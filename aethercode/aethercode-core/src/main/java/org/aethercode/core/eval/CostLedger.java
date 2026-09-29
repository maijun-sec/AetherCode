package org.aethercode.core.eval;

import org.aethercode.core.cost.CostTracker;

/**
 * Thin wrapper around {@link CostTracker} that exposes the eval-friendly
 * fields by name. The eval module wanted a {@code totalUsd()} accessor
 * but {@link CostTracker} returns a {@code Summary} record instead.
 *
 * <p>This class is intentionally tiny -- just a 1-line getter each.
 * It exists so {@link AgentRunner} and {@link ScoreAggregator} can read
 * cost figures without importing the {@code Summary} record type
 * into their signatures.
 *
 * <p>Thread-safe iff the underlying {@link CostTracker} is thread-safe
 * (it is -- its {@code record} / {@code summary} methods are synchronized).
 */
public final class CostLedger {

    private final CostTracker tracker;

    public CostLedger(CostTracker tracker) {
        if (tracker == null) {
            throw new IllegalArgumentException("CostTracker is required");
        }
        this.tracker = tracker;
    }

    /** Total USD spent across all models since reset / construction. */
    public double totalUsd() {
        return tracker.summary().totalCostUsd();
    }

    /** Total input tokens across all models. */
    public long totalInputTokens() {
        return tracker.summary().totalInput();
    }

    /** Total output tokens across all models. */
    public long totalOutputTokens() {
        return tracker.summary().totalOutput();
    }

    /** Underlying tracker (for tests / advanced callers). */
    public CostTracker tracker() {
        return tracker;
    }
}