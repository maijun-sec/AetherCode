package org.aethercode.tasks.engine.summary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * T-1-23: per-turn auto-summary hook. Called by
 * {@code SupervisorService.onAssistantTurnEnd} after every
 * assistant turn. The hook decides whether a post-turn
 * LLM call is needed (only when the last message lacks a
 * {@code ## Summary} block) and, if so, queues it.
 *
 * <p>Three implementations:
 * <ul>
 *   <li>{@link LlmBackedSummaryHook} — makes a small LLM call
 *       via an injected {@link LlmCaller}.</li>
 *   <li>{@link SummaryFallback} — no LLM, just appends a
 *       human-readable fallback line.</li>
 *   <li>no-op ({@link #NOOP}) — for tests / when the supervisor
 *       is configured to skip auto-summary.</li>
 * </ul>
 *
 * <p>The hook is invoked once per assistant turn; the
 * implementation is responsible for keeping the call
 * non-blocking (it returns a {@link CompletableFuture}).
 */
public interface PostTurnSummaryHook {

    /** No-op hook (skips all auto-summary work). */
    PostTurnSummaryHook NOOP = new PostTurnSummaryHook() {
        @Override public CompletableFuture<SummaryResult> onAssistantTurnEnd(TurnContext ctx) {
            return CompletableFuture.completedFuture(SummaryResult.skipped("noop-hook"));
        }
    };

    /**
     * Process the end of an assistant turn.
     *
     * <p>Implementations should:
     * <ol>
     *   <li>Check the last assistant message for a {@code ## Summary}
     *       block. If present, return {@link SummaryResult#alreadyPresent}.</li>
     *   <li>Otherwise, queue a small LLM call asking the LLM to
     *       write a 1-3 line summary of the last 5 messages. On
     *       success, return {@link SummaryResult#injected} with
     *       the produced text.</li>
     *   <li>On LLM failure, return {@link SummaryResult#fallback} so
     *       the caller can append a "summary failed" line.</li>
     * </ol>
     */
    CompletableFuture<SummaryResult> onAssistantTurnEnd(TurnContext ctx);

    /** Outcome of {@link #onAssistantTurnEnd}. */
    enum Kind {
        /** Last message already had a {@code ## Summary} block. No work. */
        ALREADY_PRESENT,
        /** LLM call succeeded; the produced text is in {@code text}. */
        INJECTED,
        /** LLM call failed; the caller should append a fallback line. */
        FALLBACK,
        /** Hook was a no-op (e.g. NOOP / disabled). */
        SKIPPED
    }

    record SummaryResult(Kind kind, String text, String reason) {
        public static SummaryResult alreadyPresent() {
            return new SummaryResult(Kind.ALREADY_PRESENT, null, "summary block present in last message");
        }
        public static SummaryResult injected(String text) {
            return new SummaryResult(Kind.INJECTED, text, null);
        }
        public static SummaryResult fallback(String reason) {
            return new SummaryResult(Kind.FALLBACK, null, reason);
        }
        public static SummaryResult skipped(String reason) {
            return new SummaryResult(Kind.SKIPPED, null, reason);
        }
    }

    /**
     * Per-call context. Carries just enough info for the hook
     * to decide (last-message text) and to drive the LLM call
     * (recent messages, system prompt).
     */
    record TurnContext(
            String sessionId,
            String lastAssistantMessage,
            List<Map<String, Object>> recentMessages,
            Map<String, Object> metadata) {

        public TurnContext {
            recentMessages = List.copyOf(recentMessages);
            metadata = Map.copyOf(metadata);
        }
    }
}
