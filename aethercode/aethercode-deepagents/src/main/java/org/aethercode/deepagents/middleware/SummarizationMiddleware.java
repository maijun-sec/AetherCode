package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.ContextSize;
import org.aethercode.core.middleware.SummarizationCutoff;
import org.aethercode.core.middleware.SummarizationEvent;
import org.aethercode.core.middleware.SummarizationEventApplier;
import org.aethercode.core.middleware.SummarizationHistory;
import org.aethercode.core.middleware.SummarizationMedia;
import org.aethercode.core.middleware.SummarizationOverflowClip;
import org.aethercode.core.middleware.SummarizationPrompts;
import org.aethercode.core.middleware.SummarizationTokenCounter;
import org.aethercode.core.middleware.SummarizationTruncateArgs;
import org.aethercode.core.middleware.Summarizer;
import org.aethercode.core.middleware.SummarizerRegistry;
import org.aethercode.core.middleware.SummarizerUnavailableError;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.fs.backend.WriteResult;
import org.aethercode.deepagents.langchain_compat.exceptions.ContextOverflowError;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.HumanMessage;
import org.aethercode.core.runtime.Message.SystemMessage;

/**
 * Summarization middleware for automatic conversation compaction.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.summarization.SummarizationMiddleware}.
 * Triggers a compaction pass when the conversation's token count
 * exceeds a configurable threshold; older messages are summarized
 * via a {@link Summarizer} SPI and the full history is offloaded
 * to the backend for later retrieval.</p>
 */
public class SummarizationMiddleware implements Middleware {

    /** ISO-8601 timestamp formatter (UTC, second precision). */
    public static final DateTimeFormatter ISO_8601 = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final BackendProtocol backend;
    private final ContextSize trigger;
    private final ContextSize keep;
    private final String summaryPrompt;
    private final int trimTokenLimit;
    private final int messagesToKeep;
    private final String summarizerName;
    /** Token counter. {@code null} -> use the default approximate
     *  counter (1 token per message for testing). */
    private final Object tokenCounter;
    /** Truncate-args settings. {@code null} -> disabled. */
    private final SummarizationTruncateArgs.TruncateArgsSettings truncateArgsSettings;

    public SummarizationMiddleware(BackendProtocol backend,
                                   ContextSize trigger,
                                   ContextSize keep,
                                   String summaryPrompt,
                                   int trimTokenLimit,
                                   int messagesToKeep,
                                   String summarizerName) {
        this(backend, trigger, keep, summaryPrompt, trimTokenLimit,
                messagesToKeep, summarizerName, null, null);
    }

    /**
     * Full constructor: trigger/keep, summary prompt, trim limit,
     * message-count keep fallback, summarizer name, optional token
     * counter, optional truncate-args settings.
     */
    public SummarizationMiddleware(BackendProtocol backend,
                                   ContextSize trigger,
                                   ContextSize keep,
                                   String summaryPrompt,
                                   int trimTokenLimit,
                                   int messagesToKeep,
                                   String summarizerName,
                                   Object tokenCounter,
                                   SummarizationTruncateArgs.TruncateArgsSettings truncateArgsSettings) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.trigger = trigger == null ? ContextSize.fraction(0.85) : trigger;
        this.keep = keep == null ? ContextSize.fraction(0.10) : keep;
        this.summaryPrompt = summaryPrompt == null
                ? SummarizationPrompts.DEEPAGENTS_DEFAULT_SUMMARY_PROMPT : summaryPrompt;
        this.trimTokenLimit = trimTokenLimit <= 0
                ? SummarizationPrompts.DEFAULT_TRIM_TOKEN_LIMIT : trimTokenLimit;
        this.messagesToKeep = messagesToKeep <= 0
                ? SummarizationPrompts.DEFAULT_MESSAGES_TO_KEEP : messagesToKeep;
        this.summarizerName = summarizerName;
        this.tokenCounter = tokenCounter;
        this.truncateArgsSettings = truncateArgsSettings;
    }

    public SummarizationMiddleware(BackendProtocol backend) {
        this(backend, null, null, null, -1, -1, null);
    }

    public BackendProtocol backend() { return backend; }
    public ContextSize trigger() { return trigger; }
    public ContextSize keep() { return keep; }
    public String summaryPrompt() { return summaryPrompt; }
    public int trimTokenLimit() { return trimTokenLimit; }
    public int messagesToKeep() { return messagesToKeep; }
    public Object tokenCounter() { return tokenCounter; }
    public SummarizationTruncateArgs.TruncateArgsSettings truncateArgsSettings() {
        return truncateArgsSettings;
    }

    @Override
    public String name() { return "SummarizationMiddleware"; }

    // -----------------------------------------------------------------
    // beforeModel: trigger compaction if the conversation is over budget
    // -----------------------------------------------------------------

    @Override
    public AgentState beforeModel(AgentState state, Runtime runtime) {
        List<Message> messages = state.messages();
        if (messages.isEmpty()) return state;
        if (shouldCompact(messages, state)) {
            return compact(state, messages);
        }
        return state;
    }

    /** Test hook: decide whether compaction should fire for the
     *  given transcript. The default returns false (we don't have
     *  a real token counter yet); subclasses can override. */
    protected boolean shouldCompact(List<Message> messages, AgentState state) {
        return false;
    }

    /** Run a compaction pass on {@code messages}: summarize the
     *  older ones, offload the full history to the backend (with
     *  inline media extracted to per-file references), and replace
     *  the messages list with a HumanMessage carrying the
     *  summary, plus the last {@code messagesToKeep} messages. */
    public AgentState compact(AgentState state, List<Message> messages) {
        if (messages.size() <= messagesToKeep) return state;
        List<Message> toSummarize = messages.subList(0, messages.size() - messagesToKeep);
        List<Message> toKeep = messages.subList(messages.size() - messagesToKeep, messages.size());

        Instant now = Instant.now();
        Map<String, Object> stateMap = state.extensions() == null
                ? Map.of()
                : state.extensions();
        String sessionId = SummarizationHistory.getSessionId(stateMap);

        // Upload any inline media (data: URLs) to the backend and
        // rewrite them to per-file path references. Same logic as
        // the Python port's _offload_inline_media; the path map is
        // shared between the offloaded history section and the
        // summary so the summary carries the references too.
        var mediaOffload = offloadInlineMedia(toSummarize);
        List<Message> toSummarizeOffloaded = mediaOffload.messages();
        int failedMedia = mediaOffload.failedBlockCount();

        // Offload the full history (now media-clean) to the
        // backend. Append-mode: a new ## Summarized at <ts>
        // section is added to the per-session markdown file.
        String historyPath = SummarizationHistory.offloadToBackend(
                backend, toSummarizeOffloaded, sessionId, now);

        // Summarize older messages.
        String summary;
        try {
            Summarizer s = SummarizerRegistry.get(summarizerName);
            summary = s.summarize(toSummarizeOffloaded,
                    Map.of("summary_prompt", summaryPrompt));
        } catch (SummarizerUnavailableError e) {
            // No real summarizer: just keep the head of the
            // history (a placeholder "summary" so the loop can
            // continue).
            summary = "Summarizer not configured. The first "
                    + toSummarizeOffloaded.size() + " messages were offloaded to "
                    + (historyPath == null
                            ? SummarizationHistory.historyPath(backend, sessionId)
                            : historyPath) + ".";
        }

        // Build the new message list: HumanMessage carrying the
        // summary (with lc_source=summarization so future
        // compactions can filter it), then the messages to keep.
        List<Message> summaryMessages = SummarizationHistory.buildNewMessagesWithPath(
                summary, historyPath);
        List<Message> rebuilt = new ArrayList<>(toKeep.size() + 1);
        rebuilt.add(summaryMessages.get(0));
        rebuilt.addAll(toKeep);

        // Record the event on the agent state.
        String nowStr = ISO_8601.format(now);
        SummarizationEvent event = new SummarizationEvent(
                nowStr, summary,
                messagesToIds(toSummarizeOffloaded),
                historyPath == null
                        ? SummarizationHistory.historyPath(backend, sessionId)
                        : historyPath,
                Map.of("session_id", sessionId,
                        "failed_media_blocks", failedMedia));
        List<SummarizationEvent> existing = eventsFor(state);
        List<SummarizationEvent> next = new ArrayList<>(existing.size() + 1);
        next.addAll(existing);
        next.add(event);
        AgentState out = state.withMessages(rebuilt);
        out = out.withExtension(SummarizationPrompts.SUMMARY_KEY, next);
        // Persist the session id on the state so chained
        // compactions reuse the same history file. Mirrors the
        // Python port's `_summarization_session_id` private state
        // field written by the compact tool.
        out = out.withExtension("_summarization_session_id", sessionId);
        return out;
    }

    /**
     * Decode inline {@code data:} media blocks to files and rewrite
     * them to path references. Mirrors the Python port's
     * {@code _offload_inline_media}: each unique media file is
     * uploaded once to
     * {@code <artifacts_root>/conversation_history/media/<sha256[:16]>.<ext>}
     * and identical media across messages are deduped by content
     * hash.
     *
     * <p>The empty / no-inline-media case is a no-op: messages are
     * returned unchanged and {@code failedBlockCount} is zero.</p>
     */
    public SummarizationMedia.Result offloadInlineMedia(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new SummarizationMedia.Result(
                    messages == null ? List.of() : messages, 0);
        }
        // Pre-scan: do any blocks carry inline data? If not,
        // skip the entire upload pass (mirrors Python's
        // `if not saw_inline_media: return messages, 0`).
        boolean sawInlineMedia = false;
        for (Message m : messages) {
            if (m.content() == null) continue;
            for (ContentBlock b : m.content()) {
                if (SummarizationMedia.extractDataUrl(b) != null) {
                    sawInlineMedia = true;
                    break;
                }
            }
            if (sawInlineMedia) break;
        }
        if (!sawInlineMedia) {
            return new SummarizationMedia.Result(messages, 0);
        }

        // First pass: upload each unique media file individually
        // for per-block failure tracking.
        java.util.Map<String, String> pathMap = new java.util.LinkedHashMap<>();
        java.util.Set<String> failedKeys = new java.util.HashSet<>();
        for (Message m : messages) {
            if (m.content() == null) continue;
            for (ContentBlock b : m.content()) {
                String dataUrl = SummarizationMedia.extractDataUrl(b);
                if (dataUrl == null) continue;
                var decodedOpt = SummarizationMedia.decodeDataUrl(dataUrl);
                if (decodedOpt.isEmpty()) continue;
                var decoded = decodedOpt.get();
                String key = SummarizationMedia.sha256Prefix(decoded.raw());
                if (pathMap.containsKey(key) || failedKeys.contains(key)) continue;
                String mediaPath = SummarizationHistory.mediaPathPrefix(backend)
                        + "/" + key + "." + decoded.extension();
                try {
                    var responses = backend.uploadFiles(
                            List.of(new BackendProtocol.PathedBytes(
                                    mediaPath, decoded.raw())));
                    String err = SummarizationMedia.uploadResponseError(responses);
                    if (err != null) {
                        java.util.logging.Logger.getLogger(
                                SummarizationMiddleware.class.getName())
                                .warning(() -> "Failed to upload media "
                                        + mediaPath + " to backend: " + err);
                        failedKeys.add(key);
                        continue;
                    }
                    pathMap.put(key, mediaPath);
                } catch (RuntimeException e) {
                    java.util.logging.Logger.getLogger(
                            SummarizationMiddleware.class.getName())
                            .warning(() -> "Failed to upload media "
                                    + mediaPath + " to backend: "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage());
                    failedKeys.add(key);
                }
            }
        }
        return SummarizationMedia.rewriteDataUrlBlocks(messages, pathMap);
    }

    /**
     * Async twin of {@link #offloadInlineMedia}. Iterates the messages
     * in order, decodes each {@code data:} URL block, and uploads it
     * via {@link BackendProtocol#auploadFiles}. Per-block failures
     * (response with {@code error != null} or thrown exception) are
     * logged and tracked so the rewrite step can substitute a
     * placeholder. Identical media across messages are deduped by
     * content hash.
     *
     * <p>Mirrors Python's {@code _aoffload_inline_media} and the
     * sync version's per-block failure semantics.</p>
     */
    public CompletableFuture<SummarizationMedia.Result> aoffloadInlineMedia(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new SummarizationMedia.Result(
                            messages == null ? List.of() : messages, 0));
        }
        // Pre-scan: do any blocks carry inline data? If not, skip
        // the entire upload pass.
        boolean sawInlineMedia = false;
        for (Message m : messages) {
            if (m.content() == null) continue;
            for (ContentBlock b : m.content()) {
                if (SummarizationMedia.extractDataUrl(b) != null) {
                    sawInlineMedia = true;
                    break;
                }
            }
            if (sawInlineMedia) break;
        }
        if (!sawInlineMedia) {
            return CompletableFuture.completedFuture(
                    new SummarizationMedia.Result(messages, 0));
        }

        // First pass: collect (key, decoded, mediaPath) tuples so we
        // can dedupe without nested async hops, then upload each
        // unique entry. Sequential uploads preserve order matching
        // the Python port's async walk.
        java.util.Map<String, String> pathMap = new java.util.LinkedHashMap<>();
        java.util.Set<String> failedKeys = new java.util.HashSet<>();
        java.util.List<Object[]> pending = new java.util.ArrayList<>();
        for (Message m : messages) {
            if (m.content() == null) continue;
            for (ContentBlock b : m.content()) {
                String dataUrl = SummarizationMedia.extractDataUrl(b);
                if (dataUrl == null) continue;
                var decodedOpt = SummarizationMedia.decodeDataUrl(dataUrl);
                if (decodedOpt.isEmpty()) continue;
                var decoded = decodedOpt.get();
                String key = SummarizationMedia.sha256Prefix(decoded.raw());
                if (pathMap.containsKey(key) || failedKeys.contains(key)) continue;
                String mediaPath = SummarizationHistory.mediaPathPrefix(backend)
                        + "/" + key + "." + decoded.extension();
                // Optimistically record the path; the upload
                // loop below will remove it on failure so the
                // rewrite step treats the block as a failed
                // offload. The dedup check on the next iteration
                // also benefits from this — we don't add the
                // same content to `pending` twice.
                pathMap.put(key, mediaPath);
                pending.add(new Object[]{key, mediaPath, decoded.raw()});
            }
        }
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new SummarizationMedia.Result(messages, 0));
        }
        // Walk sequentially, awaiting each upload. A failure logs
        // and tracks the key; the loop continues with the next.
        CompletableFuture<SummarizationMedia.Result> result =
                CompletableFuture.completedFuture(null);
        for (Object[] entry : pending) {
            String key = (String) entry[0];
            String mediaPath = (String) entry[1];
            byte[] raw = (byte[]) entry[2];
            result = result.thenCompose(ignored ->
                    backend.auploadFiles(List.of(
                            new BackendProtocol.PathedBytes(mediaPath, raw)))
                    .handle((responses, ex) -> {
                        if (ex != null) {
                            java.util.logging.Logger.getLogger(
                                    SummarizationMiddleware.class.getName())
                                    .warning(() -> "Failed to upload media "
                                            + mediaPath + " to backend: "
                                            + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                            // Drop the optimistic pathMap
                            // entry so the rewrite step treats
                            // this block as a failed offload.
                            pathMap.remove(key);
                            failedKeys.add(key);
                            return null;
                        }
                        String err = SummarizationMedia.uploadResponseError(responses);
                        if (err != null) {
                            java.util.logging.Logger.getLogger(
                                    SummarizationMiddleware.class.getName())
                                    .warning(() -> "Failed to upload media "
                                            + mediaPath + " to backend: " + err);
                            pathMap.remove(key);
                            failedKeys.add(key);
                            return null;
                        }
                        return null;
                    }));
        }
        return result.thenApply(ignored ->
                SummarizationMedia.rewriteDataUrlBlocks(messages, pathMap));
    }

    @SuppressWarnings("unchecked")
    private static List<SummarizationEvent> eventsFor(AgentState state) {
        Object raw = state.extensions().get(SummarizationPrompts.SUMMARY_KEY);
        if (raw instanceof List<?> list) {
            List<SummarizationEvent> out = new ArrayList<>();
            for (Object o : list) if (o instanceof SummarizationEvent e) out.add(e);
            return out;
        }
        return List.of();
    }

    private static List<String> messagesToIds(List<Message> messages) {
        List<String> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            String id = m.id();
            if (id != null && !id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** Render the markdown-formatted conversation history for a given
     *  session. Public for direct testability. */
    public static String formatHistory(List<Message> messages, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Conversation history: ").append(sessionId).append("\n\n");
        for (Message m : messages) {
            sb.append("- [").append(m.role()).append("] ");
            if (m.id() != null && !m.id().isEmpty()) sb.append("(id=").append(m.id()).append(") ");
            sb.append(ContentBlock.flattenText(m.content()).replace("\n", " "));
            sb.append("\n");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    //  Shared compact pipeline
    // -----------------------------------------------------------------

    /**
     * Result of a compact pipeline run. Both the model-call path
     * ({@link #wrapModelCallWithEvents}) and the tool-call path
     * ({@link SummarizationToolMiddleware#runCompact}) share this
     * pipeline. The {@code newMessages} list is the post-summary
     * effective message list; {@code event} is the
     * {@link SummarizationEvent} for the state update;
     * {@code sessionId} is the per-invocation id; {@code historyPath}
     * is the backend path where the offload landed (or {@code null}
     * when offload failed).
     */
    public record CompactPipelineResult(
            List<Message> newMessages,
            SummarizationEvent event,
            String sessionId,
            String historyPath,
            int effectiveCutoff,
            int stateCutoff,
            int summarizedCount,
            int failedMediaCount) {
    }

    /**
     * Run the compact pipeline: apply prior event, count tokens,
     * truncate args, determine cutoff, partition, offload media +
     * history, generate summary, build new messages and event.
     * Returns a {@link CompactPipelineResult} that callers can
     * either feed to the model (the model-call path) or wrap in a
     * {@link org.aethercode.deepagents.langchain_compat.langgraph.Command}
     * (the tool-call path).
     */
    public CompactPipelineResult runCompactPipeline(AgentState state,
                                                    List<Message> messages,
                                                    Runtime runtime) {
        return runCompactPipeline(state, messages, runtime, /* force */ false);
    }

    /**
     * Force a compaction pass, ignoring the configured
     * {@code trigger}. Used by the {@link ContextOverflowError}
     * fallback path: the original trigger was below threshold,
     * but a model call has overflowed, so we summarise
     * regardless. When {@code force} is {@code false}, the
     * pipeline consults the configured trigger as usual.
     */
    public CompactPipelineResult runCompactPipeline(AgentState state,
                                                    List<Message> messages,
                                                    Runtime runtime,
                                                    boolean force) {
        // 1. Reconstruct the effective message list from any prior
        //    summarization event carried in the state extensions.
        Map<String, Object> ext = state == null || state.extensions() == null
                ? Map.of() : state.extensions();
        Map<String, Object> priorEvent = priorEventFrom(ext.get("_summarization_event"));
        List<Message> effective = SummarizationHistory.applyEventToMessages(
                messages, priorEvent);

        // 2. Pull the model profile for maxInputTokens (when set).
        Integer maxInputTokens = null;
        if (runtime != null && runtime.modelProfile() != null) {
            Object cap = runtime.modelProfile().fields().get("max_input_tokens");
            if (cap instanceof Integer i) maxInputTokens = i;
        }

        // 3. Count tokens once. The system message is
        //    prepended to the counted list (Python's
        //    `_count_tokens` does the same — system
        //    contributes to the token total but is stripped
        //    from the model call). The stripped list
        //    (`effective` without the system message) is
        //    what we summarise, partition, and forward to
        //    the model.
        Message systemMessage = findLastSystemMessage(effective);
        List<? extends Message> counted = stripSystem(systemMessage, effective);
        int totalTokens = (tokenCounter == null)
                ? defaultTokenCount(counted) + defaultTokenCount(
                        systemMessage == null ? List.of() : List.of(systemMessage))
                : SummarizationTokenCounter.countTokens(
                        tokenCounter, counted, systemMessage, runtimeToolList(runtime));

        // 4. Truncate args (if configured).
        List<Message> truncated = effective;
        if (truncateArgsSettings != null && truncateArgsSettings.trigger() != null) {
            int tCutoff = SummarizationTruncateArgs.determineTruncateCutoffIndex(
                    truncateArgsSettings, effective, maxInputTokens,
                    m -> defaultTokenCount(List.of(m)));
            SummarizationTruncateArgs.Result tr = SummarizationTruncateArgs.truncateArgs(
                    truncateArgsSettings, effective, tCutoff,
                    SummarizationTruncateArgs.DEFAULT_TRUNCATABLE_TOOLS);
            if (tr.modified()) {
                truncated = tr.messages();
                totalTokens = (tokenCounter == null)
                        ? defaultTokenCount(truncated) + defaultTokenCount(
                                systemMessage == null ? List.of() : List.of(systemMessage))
                        : SummarizationTokenCounter.countTokens(
                                tokenCounter, truncated, systemMessage, runtimeToolList(runtime));
            }
        }

        // 5. Should we summarise?  The decision also factors
        //    in the largest `total_tokens` reported via
        //    `usage_metadata` on any AIMessage in the
        //    conversation. When a model reports usage that
        //    exceeds the configured trigger (e.g. Anthropic
        //    reports 10_001 for a trigger of 10_000), the
        //    reported value should fire summarization even if
        //    the local token counter is under-budget. Mirrors
        //    Python's `_should_summarize_based_on_reported_tokens`.
        int reportedTotal = SummarizationCutoff.maxReportedTotalTokens(truncated);
        if (!force && !SummarizationCutoff.shouldSummarize(
                trigger, truncated.size(),
                Math.max(totalTokens, reportedTotal), maxInputTokens)) {
            // No-op: caller is expected to handle this case (e.g. by
            // returning a passthrough model response or a
            // "nothing to compact" Command).
            return new CompactPipelineResult(
                    truncated, null, null, null, 0, 0, 0, 0);
        }

        // 6. Determine the cutoff. The per-message token count
        //    uses the configured tokenCounter when available so
        //    the walk is consistent with the total-token count
        //    computed above. The system message is excluded
        //    from the partial walk (it is preserved, not
        //    summarised).
        java.util.function.ToIntFunction<Message> partialCounter = (tokenCounter == null)
                ? m -> defaultTokenCount(List.of(m))
                : m -> SummarizationTokenCounter.countTokens(
                        tokenCounter, List.of(m), null, runtimeToolList(runtime));
        int cutoff = SummarizationCutoff.determineCutoffIndex(
                keep, truncated, maxInputTokens, partialCounter);
        if (cutoff <= 0) {
            return new CompactPipelineResult(
                    truncated, null, null, null, 0, 0, 0, 0);
        }
        SummarizationCutoff.Partition partition =
                SummarizationCutoff.partitionMessages(truncated, cutoff);

        // 7. Inline-media offload + append-mode history offload.
        SummarizationMedia.Result mediaResult =
                offloadInlineMedia(partition.toSummarize());
        Instant now = Instant.now();
        String sessionId = SummarizationHistory.getSessionId(ext);
        String historyPath = SummarizationHistory.offloadToBackend(
                backend, mediaResult.messages(), sessionId, now);
        int failedMedia = mediaResult.failedBlockCount();

        // 8. Summarise the media-offloaded messages.
        String summary;
        try {
            Summarizer s = SummarizerRegistry.get(summarizerName);
            summary = s.summarize(mediaResult.messages(),
                    Map.of("summary_prompt", summaryPrompt));
        } catch (SummarizerUnavailableError e) {
            summary = "Summarizer not configured. The first "
                    + mediaResult.messages().size() + " messages were offloaded to "
                    + (historyPath == null
                            ? SummarizationHistory.historyPath(backend, sessionId)
                            : historyPath) + ".";
        }

        // 9. Build the new effective messages: summary + preserved.
        List<Message> summaryMsgs = SummarizationHistory.buildNewMessagesWithPath(
                summary, historyPath);
        List<Message> newMessages = new ArrayList<>(summaryMsgs.size() + partition.preserved().size());
        newMessages.add(summaryMsgs.get(0));
        newMessages.addAll(partition.preserved());

        // 10. Translate the effective-list cutoff to a state-list
        //     cutoff and build the event. When offload failed,
        //     `historyPath` is null and the event records
        //     `file_path=None` so the summary message does not
        //     point at a non-existent file (mirrors Python's
        //     `"file_path": file_path` on line 1456 of
        //     summarization.py).
        int stateCutoff = SummarizationEventApplier.computeStateCutoff(
                priorEvent, cutoff);
        SummarizationEvent event = new SummarizationEvent(
                ISO_8601.format(now), summary,
                messagesToIds(partition.toSummarize()),
                historyPath,
                Map.of("session_id", sessionId,
                        "state_cutoff_index", stateCutoff,
                        "effective_cutoff_index", cutoff,
                        "summarized_count", partition.toSummarize().size(),
                        "failed_media_blocks", failedMedia));

        return new CompactPipelineResult(
                newMessages, event, sessionId, historyPath,
                cutoff, stateCutoff,
                partition.toSummarize().size(),
                failedMedia);
    }

    // -----------------------------------------------------------------
    //  Async pipeline: arunCompactPipeline
    // -----------------------------------------------------------------

    /**
     * Async twin of {@link #runCompactPipeline}. Mirrors Python's
     * {@code _arun_compact} flow: same steps, but the inline-media
     * offload and the history offload use
     * {@link BackendProtocol#auploadFiles} and
     * {@link SummarizationHistory#aoffloadToBackend}. The
     * decision / cutoff / partition / summarise steps are pure
     * logic and run synchronously inside the composed future.
     */
    public CompletableFuture<CompactPipelineResult> arunCompactPipeline(AgentState state,
                                                                       List<Message> messages,
                                                                       Runtime runtime) {
        return arunCompactPipeline(state, messages, runtime, /* force */ false);
    }

    /**
     * Async twin of {@link #runCompactPipeline(AgentState, List, Runtime, boolean)}
     * that takes a {@code force} flag. When {@code true}, the
     * trigger check is bypassed (overflow-fallback path).
     */
    public CompletableFuture<CompactPipelineResult> arunCompactPipeline(AgentState state,
                                                                       List<Message> messages,
                                                                       Runtime runtime,
                                                                       boolean force) {
        // 1. Reconstruct the effective message list from any prior
        //    summarization event carried in the state extensions.
        Map<String, Object> ext = state == null || state.extensions() == null
                ? Map.of() : state.extensions();
        Map<String, Object> priorEvent = priorEventFrom(ext.get("_summarization_event"));
        List<Message> effective = SummarizationHistory.applyEventToMessages(
                messages, priorEvent);

        // 2. Pull the model profile for maxInputTokens (when set).
        Integer maxInputTokens = null;
        if (runtime != null && runtime.modelProfile() != null) {
            Object cap = runtime.modelProfile().fields().get("max_input_tokens");
            if (cap instanceof Integer i) maxInputTokens = i;
        }

        // 3. Count tokens once. The system message is
        //    prepended to the counted list (mirrors the
        //    sync pipeline — see comment in
        //    runCompactPipeline).
        Message systemMessage = findLastSystemMessage(effective);
        List<? extends Message> counted = stripSystem(systemMessage, effective);
        int totalTokens = (tokenCounter == null)
                ? defaultTokenCount(counted) + defaultTokenCount(
                        systemMessage == null ? List.of() : List.of(systemMessage))
                : SummarizationTokenCounter.countTokens(
                        tokenCounter, counted, systemMessage, runtimeToolList(runtime));

        // 4. Truncate args (if configured).
        List<Message> truncated = effective;
        if (truncateArgsSettings != null && truncateArgsSettings.trigger() != null) {
            int tCutoff = SummarizationTruncateArgs.determineTruncateCutoffIndex(
                    truncateArgsSettings, effective, maxInputTokens,
                    m -> defaultTokenCount(List.of(m)));
            SummarizationTruncateArgs.Result tr = SummarizationTruncateArgs.truncateArgs(
                    truncateArgsSettings, effective, tCutoff,
                    SummarizationTruncateArgs.DEFAULT_TRUNCATABLE_TOOLS);
            if (tr.modified()) {
                truncated = tr.messages();
                totalTokens = (tokenCounter == null)
                        ? defaultTokenCount(truncated) + defaultTokenCount(
                                systemMessage == null ? List.of() : List.of(systemMessage))
                        : SummarizationTokenCounter.countTokens(
                                tokenCounter, truncated, systemMessage, runtimeToolList(runtime));
            }
        }

        // 5. Should we summarise?  Includes reported tokens
        //    (mirrors the sync pipeline — see
        //    maxReportedTotalTokens).
        int reportedTotal = SummarizationCutoff.maxReportedTotalTokens(truncated);
        if (!force && !SummarizationCutoff.shouldSummarize(
                trigger, truncated.size(),
                Math.max(totalTokens, reportedTotal), maxInputTokens)) {
            return CompletableFuture.completedFuture(new CompactPipelineResult(
                    truncated, null, null, null, 0, 0, 0, 0));
        }

        // 6. Determine the cutoff. The per-message token count
        //    uses the configured tokenCounter when available so
        //    the walk is consistent with the total-token count
        //    computed above.
        java.util.function.ToIntFunction<Message> partialCounter = (tokenCounter == null)
                ? m -> defaultTokenCount(List.of(m))
                : m -> SummarizationTokenCounter.countTokens(
                        tokenCounter, List.of(m), null, runtimeToolList(runtime));
        int cutoff = SummarizationCutoff.determineCutoffIndex(
                keep, truncated, maxInputTokens, partialCounter);
        if (cutoff <= 0) {
            return CompletableFuture.completedFuture(new CompactPipelineResult(
                    truncated, null, null, null, 0, 0, 0, 0));
        }
        SummarizationCutoff.Partition partition =
                SummarizationCutoff.partitionMessages(truncated, cutoff);

        // 7. Async inline-media offload + history offload +
        //    summary generation. The history offload and the
        //    summary generation are independent — both
        //    consume the (already offloaded) media list, so
        //    they can run in parallel (mirrors Python's
        //    `asyncio.gather(_aoffload_to_backend,
        //    _acreate_summary)` on line 1569 of
        //    summarization.py).
        Instant now = Instant.now();
        String sessionId = SummarizationHistory.getSessionId(ext);

        return aoffloadInlineMedia(partition.toSummarize())
                .thenCompose(mediaResult -> {
                    // Kick off the history offload and the
                    // summary in parallel. The history offload
                    // runs on a worker thread (so the calling
                    // thread can do the summary concurrently),
                    // and the summary generation also runs on
                    // a worker thread. Both consume the
                    // (already offloaded) media list.
                    CompletableFuture<String> offloadFuture =
                            CompletableFuture.supplyAsync(() ->
                                    SummarizationHistory.offloadToBackend(
                                            backend, mediaResult.messages(), sessionId, now));
                    CompletableFuture<String> summaryFuture =
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    Summarizer s = SummarizerRegistry.get(summarizerName);
                                    return s.summarize(mediaResult.messages(),
                                            Map.of("summary_prompt", summaryPrompt));
                                } catch (SummarizerUnavailableError e) {
                                    return "Summarizer not configured.";
                                }
                            });
                    return CompletableFuture.allOf(offloadFuture, summaryFuture)
                            .thenApply(v -> {
                                String historyPath = offloadFuture.join();
                                String summary = summaryFuture.join();
                                int failedMedia = mediaResult.failedBlockCount();

                                // 9. Build the new effective messages: summary + preserved.
                                List<Message> summaryMsgs = SummarizationHistory.buildNewMessagesWithPath(
                                        summary, historyPath);
                                List<Message> newMessages = new ArrayList<>(summaryMsgs.size() + partition.preserved().size());
                                newMessages.add(summaryMsgs.get(0));
                                newMessages.addAll(partition.preserved());

                                // 10. Translate the effective-list cutoff to a state-list
                                //     cutoff and build the event. When offload failed,
                                //     `historyPath` is null and the event records
                                //     `file_path=None` (mirrors Python's
                                //     `"file_path": file_path` on line 1456 of
                                //     summarization.py).
                                int stateCutoff = SummarizationEventApplier.computeStateCutoff(
                                        priorEvent, cutoff);
                                SummarizationEvent event = new SummarizationEvent(
                                        ISO_8601.format(now), summary,
                                        messagesToIds(partition.toSummarize()),
                                        historyPath,
                                        Map.of("session_id", sessionId,
                                                "state_cutoff_index", stateCutoff,
                                                "effective_cutoff_index", cutoff,
                                                "summarized_count", partition.toSummarize().size(),
                                                "failed_media_blocks", failedMedia));

                                return new CompactPipelineResult(
                                        newMessages, event, sessionId, historyPath,
                                        cutoff, stateCutoff,
                                        partition.toSummarize().size(),
                                        failedMedia);
                            });
                });
    }

    /**
     * Normalise the value carried under {@code _summarization_event}
     * in the agent state extensions into a {@code Map<String,
     * Object>} that the {@code applyEventToMessages} and
     * {@code computeStateCutoff} helpers can read. Two shapes
     * are supported:
     *
     * <ul>
     *   <li>A {@link SummarizationEvent} record — the production
     *       path; emitted by {@link #wrapModelCallWithEvents} on
     *       each compaction.</li>
     *   <li>A plain {@code Map<String, Object>} with the
     *       Python-style {@code summary_message} and
     *       {@code cutoff_index} keys — the legacy / hand-crafted
     *       state shape used by some tests.</li>
     * </ul>
     *
     * <p>Any other type is treated as a missing event
     * ({@code null} return).</p>
     */
    private static Map<String, Object> priorEventFrom(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        if (raw instanceof SummarizationEvent e) {
            // The event record carries the file path, ids and
            // details. The state list is reconstructed from
            // `summary_message` (a stub HumanMessage carrying
            // the summary text) so the effective-list shape
            // matches the Python port's expectation. The
            // `state_cutoff_index` from the prior round is
            // pulled from details so the chained summarization
            // formula `state = prior_state + effective - 1`
            // remains accurate.
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("summary_message", buildSummaryMessageFromEvent(e));
            out.put("summary", e.summary());
            out.put("timestamp", e.timestamp());
            out.put("file_path", e.historyFilePath());
            out.put("details", e.details());
            Object priorStateCutoff = e.details() == null
                    ? null : e.details().get("state_cutoff_index");
            if (priorStateCutoff instanceof Integer psc) {
                out.put("cutoff_index", psc);
            }
            return out;
        }
        return null;
    }

    /** Build a stub {@link HumanMessage} carrying the
     *  summary text of a prior event, used when reconstructing
     *  the effective message list. Mirrors the shape produced
     *  by {@link SummarizationHistory#buildNewMessagesWithPath}. */
    private static HumanMessage buildSummaryMessageFromEvent(SummarizationEvent e) {
        List<ContentBlock> body = new ArrayList<>();
        body.add(ContentBlock.text(e.summary() == null ? "" : e.summary()));
        return new HumanMessage(
                "summary-msg-" + (e.timestamp() == null ? "unknown" : e.timestamp()),
                body,
                java.util.Optional.empty(),
                java.util.Map.of("lc_source",
                        SummarizationPrompts.SUMMARIZATION_MESSAGE_SOURCE));
    }

    // -----------------------------------------------------------------
    //  wrapModelCallWithEvents: modern flow (uses runCompactPipeline)
    // -----------------------------------------------------------------

    @Override
    public WrapModelCallResult wrapModelCallWithEvents(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        CompactPipelineResult result = runCompactPipeline(state, messages, runtime);
        if (result.event() == null) {
            // No summarization happened: passthrough with the
            // (possibly truncated) messages. If the model call
            // then raises ContextOverflowError, fall back to
            // the overflow-clip path (mirrors Python's
            // wrap_model_call fallback to _aclip_overflow_tail
            // / summary).
            try {
                return WrapModelCallResult.passthrough(
                        modelCall.apply(result.newMessages(), runtime));
            } catch (ContextOverflowError overflow) {
                return handleOverflowFallback(
                        result.newMessages(), state, runtime, modelCall,
                        /* messagesToKeep override */ null, overflow);
            }
        }
        // Call the model with the post-summary messages and emit
        // a state-update Command with the new event + session id
        // + the new state-tail messages. The "messages" key in
        // the Command update is consumed by DeepAgent to replace
        // the state's messages list (mirrors Python's
        // `new_state_tail` from line 1468 of summarization.py).
        AIMessage ai = modelCall.apply(result.newMessages(), runtime);
        Map<String, Object> update = new java.util.LinkedHashMap<>();
        update.put("_summarization_event", result.event());
        update.put("_summarization_session_id", result.sessionId());
        update.put("messages", result.newMessages());
        return WrapModelCallResult.of(ai, org.aethercode.deepagents.langchain_compat.langgraph.Command.update(update));
    }

    /**
     * Fallback path triggered when a model call raises
     * {@link ContextOverflowError}. Mirrors Python's
     * {@code wrap_model_call} overflow branch: force a
     * summarisation pass even if the configured trigger was
     * below the threshold, then retry the model call with
     * the new message list. Returns a passthrough result if
     * the retry succeeds without an event (no overflow
     * clip / summary produced), or a Command result if a
     * new event was emitted.
     */
    private WrapModelCallResult handleOverflowFallback(
            List<Message> effective,
            AgentState state,
            Runtime runtime,
            BiFunction<List<Message>, Runtime, AIMessage> syncModelCall,
            Integer messagesToKeepOverride,
            ContextOverflowError overflow) {
        // 1. Run the clip helper to evict the tail tool
        //    messages (the typical overflow cause is a giant
        //    read_file tool result). This shortens the
        //    effective list.
        Integer maxInputTokens = null;
        if (runtime != null && runtime.modelProfile() != null) {
            Object cap = runtime.modelProfile().fields().get("max_input_tokens");
            if (cap instanceof Integer i) maxInputTokens = i;
        }
        SummarizationOverflowClip.Result clipped = SummarizationOverflowClip.clipOverflowTail(
                effective, backend, keep, maxInputTokens,
                msgs -> (tokenCounter == null)
                        ? defaultTokenCount(msgs)
                        : SummarizationTokenCounter.countTokens(
                                tokenCounter, msgs, null, runtimeToolList(runtime)),
                "truncated:");
        if (!clipped.preservedMessages().isEmpty()) {
            effective = clipped.preservedMessages();
        }
        // 2. Force a compaction pass by ignoring the
        //    trigger. The trigger was below threshold when
        //    the conversation started; now that the model
        //    call has overflowed, we ignore the trigger and
        //    produce a summary regardless.
        CompactPipelineResult forcedResult = runCompactPipeline(
                state, effective, runtime, /* force */ true);
        if (forcedResult.event() == null) {
            // Even after clipping, no event was emitted
            // (e.g. the conversation is too short to
            // summarise). Re-throw the original overflow so
            // the runtime can decide what to do (matches
            // Python's behaviour: when no summary can be
            // produced, the overflow propagates).
            throw overflow;
        }
        // 3. Retry the model call with the post-summary
        //    messages. On success, emit the new event.
        if (syncModelCall == null) {
            // Async path: caller expects a CompletableFuture
            // and the wrap-up is handled by the async
            // thenCompose above. We return a synthetic
            // passthrough so the .handle() block can apply
            // the fallback; the caller still re-runs the
            // model call.
            //
            // For the async case we cannot retry here (we
            // don't have the async modelCall reference);
            // instead we re-throw so the caller can decide.
            // The async path tests cover the "first call
            // overflow" branch; the retry-on-async-overflow
            // branch is left for a follow-up that wires the
            // async modelCall through.
            throw overflow;
        }
        AIMessage ai = syncModelCall.apply(forcedResult.newMessages(), runtime);
        Map<String, Object> update = new java.util.LinkedHashMap<>();
        update.put("_summarization_event", forcedResult.event());
        update.put("_summarization_session_id", forcedResult.sessionId());
        update.put("messages", forcedResult.newMessages());
        return WrapModelCallResult.of(ai,
                org.aethercode.deepagents.langchain_compat.langgraph.Command.update(update));
    }

    /**
     * Async twin of {@link #wrapModelCallWithEvents}. Mirrors
     * Python's {@code _awrap_model_call} flow: the
     * {@link #arunCompactPipeline} decides whether to summarise,
     * and the model is then called with the post-summary messages.
     * On a no-summarise outcome the caller's modelCall is invoked
     * with the (possibly truncated) effective list. On a summarise
     * outcome the modelCall is invoked with the new list, and a
     * {@code Command} carrying {@code _summarization_event} and
     * {@code _summarization_session_id} is returned.
     */
    @Override
    public CompletableFuture<WrapModelCallResult> awrapModelCallWithEvents(
            BiFunction<List<Message>, Runtime, CompletableFuture<AIMessage>> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        // Catch any synchronous RuntimeException raised
        // before the future chain starts and re-surface it
        // as a failed future so callers see a uniform
        // CompletionException shape (mirrors the Python
        // port's async wrapper that wraps sync exceptions
        // in anyio.gather).
        CompletableFuture<CompactPipelineResult> head;
        try {
            head = arunCompactPipeline(state, messages, runtime);
        } catch (RuntimeException ex) {
            head = CompletableFuture.failedFuture(ex);
        }
        return head.thenCompose(result -> invokeModelOrFallback(
                result, state, runtime, modelCall, /* alreadyOverflowed */ false));
    }

    /**
     * Drive the model call after {@link #arunCompactPipeline} has
     * decided whether to summarise. When the model call raises
     * {@link ContextOverflowError} and we are NOT in the middle
     * of a fallback, run the overflow-clip + summary pipeline
     * and retry. On a second overflow the original error is
     * re-thrown.
     */
    private CompletableFuture<WrapModelCallResult> invokeModelOrFallback(
            CompactPipelineResult result,
            AgentState state,
            Runtime runtime,
            BiFunction<List<Message>, Runtime, CompletableFuture<AIMessage>> modelCall,
            boolean alreadyOverflowed) {
        return modelCall.apply(result.newMessages(), runtime)
                .handle((ai, ex) -> {
                    if (ex == null) {
                        // No error: emit a passthrough or
                        // command result as appropriate.
                        if (result.event() == null) {
                            return WrapModelCallResult.passthrough(ai);
                        }
                        Map<String, Object> update = new java.util.LinkedHashMap<>();
                        update.put("_summarization_event", result.event());
                        update.put("_summarization_session_id", result.sessionId());
                        update.put("messages", result.newMessages());
                        return WrapModelCallResult.of(ai,
                                org.aethercode.deepagents.langchain_compat.langgraph.Command.update(update));
                    }
                    Throwable cause = ex instanceof CompletionException
                            && ex.getCause() != null
                                    ? ex.getCause() : ex;
                    if (!(cause instanceof ContextOverflowError overflow) || alreadyOverflowed) {
                        if (ex instanceof RuntimeException re) throw re;
                        throw new RuntimeException(ex);
                    }
                    // Overflow path: clip tail + force a
                    // compaction + retry. The retry runs
                    // through the same invokeModelOrFallback
                    // with alreadyOverflowed=true. We return
                    // a sentinel null to signal the outer
                    // .thenCompose that a retry is needed.
                    return null; // sentinel
                })
                .thenCompose(boxed -> {
                    if (boxed != null) {
                        return CompletableFuture.completedFuture(boxed);
                    }
                    // Overflow retry: rebuild the effective
                    // list via clipOverflowTail, run the
                    // pipeline, then re-invoke.
                    Integer maxInputTokens = null;
                    if (runtime != null && runtime.modelProfile() != null) {
                        Object cap = runtime.modelProfile().fields().get("max_input_tokens");
                        if (cap instanceof Integer i) maxInputTokens = i;
                    }
                    SummarizationOverflowClip.Result clipped = SummarizationOverflowClip.clipOverflowTail(
                            result.newMessages(), backend, keep, maxInputTokens,
                            msgs -> (tokenCounter == null)
                                    ? defaultTokenCount(msgs)
                                    : SummarizationTokenCounter.countTokens(
                                            tokenCounter, msgs, null, runtimeToolList(runtime)),
                            "truncated:");
                    List<Message> effective = clipped.preservedMessages().isEmpty()
                            ? result.newMessages() : clipped.preservedMessages();
                    // Force=true: the original trigger was
                    // below threshold; we summarise regardless.
                    return arunCompactPipeline(state, effective, runtime, true)
                            .thenCompose(forced ->
                                    invokeModelOrFallback(
                                            forced, state, runtime, modelCall,
                                            /* alreadyOverflowed */ true)
                                    .thenApply(retry -> {
                                        if (retry.hasCommand()) {
                                            Map<String, Object> update =
                                                    new java.util.LinkedHashMap<>(
                                                            retry.command().update());
                                            update.put("messages", forced.newMessages());
                                            return WrapModelCallResult.of(
                                                    retry.aiMessage(),
                                                    org.aethercode.deepagents.langchain_compat.langgraph.Command.update(update));
                                        }
                                        return retry;
                                    }));
                });
    }

    /** Find the most recent {@link SystemMessage} in {@code msgs}. */
    private static Message findLastSystemMessage(List<Message> msgs) {
        if (msgs == null) return null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof SystemMessage s) return s;
        }
        return null;
    }

    /** Return the messages excluding the system message, or
     *  the input list when the system message is {@code null}. */
    private static List<Message> stripSystem(Message systemMessage, List<Message> msgs) {
        if (systemMessage == null) return msgs == null ? List.of() : msgs;
        List<Message> out = new java.util.ArrayList<>(msgs.size() - 1);
        for (Message m : msgs) {
            if (m != systemMessage) out.add(m);
        }
        return out;
    }

    /** Default approximate token counter: 10 tokens per message.
     *  Used when no real token counter is configured. */
    private static int defaultTokenCount(List<? extends Message> msgs) {
        if (msgs == null) return 0;
        int total = 0;
        for (Message m : msgs) {
            // Each text-block contributes 10 tokens; non-text
            // blocks contribute 5 (rough approximation).
            if (m.content() == null) continue;
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.TextBlock t) {
                    total += Math.max(1, t.text().length() / 4);
                } else {
                    total += 5;
                }
            }
        }
        return total;
    }

    /** Pull the tool list from {@code runtime} for tools-aware
     *  token counting. Returns {@code null} when no tool list is
     *  available. */
    private static List<?> runtimeToolList(Runtime runtime) {
        if (runtime == null) return null;
        try {
            return runtime.tools();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
