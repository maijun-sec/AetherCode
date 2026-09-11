package org.aethercode.tasks.engine.summary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * T-1-23: default post-turn summary hook backed by a small LLM
 * call. The hook:
 * <ol>
 *   <li>Inspects the last assistant message for a
 *       {@code ## Summary} block. If present, no work is done.</li>
 *   <li>Otherwise builds a 1-3-line-summary request and invokes
 *       the {@link LlmCaller}. On success, returns
 *       {@link PostTurnSummaryHook.SummaryResult#injected} with
 *       the produced text.</li>
 *   <li>On failure, returns
 *       {@link PostTurnSummaryHook.SummaryResult#fallback} with
 *       the reason — the caller appends a human-readable
 *       "auto-summary failed" line.</li>
 * </ol>
 *
 * <p>Async: the LLM call is dispatched on the supplied
 * {@link Executor} (or the common pool if not given). The
 * returned future never blocks the supervisor's main turn loop.
 */
public final class LlmBackedSummaryHook implements PostTurnSummaryHook {

    private static final Logger LOG = LoggerFactory.getLogger(LlmBackedSummaryHook.class);

    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are a terse summarizer. Given the recent transcript, "
                    + "write a 1-3 line summary naming what was done and "
                    + "what is intended next. End with the literal line "
                    + "'## Summary' followed by your summary. No preamble. "
                    + "No postamble. Plain text.";

    public static final String DEFAULT_USER_PROMPT_PREFIX =
            "Recent messages (most recent last). Write a 1-3 line summary "
                    + "of what was done and what's intended next. End with "
                    + "'## Summary' on its own line followed by the summary.";

    private final LlmCaller llm;
    private final Executor executor;
    private final String systemPrompt;
    private final int maxContextMessages;
    private final int maxCharsPerMessage;

    public LlmBackedSummaryHook(LlmCaller llm) {
        this(llm, null, DEFAULT_SYSTEM_PROMPT, 5, 1000);
    }

    public LlmBackedSummaryHook(LlmCaller llm, Executor executor) {
        this(llm, executor, DEFAULT_SYSTEM_PROMPT, 5, 1000);
    }

    public LlmBackedSummaryHook(LlmCaller llm, Executor executor,
                                String systemPrompt,
                                int maxContextMessages,
                                int maxCharsPerMessage) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.executor = executor;
        this.systemPrompt = systemPrompt == null ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
        if (maxContextMessages < 1) throw new IllegalArgumentException("maxContextMessages < 1");
        if (maxCharsPerMessage < 50) throw new IllegalArgumentException("maxCharsPerMessage < 50");
        this.maxContextMessages = maxContextMessages;
        this.maxCharsPerMessage = maxCharsPerMessage;
    }

    @Override
    public CompletableFuture<SummaryResult> onAssistantTurnEnd(TurnContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (SummaryExtractor.hasSummaryBlock(ctx.lastAssistantMessage())) {
            return CompletableFuture.completedFuture(SummaryResult.alreadyPresent());
        }
        CompletableFuture<SummaryResult> fut = new CompletableFuture<>();
        Runnable work = () -> {
            try {
                String userPrompt = buildUserPrompt(ctx);
                String raw = llm.complete(systemPrompt, userPrompt);
                if (raw == null || raw.isBlank()) {
                    fut.complete(SummaryResult.fallback("LLM returned empty response"));
                    return;
                }
                String body = stripHeading(raw);
                fut.complete(SummaryResult.injected(body));
            } catch (RuntimeException e) {
                LOG.warn("auto-summary LLM call failed: {}", e.getMessage());
                fut.complete(SummaryResult.fallback(e.getMessage() == null ? "exception" : e.getMessage()));
            } catch (Throwable t) {
                LOG.error("auto-summary LLM call error", t);
                fut.complete(SummaryResult.fallback(t.getMessage() == null ? "error" : t.getMessage()));
            }
        };
        if (executor != null) executor.execute(work);
        else CompletableFuture.runAsync(work);
        return fut;
    }

    /** Build the user prompt from the last N messages, truncated
     *  to {@code maxCharsPerMessage} per message. */
    String buildUserPrompt(TurnContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(DEFAULT_USER_PROMPT_PREFIX).append("\n\n");
        List<Map<String, Object>> msgs = ctx.recentMessages();
        int from = Math.max(0, msgs.size() - maxContextMessages);
        for (int i = from; i < msgs.size(); i++) {
            Map<String, Object> m = msgs.get(i);
            Object role = m.getOrDefault("role", "user");
            Object content = m.getOrDefault("content", "");
            String s = String.valueOf(content);
            if (s.length() > maxCharsPerMessage) {
                s = s.substring(0, maxCharsPerMessage) + "...(truncated)";
            }
            sb.append('[').append(role).append("] ").append(s).append('\n');
        }
        sb.append("\nReturn: ## Summary\\n<your 1-3 line summary>\\n");
        return sb.toString();
    }

    private static String stripHeading(String raw) {
        if (raw == null) return "";
        // strip the optional "## Summary" prefix and any leading
        // blank lines; everything else is the body.
        String trimmed = raw.strip();
        int idx = trimmed.toLowerCase().indexOf("summary");
        if (idx < 0) return trimmed;
        int newline = trimmed.indexOf('\n', idx);
        if (newline < 0) return "";
        return trimmed.substring(newline + 1).strip();
    }

    public LlmCaller llm() { return llm; }
    public int maxContextMessages() { return maxContextMessages; }
    public int maxCharsPerMessage() { return maxCharsPerMessage; }
}
