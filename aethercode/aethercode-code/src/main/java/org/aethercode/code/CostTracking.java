package org.aethercode.code;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Cost-tracking surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.cost_tracking}
 * module. The Java port exposes a small in-memory accumulator for the
 * current turn; the full port also writes per-thread aggregates to the
 * conversation-history backend.</p>
 */
public final class CostTracking {
    private CostTracking() {}

    /** A per-turn cost aggregate. */
    public record CostAggregate(
            double inputUsd,
            double outputUsd,
            double totalUsd,
            int inputTokens,
            int outputTokens) {
    }

    private static final AtomicReference<CostAggregate> CURRENT = new AtomicReference<>(new CostAggregate(0, 0, 0, 0, 0));

    /** Reset the per-turn aggregate. */
    public static void reset() {
        CURRENT.set(new CostAggregate(0, 0, 0, 0, 0));
    }

    /** Add a usage delta. */
    public static void addUsage(int inputTokens, int outputTokens, double inputUsd, double outputUsd) {
        CURRENT.updateAndGet(prev -> new CostAggregate(
                prev.inputUsd() + inputUsd,
                prev.outputUsd() + outputUsd,
                prev.totalUsd() + inputUsd + outputUsd,
                prev.inputTokens() + inputTokens,
                prev.outputTokens() + outputTokens));
    }

    /** Read the current per-turn aggregate. */
    public static CostAggregate current() {
        return CURRENT.get();
    }
}
