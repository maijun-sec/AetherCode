package org.aethercode.hooks.builtin;

import org.aethercode.hooks.Hook;

import java.util.concurrent.CompletableFuture;

/**
 * PRE_TOOL_USE hook that blocks tool calls when the
 * current phase's budget (per {@link PhaseTracker}) is
 * exhausted.
 *
 * <p>Block verdict format:
 * <pre>
 *   phase budget exhausted: phase=implement toolCalls=50/50 costUsd=$0.18/$0.20
 *   -> transition to "verify" or raise the cap (try: /phase verify or /budget implement 80)
 * </pre>
 *
 * <p>The block message is intentionally actionable: it tells
 * the user which phase is over budget, what the spent / cap
 * numbers are, and how to recover (either transition to the
 * next phase or raise the cap). Without the actionable
 * message the model would just see "blocked" and retry
 * indefinitely.
 *
 * <p>This hook subscribes ONLY to {@code PRE_TOOL_USE}. It
 * does not consume {@code POST_TOOL_USE} because the
 * per-phase cost is recorded in {@link PhaseTracker#recordToolCall}
 * (which the engine or the {@code recordCost} hook calls).
 */
public final class PhaseBudgetHook implements Hook {

    private final PhaseTracker tracker;

    public PhaseBudgetHook(PhaseTracker tracker) {
        if (tracker == null) throw new IllegalArgumentException("tracker must not be null");
        this.tracker = tracker;
    }

    @Override
    public Kind kind() {
        return Kind.PRE_TOOL_USE;
    }

    @Override
    public CompletableFuture<Outcome> run(HookContext ctx) {
        if (tracker.isOverBudget()) {
            PhaseTracker.Snapshot s = tracker.snapshot();
            PhaseTracker.Bucket b = s.buckets().get(s.currentPhase());
            int calls = b == null ? 0 : b.toolCalls;
            int cap   = b == null ? 0 : b.maxToolCalls;
            double cost = b == null ? 0.0 : b.costUsd;
            double maxCost = b == null ? 0.0 : b.maxCostUsd;
            StringBuilder msg = new StringBuilder();
            msg.append("phase budget exhausted: phase=").append(s.currentPhase());
            msg.append(" toolCalls=").append(calls).append("/").append(cap);
            msg.append(String.format(" costUsd=$%.2f/$%.2f", cost, maxCost));
            msg.append(" -> transition to next phase or raise the cap (try: /phase verify or /budget ")
               .append(s.currentPhase()).append(" 80)");
            return CompletableFuture.completedFuture(new Outcome.Block(msg.toString()));
        }
        return CompletableFuture.completedFuture(new Outcome.Continue());
    }
}
