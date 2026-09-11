package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auto-mode state and counters.
 *
 * <p>Java-native port of the Python {@code deepagents_code.auto_mode}
 * module. The Java port carries the public surface used by the
 * approval-mode HITL middleware and the classifier pipeline; the full
 * classifier implementation lands with the agent graph port.</p>
 */
public final class AutoMode {
    private AutoMode() {}

    private static final Logger LOG = LoggerFactory.getLogger(AutoMode.class);

    /** Per-thread Auto-mode counters. */
    public record AutoModeCounters(
            int totalDecisions,
            int approved,
            int denied,
            int errors) {
    }

    /** Per-call Auto decision. */
    public record AutoDecision(
            String toolCallId,
            ApprovalMode.Mode mode,
            String verdict,
            String reason) {
    }

    private static final Map<String, AutoModeCounters> COUNTERS = new LinkedHashMap<>();

    /** Read the counters for a thread. */
    public static AutoModeCounters counters(String threadId) {
        return COUNTERS.getOrDefault(threadId, new AutoModeCounters(0, 0, 0, 0));
    }

    /** Increment the counters for a thread. */
    public static void record(String threadId, boolean approved, boolean errored) {
        AutoModeCounters prev = counters(threadId);
        COUNTERS.put(threadId, new AutoModeCounters(
                prev.totalDecisions() + 1,
                prev.approved() + (approved ? 1 : 0),
                prev.denied() + (!approved && !errored ? 1 : 0),
                prev.errors() + (errored ? 1 : 0)));
    }

    /** Reset the counters for a thread. */
    public static void reset(String threadId) {
        COUNTERS.remove(threadId);
    }
}
