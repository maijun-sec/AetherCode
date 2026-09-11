package org.aethercode.core.middleware;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * Read-side clipping for the summarization-on-overflow fallback
 * path.
 *
 * <p>When {@code SummarizationMiddleware.wrapModelCallWithEvents}
 * catches a {@code ContextOverflowError} it falls through to
 * summarization and <em>also</em> invokes
 * {@link #clipOverflowTail} (or its async variant
 * {@link #aclipOverflowTail}) to shrink the trailing
 * {@link ToolMessage} batch in the preserved suffix.</p>
 *
 * <p>Two per-TM paths:</p>
 * <ul>
 *   <li>{@code read_file} tool result: head-slice the content and
 *       append a notice pointing back to the original {@code file_path}
 *       argument. No new backend write is needed because the original
 *       file already lives at that path.</li>
 *   <li>Any other tool result: full offload to
 *       {@code /large_tool_results/{tool_call_id}} via the shared
 *       {@link ToolMessageEviction} helper, then replace the
 *       message with an offload-pointer stub.</li>
 * </ul>
 *
 * <p>Java-native port of
 * {@code deepagents.middleware._overflow_clip}.</p>
 */
public final class SummarizationOverflowClip {

    /** Default head-slice length for {@code read_file} results. */
    public static final int READ_FILE_HEAD_CHARS = 4_000;
    /** Default fallback token threshold for non-tokens keep. */
    public static final int DEFAULT_FLOOR_TOKENS = 5_000;

    private SummarizationOverflowClip() {}

    // -----------------------------------------------------------------
    //  Threshold
    // -----------------------------------------------------------------

    /**
     * Derive a token threshold for tail-TM clipping from
     * {@code keep}. If {@code keep} is message-based, fall back to
     * {@value #DEFAULT_FLOOR_TOKENS} -- equivalent to a 20_000-char
     * floor under a {@code chars / 4} approximation.
     */
    public static int deriveOverflowClipThresholdTokens(ContextSize keep,
                                                        Integer maxInputTokens) {
        if (keep == null) return DEFAULT_FLOOR_TOKENS;
        return switch (keep.kind()) {
            case TOKENS -> keep.value();
            case FRACTION -> {
                if (maxInputTokens == null) yield DEFAULT_FLOOR_TOKENS;
                yield (int) (maxInputTokens * (keep.value() / 1_000_000.0));
            }
            case MESSAGES -> DEFAULT_FLOOR_TOKENS;
        };
    }

    // -----------------------------------------------------------------
    //  Tail TM batch
    // -----------------------------------------------------------------

    /**
     * Return {@code (startIndex, batch)} if {@code messages} ends
     * with consecutive {@link ToolMessage}s, otherwise
     * {@code null}.
     */
    public static TailBatch findTailToolMessageBatch(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        if (!(messages.get(messages.size() - 1) instanceof ToolMessage)) {
            return null;
        }
        int i = messages.size() - 1;
        while (i >= 0 && messages.get(i) instanceof ToolMessage) {
            i--;
        }
        int start = i + 1;
        List<Message> batch = new ArrayList<>(
                messages.size() - start);
        for (int j = start; j < messages.size(); j++) {
            batch.add(messages.get(j));
        }
        return new TailBatch(start, batch);
    }

    public record TailBatch(int startIndex, List<Message> batch) {
        public TailBatch {
            batch = batch == null ? List.of() : List.copyOf(batch);
        }
    }

    // -----------------------------------------------------------------
    //  Tool-call index
    // -----------------------------------------------------------------

    /**
     * Build a {@code toolCallId -> toolCall} index from AIMessage
     * tool_calls in {@code messages}. Used to look up the
     * {@code read_file} {@code file_path} argument for tail-TM
     * slicing.
     */
    public static Map<String, Map<String, Object>> buildToolCallIndex(
            List<Message> messages) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        if (messages == null) return index;
        for (Message m : messages) {
            if (m instanceof AIMessage aim && aim.content() != null) {
                for (ContentBlock b : aim.content()) {
                    if (b instanceof ContentBlock.ToolUseBlock tu) {
                        String id = tu.id();
                        if (id != null && !id.isEmpty()) {
                            Map<String, Object> tc = new LinkedHashMap<>();
                            tc.put("id", id);
                            tc.put("name", tu.name());
                            tc.put("args", tu.input() == null ? Map.of() : tu.input());
                            index.put(id, tc);
                        }
                    }
                }
            }
        }
        return index;
    }

    // -----------------------------------------------------------------
    //  read_file path resolution
    // -----------------------------------------------------------------

    /**
     * Return the {@code file_path} argument from the matching
     * {@code read_file} tool call, or {@code null} if the message
     * is not a {@code read_file} result.
     */
    @SuppressWarnings("unchecked")
    public static String readFileOriginalPath(
            ToolMessage msg,
            Map<String, Map<String, Object>> toolCallIndex) {
        if (msg == null || msg.toolCallId() == null) return null;
        Map<String, Object> tc = toolCallIndex.get(msg.toolCallId());
        if (tc == null) return null;
        if (!"read_file".equals(tc.get("name"))) return null;
        Object args = tc.get("args");
        if (!(args instanceof Map<?, ?>)) return null;
        Object path = ((Map<String, Object>) args).get("file_path");
        return path instanceof String s && !s.isEmpty() ? s : null;
    }

    // -----------------------------------------------------------------
    //  Single TM clip
    // -----------------------------------------------------------------

    /**
     * Slice a {@code read_file} {@code ToolMessage}'s content to the
     * head {@value #READ_FILE_HEAD_CHARS} chars and append a
     * path-pointer notice. The full file is already on the backend
     * at {@code originalPath}, and the agent can recover with
     * {@code read_file(file_path=originalPath, offset=N, limit=K)}.
     */
    public static ToolMessage sliceReadFileToolMessage(
            ToolMessage msg, String originalPath) {
        if (msg == null) return null;
        String content = ToolMessageEviction.extractTextFromMessage(msg);
        String notice = "\n\n[Output was truncated due to context window size limits. "
                + "The full content is at " + originalPath + ". "
                + "Use read_file with offset and limit parameters to retrieve specific portions. "
                + "For example, to read the first 100 lines, call read_file with file_path='"
                + originalPath + "', offset=0, limit=100.]";
        String sliced = (content == null ? "" : content.substring(
                0, Math.min(READ_FILE_HEAD_CHARS, content.length()))) + notice;
        return new ToolMessage(
                msg.id(), msg.toolCallId(),
                List.of(ContentBlock.text(sliced)),
                msg.name(), msg.status(), msg.artifact(),
                msg.additionalKwargs(), msg.responseMetadata());
    }

    /**
     * Apply the per-TM clip: {@code read_file} slice vs generic
     * eviction. Returns the replacement {@code ToolMessage} or
     * {@code null} when the offload failed.
     */
    public static ToolMessage clipOneTailMessage(
            ToolMessage msg,
            Map<String, Map<String, Object>> toolCallIndex,
            BackendProtocol backend,
            String largeToolResultsPrefix) {
        if (msg == null) return null;
        String originalPath = readFileOriginalPath(msg, toolCallIndex);
        if (originalPath != null) {
            return sliceReadFileToolMessage(msg, originalPath);
        }
        String text = ToolMessageEviction.extractTextFromMessage(msg);
        return ToolMessageEviction.offloadToolMessageContent(
                msg, text, backend, largeToolResultsPrefix);
    }

    /** Async variant of {@link #clipOneTailMessage}. */
    public static CompletableFuture<ToolMessage> aclipOneTailMessage(
            ToolMessage msg,
            Map<String, Map<String, Object>> toolCallIndex,
            BackendProtocol backend,
            String largeToolResultsPrefix) {
        if (msg == null) return CompletableFuture.completedFuture(null);
        String originalPath = readFileOriginalPath(msg, toolCallIndex);
        if (originalPath != null) {
            return CompletableFuture.completedFuture(
                    sliceReadFileToolMessage(msg, originalPath));
        }
        String text = ToolMessageEviction.extractTextFromMessage(msg);
        return ToolMessageEviction.aoffloadToolMessageContent(
                msg, text, backend, largeToolResultsPrefix);
    }

    // -----------------------------------------------------------------
    //  Batch clip
    // -----------------------------------------------------------------

    /**
     * Offload the trailing {@code ToolMessage} batch when it's
     * large enough to matter.
     *
     * <p>Engages only when {@code preservedMessages} ends with
     * consecutive {@code ToolMessage}s whose combined token count
     * reaches the derived threshold. Each large TM is written under
     * {@code largeToolResultsPrefix/{tool_call_id}} and replaced
     * in-place by an offload-pointer TM.</p>
     *
     * @return {@code (modifiedPreserved, newTail)} where
     *         {@code newTail} is the list of replacement TMs the
     *         caller should propagate via the runtime's state-update
     *         mechanism so the originals are overwritten. Empty
     *         when no clipping is needed.
     */
    public static Result clipOverflowTail(
            List<Message> preservedMessages,
            BackendProtocol backend,
            ContextSize keep,
            Integer maxInputTokens,
            java.util.function.ToIntFunction<List<Message>> tokenCounter,
            String largeToolResultsPrefix) {
        TailBatch found = findTailToolMessageBatch(preservedMessages);
        if (found == null) {
            return new Result(preservedMessages == null ? List.of() : preservedMessages, List.of());
        }
        if (tokenCounter.applyAsInt(found.batch()) < deriveOverflowClipThresholdTokens(
                keep, maxInputTokens)) {
            return new Result(preservedMessages, List.of());
        }
        Map<String, Map<String, Object>> tcIndex = buildToolCallIndex(preservedMessages);
        List<Message> newTail = new ArrayList<>(found.batch().size());
        List<Message> replacements = new ArrayList<>();
        boolean anyClipped = false;
        for (Message m : found.batch()) {
            if (!(m instanceof ToolMessage tm)) {
                newTail.add(m);
                continue;
            }
            ToolMessage r = clipOneTailMessage(tm, tcIndex, backend, largeToolResultsPrefix);
            if (r != null) {
                if (r.id() == null) {
                    r = withId(r, UUID.randomUUID().toString());
                }
                newTail.add(r);
                replacements.add(r);
                anyClipped = true;
            } else {
                newTail.add(m);
            }
        }
        if (!anyClipped) {
            return new Result(preservedMessages, List.of());
        }
        List<Message> merged = new ArrayList<>(preservedMessages.size());
        merged.addAll(preservedMessages.subList(0, found.startIndex()));
        merged.addAll(newTail);
        return new Result(merged, replacements);
    }

    /** Async variant of {@link #clipOverflowTail}. */
    public static CompletableFuture<Result> aclipOverflowTail(
            List<Message> preservedMessages,
            BackendProtocol backend,
            ContextSize keep,
            Integer maxInputTokens,
            java.util.function.ToIntFunction<List<Message>> tokenCounter,
            String largeToolResultsPrefix) {
        TailBatch found = findTailToolMessageBatch(preservedMessages);
        if (found == null) {
            return CompletableFuture.completedFuture(
                    new Result(preservedMessages == null ? List.of() : preservedMessages, List.of()));
        }
        if (tokenCounter.applyAsInt(found.batch()) < deriveOverflowClipThresholdTokens(
                keep, maxInputTokens)) {
            return CompletableFuture.completedFuture(new Result(preservedMessages, List.of()));
        }
        Map<String, Map<String, Object>> tcIndex = buildToolCallIndex(preservedMessages);
        List<CompletableFuture<ToolMessage>> futures = new ArrayList<>();
        for (Message m : found.batch()) {
            if (m instanceof ToolMessage tm) {
                futures.add(aclipOneTailMessage(tm, tcIndex, backend, largeToolResultsPrefix));
            }
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        return all.thenApply(v -> {
            List<Message> newTail = new ArrayList<>(found.batch().size());
            List<Message> replacements = new ArrayList<>();
            boolean anyClipped = false;
            for (int i = 0; i < found.batch().size(); i++) {
                Message m = found.batch().get(i);
                if (!(m instanceof ToolMessage tm)) {
                    newTail.add(m);
                    continue;
                }
                ToolMessage r = futures.get(i).join();
                if (r != null) {
                    if (r.id() == null) r = withId(r, UUID.randomUUID().toString());
                    newTail.add(r);
                    replacements.add(r);
                    anyClipped = true;
                } else {
                    newTail.add(m);
                }
            }
            if (!anyClipped) {
                return new Result(preservedMessages, List.of());
            }
            List<Message> merged = new ArrayList<>(preservedMessages.size());
            merged.addAll(preservedMessages.subList(0, found.startIndex()));
            merged.addAll(newTail);
            return new Result(merged, replacements);
        });
    }

    /** Build a copy of {@code msg} with a fresh id. */
    private static ToolMessage withId(ToolMessage msg, String newId) {
        return new ToolMessage(
                newId, msg.toolCallId(), msg.content(),
                msg.name(), msg.status(), msg.artifact(),
                msg.additionalKwargs(), msg.responseMetadata());
    }

    /** Pair returned by the clip helpers. */
    public record Result(List<Message> preservedMessages,
                        List<Message> newTailReplacements) {
        public Result {
            preservedMessages = preservedMessages == null ? List.of() : List.copyOf(preservedMessages);
            newTailReplacements = newTailReplacements == null ? List.of() : List.copyOf(newTailReplacements);
        }
    }
}
