package org.aethercode.tasks.engine.summary;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * T-1-24: fallback when the LLM-backed hook fails. Returns
 * {@link PostTurnSummaryHook.SummaryResult#fallback} so the
 * caller can append a human-readable "auto-summary failed"
 * line to the message stream. The line is rendered by
 * {@link SummaryExtractor#renderFailureFooter(String)}; the
 * spec wording is
 * <em>Summary: (LLM auto-summary failed — see last assistant message)</em>.
 *
 * <p>The fallback is cheap and synchronous — it never makes an
 * LLM call and never throws. Wire it in as the
 * {@code exceptionally} callback for the
 * {@link LlmBackedSummaryHook} future (see
 * {@code SupervisorService.onAssistantTurnEnd}).
 */
public final class SummaryFallback implements PostTurnSummaryHook {

    private final Executor executor;
    private final String reason;

    public SummaryFallback() { this(null, null); }
    public SummaryFallback(String reason) { this(null, reason); }
    public SummaryFallback(Executor executor, String reason) {
        this.executor = executor;
        this.reason = reason;
    }

    @Override
    public CompletableFuture<SummaryResult> onAssistantTurnEnd(TurnContext ctx) {
        return CompletableFuture.completedFuture(
                SummaryResult.fallback(reason == null ? "fallback hook" : reason));
    }

    /**
     * The literal fallback footer line. Kept here so the
     * supervisor and the desktop / TUI agree on the wording.
     */
    public static String fallbackLine() {
        return SummaryExtractor.renderFailureFooter(null);
    }

    public static String fallbackLine(String reason) {
        return SummaryExtractor.renderFailureFooter(reason);
    }
}
