package org.aethercode.core.middleware;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.runtime.AgentState;
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
 * Read-side clipping for the summarization-on-overflow fallback path.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware._overflow_clip}. When
 * {@code SummarizationMiddleware}'s {@code wrapModelCall} catches a
 * {@code ContextOverflowError}, it falls through to summarization and
 * also invokes {@link #clipOverflowTail} (or its async variant
 * {@link #aclipOverflowTail}) to shrink the trailing ToolMessage
 * batch in the preserved suffix.</p>
 *
 * <p>Two per-TM paths:</p>
 * <ul>
 *   <li><b>read_file</b> tool result: head-slice the content and
 *       append a notice pointing back to the original
 *       {@code file_path} argument. No new backend write is needed
 *       because the original file already lives at that path.</li>
 *   <li><b>Any other tool result</b>: full offload to
 *       {@code /large_tool_results/{tool_call_id}} via
 *       {@link ToolMessageEviction#offloadToolMessageContent}, then
 *       replace the message with a
 *       {@link ToolMessageEviction#TOO_LARGE_TOOL_MSG_TEMPLATE} stub.</li>
 * </ul>
 *
 * <p>Round 4 also adds a general <em>strip-oldest</em> clipper
 * ({@link #stripOldestToFit}) that runs <em>before</em> the model
 * call: when the assembled prompt + system exceeds a configured
 * {@code maxTokens} (default {@value #DEFAULT_MAX_TOKENS}), the clipper
 * drops the oldest non-system messages one at a time, keeping the
 * system prompt and the most-recent {@code minKeep} messages, until
 * the total fits. If the prompt still does not fit after the
 * pre-system messages are exhausted, the clipper optionally truncates
 * the largest remaining message down to {@code truncateToChars} chars.</p>
 */
public final class OverflowClip {
    private OverflowClip() {}

    /** Head truncation length for {@link #sliceReadFileToolMessage}. */
    public static final int READ_FILE_HEAD_CHARS = 4_000;

    /** Default token cap for the pre-model-call strip-oldest clip. */
    public static final int DEFAULT_MAX_TOKENS = 100_000;

    /** Default minimum number of trailing messages the clip must keep. */
    public static final int DEFAULT_MIN_KEEP = 4;

    /**
     * Default character cap when the strip-oldest clip is allowed to
     * truncate the largest individual message. Mirrors
     * {@link #READ_FILE_HEAD_CHARS}.
     */
    public static final int DEFAULT_TRUNCATE_TO_CHARS = 4_000;

    /**
     * Approximate chars per token used by {@link #stripOldestToFit}'s
     * default token counter. Mirrors Python's
     * {@code chars // 4} heuristic.
     */
    public static final int APPROX_CHARS_PER_TOKEN = 4;

    private static final String READ_FILE_TRUNCATION_NOTICE_TEMPLATE =
            "\n\n[Output was truncated due to context window size limits. "
                    + "The full content is at %s. "
                    + "Use read_file with offset and limit parameters to retrieve specific portions. "
                    + "For example, to read the first 100 lines, call read_file with file_path='%s', offset=0, limit=100.]";

    // -----------------------------------------------------------------
    // Threshold
    // -----------------------------------------------------------------

    /**
     * Derive a token threshold for tail-ToolMessage clipping from
     * {@code keep}. Returns the keep token budget. If {@code keep} is
     * message-based (no token info), falls back to 5,000 &mdash;
     * equivalent to a 20,000-char floor under a {@code chars / 4}
     * approximation.
     */
    public static int deriveOverflowClipThresholdTokens(ContextSize keep, Integer maxInputTokens) {
        if (keep.kind() == ContextSize.Kind.TOKENS) return keep.value();
        if (keep.kind() == ContextSize.Kind.FRACTION) {
            if (maxInputTokens == null) return 5_000;
            // Fractions were stored as int(round(f * 1_000_000)) so we can
            // recover the original value at the divide site.
            return (int) (maxInputTokens * (keep.value() / 1_000_000.0));
        }
        return 5_000;
    }

    // -----------------------------------------------------------------
    // Tail batch detection
    // -----------------------------------------------------------------

    /**
     * Return the start index and the batch of consecutive trailing
     * {@link ToolMessage}s, or {@code null} if
     * {@code messages} doesn't end with a {@code ToolMessage}.
     */
    public static ContextSize.TailBatch findTailToolMessageBatch(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        if (!(messages.get(messages.size() - 1) instanceof ToolMessage)) return null;
        int i = messages.size() - 1;
        while (i >= 0 && messages.get(i) instanceof ToolMessage) {
            i--;
        }
        int start = i + 1;
        List<ToolMessage> batch = new ArrayList<>(messages.size() - start);
        for (int j = start; j < messages.size(); j++) {
            batch.add((ToolMessage) messages.get(j));
        }
        return new ContextSize.TailBatch(start, batch);
    }

    // -----------------------------------------------------------------
    // Tool-call index
    // -----------------------------------------------------------------

    /**
     * Build a {@code tool_call_id -> {id, name, args}} index over the
     * AIMessage tool calls in {@code messages}. Mirrors the Python
     * port's {@code _build_tool_call_index}.
     */
    public static Map<String, ContextSize.ToolCallRef> buildToolCallIndex(List<Message> messages) {
        Map<String, ContextSize.ToolCallRef> index = new LinkedHashMap<>();
        if (messages == null) return index;
        for (Message m : messages) {
            if (!(m instanceof AIMessage ai)) continue;
            for (ContentBlock b : ai.content()) {
                if (!(b instanceof ContentBlock.ToolUseBlock tc)) continue;
                if (tc.id() != null && !tc.id().isEmpty()) {
                    index.put(tc.id(), new ContextSize.ToolCallRef(
                            tc.id(), tc.name() == null ? "" : tc.name(), tc.input()));
                }
            }
        }
        return index;
    }

    // -----------------------------------------------------------------
    // Per-message clipping
    // -----------------------------------------------------------------

    /**
     * Slice a {@code read_file} {@link ToolMessage}'s content
     * to {@value #READ_FILE_HEAD_CHARS} head chars and append a
     * path-pointer notice. The truncation notice mirrors
     * {@code READ_FILE_TRUNCATION_MSG} in shape so the agent
     * encounters a consistent format whether the tool truncated
     * itself or the middleware did.
     */
    public static ToolMessage sliceReadFileToolMessage(ToolMessage msg,
                                                              String originalPath) {
        String text = ToolMessageEviction.extractTextFromMessage(msg);
        String notice = String.format(READ_FILE_TRUNCATION_NOTICE_TEMPLATE, originalPath, originalPath);
        List<ContentBlock> newContent = List.of(ContentBlock.text(text.substring(
                0, Math.min(READ_FILE_HEAD_CHARS, text.length())) + notice));
        return new ToolMessage(
                msg.id(), msg.toolCallId(), newContent,
                msg.name(), msg.status(), msg.artifact(),
                msg.additionalKwargs(), msg.responseMetadata());
    }

    /**
     * Return the {@code file_path} arg from the matching read_file
     * tool_call, or {@code null}.
     */
    public static String readFileOriginalPath(ToolMessage msg,
                                              Map<String, ContextSize.ToolCallRef> tcIndex) {
        if (msg.toolCallId() == null) return null;
        ContextSize.ToolCallRef tc = tcIndex.get(msg.toolCallId());
        if (tc == null || !"read_file".equals(tc.name())) return null;
        Object path = tc.args().get("file_path");
        return path instanceof String s && !s.isEmpty() ? s : null;
    }

    /**
     * Apply the appropriate per-TM clip: read_file slice vs generic
     * eviction.
     */
    public static ToolMessage clipOneTailMessage(ToolMessage msg,
                                                          Map<String, ContextSize.ToolCallRef> tcIndex,
                                                          BackendProtocol backend,
                                                          String largeToolResultsPrefix) {
        String originalPath = readFileOriginalPath(msg, tcIndex);
        if (originalPath != null) {
            return sliceReadFileToolMessage(msg, originalPath);
        }
        return ToolMessageEviction.offloadToolMessageContent(
                msg, ToolMessageEviction.extractTextFromMessage(msg), backend, largeToolResultsPrefix);
    }

    /** Async variant of {@link #clipOneTailMessage}. */
    public static CompletableFuture<ToolMessage> aclipOneTailMessage(
            ToolMessage msg,
            Map<String, ContextSize.ToolCallRef> tcIndex,
            BackendProtocol backend,
            String largeToolResultsPrefix) {
        String originalPath = readFileOriginalPath(msg, tcIndex);
        if (originalPath != null) {
            return CompletableFuture.completedFuture(sliceReadFileToolMessage(msg, originalPath));
        }
        return ToolMessageEviction.aoffloadToolMessageContent(
                msg, ToolMessageEviction.extractTextFromMessage(msg), backend, largeToolResultsPrefix);
    }

    // -----------------------------------------------------------------
    // Whole-tail clipping
    // -----------------------------------------------------------------

    /**
     * Offload the trailing {@link ToolMessage} batch when it's
     * large enough to matter.
     *
     * <p>Engages only when {@code preservedMessages} ends with
     * consecutive {@link ToolMessage}s whose combined token
     * count reaches
     * {@link #deriveOverflowClipThresholdTokens(ContextSize, Integer)}.
     * Each large TM is written under
     * {@code {prefix}/{tool_call_id}} and replaced in-place by an
     * offload-pointer {@link ToolMessage}.</p>
     *
     * <p>Returns {@code (modified preserved, replacement TMs to persist
     * in state)}. Replacements carry the original ids so the
     * {@code add_messages} reducer overwrites the originals when the
     * caller propagates them via a {@code Command} update. The
     * replacements list omits any TM whose backend write failed
     * (those keep their originals in both lists).</p>
     */
    public static ClipResult clipOverflowTail(List<Message> preservedMessages,
                                              BackendProtocol backend,
                                              ContextSize keep,
                                              Integer maxInputTokens,
                                              ContextSize.TokenCounter tokenCounter,
                                              String largeToolResultsPrefix) {
        ContextSize.TailBatch found = findTailToolMessageBatch(preservedMessages);
        if (found == null) return new ClipResult(preservedMessages, List.of());
        int threshold = deriveOverflowClipThresholdTokens(keep, maxInputTokens);
        if (tokenCounter.apply(found.batch()) < threshold) {
            return new ClipResult(preservedMessages, List.of());
        }
        Map<String, ContextSize.ToolCallRef> tcIndex = buildToolCallIndex(preservedMessages);
        List<ToolMessage> newTail = new ArrayList<>(found.batch().size());
        boolean anyClipped = false;
        for (ToolMessage m : found.batch()) {
            ToolMessage r = clipOneTailMessage(m, tcIndex, backend, largeToolResultsPrefix);
            if (r != null) {
                if (r.id() == null) {
                    r = withNewId(r);
                }
                newTail.add(r);
                anyClipped = true;
            } else {
                newTail.add(m);
            }
        }
        if (!anyClipped) return new ClipResult(preservedMessages, List.of());

        List<Message> rebuilt = new ArrayList<>(preservedMessages.size());
        rebuilt.addAll(preservedMessages.subList(0, found.startIndex()));
        for (ToolMessage m : newTail) rebuilt.add(m);
        return new ClipResult(rebuilt, newTail);
    }

    /**
     * Async variant of {@link #clipOverflowTail}. Offloads each tail
     * TM concurrently via {@link #aclipOneTailMessage}.
     */

    /**
     * Async variant of {@link #clipOverflowTail}. Offloads each tail
     * TM concurrently via {@link #aclipOneTailMessage}.
     */
    public static CompletableFuture<ClipResult> aclipOverflowTail(List<Message> preservedMessages,
                                                                  BackendProtocol backend,
                                                                  ContextSize keep,
                                                                  Integer maxInputTokens,
                                                                  ContextSize.TokenCounter tokenCounter,
                                                                  String largeToolResultsPrefix) {
        ContextSize.TailBatch found = findTailToolMessageBatch(preservedMessages);
        if (found == null) return CompletableFuture.completedFuture(new ClipResult(preservedMessages, List.of()));
        int threshold = deriveOverflowClipThresholdTokens(keep, maxInputTokens);
        if (tokenCounter.apply(found.batch()) < threshold) {
            return CompletableFuture.completedFuture(new ClipResult(preservedMessages, List.of()));
        }
        Map<String, ContextSize.ToolCallRef> tcIndex = buildToolCallIndex(preservedMessages);
        @SuppressWarnings("unchecked")
        CompletableFuture<ToolMessage>[] futures = new CompletableFuture[found.batch().size()];
        for (int i = 0; i < found.batch().size(); i++) {
            futures[i] = aclipOneTailMessage(found.batch().get(i), tcIndex, backend, largeToolResultsPrefix);
        }
        return CompletableFuture.allOf(futures).thenApply(v -> {
            List<ToolMessage> newTail = new ArrayList<>(found.batch().size());
            boolean anyClipped = false;
            for (int i = 0; i < futures.length; i++) {
                ToolMessage m = found.batch().get(i);
                ToolMessage r = futures[i].join();
                if (r != null) {
                    if (r.id() == null) r = withNewId(r);
                    newTail.add(r);
                    anyClipped = true;
                } else {
                    newTail.add(m);
                }
            }
            if (!anyClipped) return new ClipResult(preservedMessages, List.of());
            List<Message> rebuilt = new ArrayList<>(preservedMessages.size());
            rebuilt.addAll(preservedMessages.subList(0, found.startIndex()));
            for (ToolMessage m : newTail) rebuilt.add(m);
            return new ClipResult(rebuilt, newTail);
        });
    }

    private static ToolMessage withNewId(ToolMessage r) {
        return new ToolMessage(UUID.randomUUID().toString(),
                r.toolCallId(), r.content(), r.name(), r.status(), r.artifact(),
                r.additionalKwargs(), r.responseMetadata());
    }

    /**
     * Result of {@link #clipOverflowTail} &mdash; the (possibly
     * modified) preserved list, and the replacement TMs to persist.
     */
    public record ClipResult(List<Message> preserved, List<ToolMessage> replacements) {
        public ClipResult {
            preserved = List.copyOf(preserved);
            replacements = List.copyOf(replacements);
        }

        /**
         * Convenience: apply the clip result to the supplied
         * {@link AgentState}, producing a new state with the
         * replacement messages.
         */
        public AgentState apply(AgentState state) {
            return state.withMessages(preserved);
        }
    }

    // -----------------------------------------------------------------
    //  Round 4: pre-model-call strip-oldest clipper
    // -----------------------------------------------------------------

    /**
     * Result of a {@link #stripOldestToFit} call.
     *
     * <p>Carries the (possibly shortened) message list plus counters
     * so callers can log what happened:</p>
     * <ul>
     *   <li>{@code messages} &mdash; the new list, ready to hand to
     *       the chat model.</li>
     *   <li>{@code dropped} &mdash; how many of the original
     *       non-system messages were removed from the front.</li>
     *   <li>{@code truncated} &mdash; how many of the remaining
     *       messages were head-truncated to fit the cap (0 when
     *       the strip-oldest pass alone was enough).</li>
     *   <li>{@code finalTokens} &mdash; the token count the clipper
     *       ended up with (under {@code maxTokens}).</li>
     * </ul>
     */
    public record StripResult(List<Message> messages,
                              int dropped,
                              int truncated,
                              int finalTokens) {
        public StripResult {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    /**
     * Approximate the token count of a message list by character
     * length. Uses {@link #APPROX_CHARS_PER_TOKEN} chars per token,
     * matching the Python port's {@code chars // 4} heuristic.
     * System messages are weighted like any other content; callers
     * that need a model-aware count should pass an explicit
     * {@code tokenCounter}.
     */
    public static int approximateTokenCount(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int chars = 0;
        for (Message m : messages) {
            if (m == null || m.content() == null) continue;
            for (ContentBlock b : m.content()) {
                if (b == null) continue;
                if (b instanceof ContentBlock.TextBlock t) {
                    chars += (t.text() == null ? 0 : t.text().length());
                } else {
                    // Non-text blocks (tool_use / tool_result / image / etc.) are
                    // conservatively charged 64 tokens each — a safe upper
                    // bound for short JSON or blob placeholders.
                    chars += 64 * APPROX_CHARS_PER_TOKEN;
                }
            }
        }
        return (chars + APPROX_CHARS_PER_TOKEN - 1) / APPROX_CHARS_PER_TOKEN;
    }

    /**
     * The default strip-oldest clipper. Equivalent to
     * {@link #stripOldestToFit(List, int, int, int, boolean, java.util.function.ToIntFunction)}
     * with {@code maxTokens = }{@link #DEFAULT_MAX_TOKENS},
     * {@code minKeep = }{@link #DEFAULT_MIN_KEEP},
     * {@code truncateToChars = }{@link #DEFAULT_TRUNCATE_TO_CHARS},
     * {@code truncateLargest = true}, and a built-in
     * {@link #approximateTokenCount} token counter.
     */
    public static StripResult stripOldestToFit(List<Message> messages) {
        return stripOldestToFit(messages, DEFAULT_MAX_TOKENS, DEFAULT_MIN_KEEP,
                DEFAULT_TRUNCATE_TO_CHARS, true, OverflowClip::approximateTokenCount);
    }

    /**
     * Strip the oldest non-system messages until the list fits under
     * {@code maxTokens} (counted by {@code tokenCounter}). System
     * messages are kept at the front; the trailing
     * {@code minKeep} messages are always kept. When the resulting
     * list still does not fit, the clipper optionally
     * head-truncates the largest non-system, non-trailing-keep
     * message down to {@code truncateToChars} characters so the
     * model can recover via the original tool path.
     *
     * <p>This is the round-4 {@code OverflowClip} extension called
     * by the {@code SummarizationMiddleware} <em>before</em> a
     * model call when the assembled prompt approaches the model's
     * context window. It is also a useful general-purpose utility
     * for any caller that wants to bound a message list's token
     * count without invoking summarization.</p>
     *
     * @param messages the original message list; may contain
     *                 {@link Message.SystemMessage}s at the front
     * @param maxTokens the token cap; messages are dropped (and
     *                  optionally truncated) until the total is at
     *                  or below this number
     * @param minKeep the minimum number of trailing messages the
     *                clip must preserve
     * @param truncateToChars when {@code truncateLargest} is true
     *                         and the strip-oldest pass is not
     *                         enough, head-truncate the largest
     *                         non-system, non-trailing message to
     *                         this many characters
     * @param truncateLargest whether to engage the truncate-largest
     *                        fallback at all
     * @param tokenCounter token-count function; the default uses
     *                     {@link #approximateTokenCount}
     * @return a {@link StripResult} with the new message list
     */
    public static StripResult stripOldestToFit(List<Message> messages,
                                               int maxTokens,
                                               int minKeep,
                                               int truncateToChars,
                                               boolean truncateLargest,
                                               java.util.function.ToIntFunction<List<Message>> tokenCounter) {
        if (messages == null || messages.isEmpty()) {
            return new StripResult(messages == null ? List.of() : messages, 0, 0, 0);
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive, got " + maxTokens);
        }
        if (minKeep < 0) {
            throw new IllegalArgumentException("minKeep must be non-negative, got " + minKeep);
        }
        java.util.function.ToIntFunction<List<Message>> tc = tokenCounter == null
                ? OverflowClip::approximateTokenCount
                : tokenCounter;

        // Split into (leading system messages) + (the rest, in order).
        // System messages stay at the front no matter how aggressively
        // we strip; the rest is the strip-eligible window.
        List<Message> systemPrefix = new ArrayList<>();
        List<Message> strippable = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof Message.SystemMessage) {
                systemPrefix.add(m);
            } else {
                strippable.add(m);
            }
        }
        // Keep the trailing minKeep of the strippable window. If the
        // strippable list is shorter than minKeep, the keep guard is
        // a no-op.
        int keep = Math.min(minKeep, strippable.size());
        int keepStart = strippable.size() - keep;
        List<Message> keepTail = new ArrayList<>(
                strippable.subList(keepStart, strippable.size()));
        List<Message> droppable = new ArrayList<>(
                strippable.subList(0, keepStart));

        int dropped = 0;
        int totalTokens = tc.applyAsInt(concatenate(systemPrefix, droppable, keepTail));
        // Strip oldest non-system messages (from the front of
        // droppable) until the total is under the cap or the
        // droppable window is empty.
        while (totalTokens > maxTokens && !droppable.isEmpty()) {
            droppable.remove(0);
            dropped++;
            totalTokens = tc.applyAsInt(concatenate(systemPrefix, droppable, keepTail));
        }

        int truncated = 0;
        if (totalTokens > maxTokens && truncateLargest) {
            // Pick the largest non-system, non-trailing-keep message
            // (still in droppable — keepTail is sacred) and head-truncate
            // it. If the only offending message is in keepTail, we
            // surface a Truncation notice on the very first keepTail
            // entry as a last-resort fallback.
            // Pass 0: try the droppable window first.
            // Pass 1: fall back to the keepTail (only when pass 0
            // found no candidate in droppable, i.e. it was empty).
            List<Message>[] searchOrder = (List<Message>[]) new List[]{
                    droppable, keepTail
            };
            for (List<Message> searchDroppable : searchOrder) {
                if (totalTokens <= maxTokens) break;
                int targetIdx = findLargestIndex(droppable, keepTail, searchDroppable);
                if (targetIdx < 0) continue;
                boolean inDroppable = targetIdx < droppable.size();
                Message target = inDroppable ? droppable.get(targetIdx) : keepTail.get(targetIdx - droppable.size());
                Message truncated1 = headTruncate(target, truncateToChars);
                if (inDroppable) {
                    droppable.set(targetIdx, truncated1);
                } else {
                    keepTail.set(targetIdx - droppable.size(), truncated1);
                }
                truncated++;
                totalTokens = tc.applyAsInt(concatenate(systemPrefix, droppable, keepTail));
            }
        }

        List<Message> finalList = new ArrayList<>(
                systemPrefix.size() + droppable.size() + keepTail.size());
        finalList.addAll(systemPrefix);
        finalList.addAll(droppable);
        finalList.addAll(keepTail);
        return new StripResult(finalList, dropped, truncated, totalTokens);
    }

    private static List<Message> concatenate(List<Message> a, List<Message> b, List<Message> c) {
        List<Message> out = new ArrayList<>(a.size() + b.size() + c.size());
        out.addAll(a); out.addAll(b); out.addAll(c);
        return out;
    }

    /**
     * Return the index (in the concatenated
     * {@code droppable + keepTail} view) of the message with the
     * largest character count. {@code searchDroppable} selects which
     * half to scan: pass 0 to search the droppable window (preferred
     * &mdash; the keepTail is sacred), pass 1 to fall back to the
     * keepTail. Returns {@code -1} when the half is empty.
     */
    private static int findLargestIndex(List<Message> droppable, List<Message> keepTail,
                                        List<Message> searchDroppable) {
        if (searchDroppable.isEmpty()) return -1;
        int bestIdx = -1;
        int bestChars = -1;
        for (int i = 0; i < searchDroppable.size(); i++) {
            int chars = characterLength(searchDroppable.get(i));
            if (chars > bestChars) {
                bestChars = chars;
                // The caller wants a flat index across the
                // (droppable ++ keepTail) view. The keepTail half is
                // searched with an offset equal to droppable.size().
                bestIdx = i + (searchDroppable == droppable ? 0 : droppable.size());
            }
        }
        return bestIdx;
    }

    private static int characterLength(Message m) {
        if (m == null || m.content() == null) return 0;
        int chars = 0;
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                chars += t.text() == null ? 0 : t.text().length();
            } else {
                chars += 64 * APPROX_CHARS_PER_TOKEN;
            }
        }
        return chars;
    }

    /**
     * Return a copy of {@code m} whose every text content block has
     * been head-truncated to {@code maxChars} characters. A trailing
     * "[truncated N chars]" notice is appended so the model can see
     * that the message is no longer complete. Non-text blocks are
     * preserved verbatim.
     */
    private static Message headTruncate(Message m, int maxChars) {
        if (m == null || maxChars < 0) return m;
        List<ContentBlock> newBlocks = new ArrayList<>();
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                String text = t.text() == null ? "" : t.text();
                if (text.length() <= maxChars) {
                    newBlocks.add(b);
                } else {
                    String head = text.substring(0, maxChars);
                    int dropped = text.length() - maxChars;
                    String notice = "\n[... truncated " + dropped + " chars by OverflowClip]";
                    newBlocks.add(ContentBlock.text(head + notice));
                }
            } else {
                newBlocks.add(b);
            }
        }
        if (m instanceof AIMessage ai) {
            return new AIMessage(ai.id(), newBlocks);
        }
        if (m instanceof ToolMessage tm) {
            return new ToolMessage(tm.id(), tm.toolCallId(), newBlocks,
                    tm.name(), tm.status(), tm.artifact(),
                    tm.additionalKwargs(), tm.responseMetadata());
        }
        if (m instanceof Message.HumanMessage hm) {
            return new Message.HumanMessage(hm.id(), newBlocks);
        }
        if (m instanceof Message.SystemMessage sm) {
            return new Message.SystemMessage(sm.id(), newBlocks);
        }
        return m;
    }
}
