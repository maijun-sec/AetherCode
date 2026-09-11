package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session statistics (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._session_stats}
 * module. The Java port exposes the small set of counters the TUI's
 * status bar reads; the full implementation lands with the agent
 * graph port.</p>
 */
public final class SessionStats {
    private SessionStats() {}

    /** A snapshot of session statistics. */
    public record Stats(
            long totalTokens,
            long totalCostUsd,
            int turns,
            long startedAtMs) {
    }

    private static final AtomicReference<Stats> CURRENT = new AtomicReference<>(
            new Stats(0, 0, 0, System.currentTimeMillis()));

    /** Read the current stats snapshot. */
    public static Stats current() {
        return CURRENT.get();
    }

    /** Reset the session stats. */
    public static void reset() {
        CURRENT.set(new Stats(0, 0, 0, System.currentTimeMillis()));
    }

    /** Record a turn. */
    public static void recordTurn(long tokens, double costUsd) {
        CURRENT.updateAndGet(prev -> new Stats(
                prev.totalTokens() + tokens,
                prev.totalCostUsd() + (long) (costUsd * 1000),
                prev.turns() + 1,
                prev.startedAtMs()));
    }
}
