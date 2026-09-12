package org.aethercode.orchestration.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * BlockA2A-style Byzantine agent detector.
 * <p>
 * Paper: 2508.01332 BlockA2A. The detector monitors agent behaviour and flags
 * deviant / rogue agents. Once flagged, the agent is halted and its permission
 * is revoked.
 * <p>
 * The detector does NOT need an LLM; it uses deterministic heuristics:
 * <ul>
 *   <li>Repeated identical outputs (echo attack)</li>
 *   <li>Output length bounds (overflow / truncation)</li>
 *   <li>Excessive error rate (failure flooding)</li>
 *   <li>Repetition ratio within output (loop detection)</li>
 * </ul>
 */
public final class ByzantineDetector {

    /** Severity of a detected violation. */
    public enum Severity { LOW, MEDIUM, HIGH }

    /** A detected violation. */
    public record Violation(String rule, Severity severity, String detail) {
        public Violation {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** A reported agent action. */
    public record AgentAction(
        String agentId,
        String output,
        boolean success
    ) {
        public AgentAction {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(output, "output");
        }
    }

    /** Configurable thresholds. */
    public record Thresholds(
        int maxOutputLength,
        int minUniqueChars,
        double maxRepetitionRatio,
        double maxErrorRate,
        int windowSize
    ) {
        public static Thresholds defaults() {
            return new Thresholds(50_000, 20, 0.85, 0.5, 20);
        }
    }

    private final Thresholds thresholds;
    private final List<AgentAction> history = new ArrayList<>();
    private final List<String> flaggedAgents = new ArrayList<>();
    private final List<Violation> violations = new ArrayList<>();

    public ByzantineDetector() {
        this(Thresholds.defaults());
    }

    public ByzantineDetector(Thresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /**
     * Observe one agent action. Returns the list of violations detected for
     * this action (may be empty). If a HIGH-severity violation is found the
     * agent is also flagged.
     */
    public List<Violation> observe(AgentAction action) {
        Objects.requireNonNull(action, "action");
        history.add(action);
        if (history.size() > 1000) {
            history.remove(0);
        }

        List<Violation> detected = new ArrayList<>();
        String output = action.output();

        // Rule 1: output length overflow
        if (output.length() > thresholds.maxOutputLength) {
            detected.add(new Violation("OUTPUT_OVERFLOW", Severity.HIGH,
                "length=" + output.length() + " > max=" + thresholds.maxOutputLength));
        }

        // Rule 2: empty / too short output
        if (output.length() < thresholds.minUniqueChars) {
            detected.add(new Violation("OUTPUT_TOO_SHORT", Severity.LOW,
                "length=" + output.length() + " < min=" + thresholds.minUniqueChars));
        }

        // Rule 3: repetition ratio
        double rep = repetitionRatio(output);
        if (rep > thresholds.maxRepetitionRatio) {
            detected.add(new Violation("OUTPUT_REPETITION", Severity.HIGH,
                "repetition=" + String.format("%.2f", rep) + " > max=" + thresholds.maxRepetitionRatio));
        }

        // Rule 4: error rate (over the rolling window)
        double errRate = recentErrorRate(action.agentId());
        if (errRate > thresholds.maxErrorRate) {
            detected.add(new Violation("ERROR_FLOOD", Severity.HIGH,
                "errRate=" + String.format("%.2f", errRate) + " > max=" + thresholds.maxErrorRate));
        }

        // Rule 5: echo attack (last 3 outputs of this agent are identical)
        if (isEchoing(action.agentId())) {
            detected.add(new Violation("ECHO_ATTACK", Severity.HIGH,
                "last 3 outputs identical for agent " + action.agentId()));
        }

        // Persist & maybe flag
        for (Violation v : detected) {
            violations.add(v);
            if (v.severity() == Severity.HIGH && !flaggedAgents.contains(action.agentId())) {
                flaggedAgents.add(action.agentId());
            }
        }
        return detected;
    }

    /** Is the given agent currently flagged as Byzantine / rogue? */
    public boolean isFlagged(String agentId) {
        return flaggedAgents.contains(agentId);
    }

    /** Get all flagged agents. */
    public List<String> flaggedAgents() {
        return List.copyOf(flaggedAgents);
    }

    /** Get all violations detected so far. */
    public List<Violation> violations() {
        return List.copyOf(violations);
    }

    /**
     * Defense Orchestration Engine (BlockA2A §3.4): halt + revoke for the
     * given agent. Returns true if the agent was flagged, false otherwise.
     */
    public boolean haltAndRevoke(String agentId) {
        if (flaggedAgents.contains(agentId)) {
            // in real impl: trigger halt + permission revocation
            return true;
        }
        return false;
    }

    /** Wipe state (used between runs). */
    public void reset() {
        history.clear();
        flaggedAgents.clear();
        violations.clear();
    }

    // ----- internal helpers -----

    private double repetitionRatio(String s) {
        if (s.isEmpty()) return 0.0;
        // count unique chars vs total
        java.util.BitSet seen = new java.util.BitSet();
        int nonAscii = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) {
                seen.set(c);
            } else {
                nonAscii++;
            }
        }
        int unique = seen.cardinality() + nonAscii;
        return 1.0 - ((double) unique / s.length());
    }

    private double recentErrorRate(String agentId) {
        int count = 0;
        int errors = 0;
        for (int i = history.size() - 1; i >= 0 && count < thresholds.windowSize; i--) {
            AgentAction a = history.get(i);
            if (a.agentId().equals(agentId)) {
                count++;
                if (!a.success()) {
                    errors++;
                }
            }
        }
        if (count == 0) return 0.0;
        return (double) errors / count;
    }

    private boolean isEchoing(String agentId) {
        if (history.size() < 3) return false;
        String last = null, second = null, third = null;
        int found = 0;
        for (int i = history.size() - 1; i >= 0 && found < 3; i--) {
            AgentAction a = history.get(i);
            if (a.agentId().equals(agentId)) {
                found++;
                if (found == 1) last = a.output();
                else if (found == 2) second = a.output();
                else if (found == 3) third = a.output();
            }
        }
        return last != null && last.equals(second) && last.equals(third);
    }

    /** Generate a fresh agent id (for tests). */
    public static String newAgentId() {
        return "agent-" + UUID.randomUUID();
    }
}
