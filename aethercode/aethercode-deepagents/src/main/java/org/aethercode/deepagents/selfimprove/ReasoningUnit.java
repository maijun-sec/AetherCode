package org.aethercode.deepagents.selfimprove;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * R241.2 (O-3) + R244.1 (O-6): one abstracted reflection,
 * modelled on the <em>ReasoningBank</em> pattern
 * (paper 1 §4.2.2, 2025). The first four fields capture
 * the reflection itself; the last four capture the
 * "is this advice still trusted?" metrics.
 *
 * <h2>Reflection (R241.2)</h2>
 *
 * <ol>
 *   <li>{@link #taskKind} — what kind of task this
 *       reflection applies to (e.g. {@code "file_edit"},
 *       {@code "build"}, {@code "test"}). Recall uses this
 *       as the primary index.</li>
 *   <li>{@link #errorPattern} — a one-line description of
 *       what went wrong.</li>
 *   <li>{@link #fixStrategy} — a one-line description of
 *       what to do next time.</li>
 *   <li>{@link #example} — a short transcript excerpt.</li>
 * </ol>
 *
 * <h2>Metrics (R241.2 + R244.1)</h2>
 *
 * <ul>
 *   <li>{@link #utility} (R241.2) — abstract preference
 *       score in {@code [0, 1]}; bumps up on every recall
 *       hit, decays on age.</li>
 *   <li>{@link #uses} (R241.2) — number of recall hits.</li>
 *   <li>{@link #okCount} / {@link #notOkCount} (R244.1) —
 *       binary self-eval outcomes. A unit with
 *       {@code okCount=4, notOkCount=1} has been
 *       followed-and-it-worked four times and failed
 *       once. {@link #confidence()} combines these.</li>
 *   <li>{@link #createdAt} — when first stored.</li>
 * </ul>
 *
 * <p>{@link #okCount} and {@link #notOkCount} are absent
 * in units persisted previously.1; Jackson falls back
 * to {@code 0}, so the schema change is forward- and
 * backward-compatible at the JSON level.
 */
public record ReasoningUnit(
        String id,
        String taskKind,
        String errorPattern,
        String fixStrategy,
        String example,
        double utility,
        long uses,
        long okCount,
        long notOkCount,
        Instant createdAt) {

    public ReasoningUnit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskKind, "taskKind");
        Objects.requireNonNull(errorPattern, "errorPattern");
        Objects.requireNonNull(fixStrategy, "fixStrategy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (utility < 0 || utility > 1) {
            throw new IllegalArgumentException("utility must be in [0,1], got " + utility);
        }
        if (uses < 0) {
            throw new IllegalArgumentException("uses must be >= 0, got " + uses);
        }
        if (okCount < 0) {
            throw new IllegalArgumentException("okCount must be >= 0, got " + okCount);
        }
        if (notOkCount < 0) {
            throw new IllegalArgumentException("notOkCount must be >= 0, got " + notOkCount);
        }
    }

    public static ReasoningUnit of(String taskKind, String errorPattern,
                                    String fixStrategy, String example) {
        return new ReasoningUnit(java.util.UUID.randomUUID().toString(),
                taskKind, errorPattern, fixStrategy, example,
                0.5, 0L, 0L, 0L, Instant.now());
    }

    /**
     * R244.1 back-compat: the 8-arg constructor used
     * before {@code okCount} / {@code notOkCount} existed
     * is preserved as a delegating constructor so older
     * call sites and tests compile unchanged. The two
     * outcome counters default to {@code 0L}.
     */
    public ReasoningUnit(String id,
                          String taskKind,
                          String errorPattern,
                          String fixStrategy,
                          String example,
                          double utility,
                          long uses,
                          Instant createdAt) {
        this(id, taskKind, errorPattern, fixStrategy, example,
                utility, uses, 0L, 0L, createdAt);
    }

    /** Visible for the test suite. */
    public ReasoningUnit withUtility(double u) {
        return new ReasoningUnit(id, taskKind, errorPattern, fixStrategy, example,
                u, uses, okCount, notOkCount, createdAt);
    }

    public ReasoningUnit withUses(long n) {
        return new ReasoningUnit(id, taskKind, errorPattern, fixStrategy, example,
                utility, n, okCount, notOkCount, createdAt);
    }

    /**
     * R244.1 (O-6): bump the outcome counter implied by
     * {@code ok}. {@code true} increments {@link #okCount};
     * {@code false} increments {@link #notOkCount}. The
     * other fields are preserved.
     */
    public ReasoningUnit withOutcome(boolean ok) {
        return ok
                ? new ReasoningUnit(id, taskKind, errorPattern, fixStrategy, example,
                        utility, uses, okCount + 1L, notOkCount, createdAt)
                : new ReasoningUnit(id, taskKind, errorPattern, fixStrategy, example,
                        utility, uses, okCount, notOkCount + 1L, createdAt);
    }

    /**
     * R244.1 (O-6): the unit's "how much do we trust
     * this?" score, in {@code (0, 1]}. Laplace-smoothed
     * (the {@code +1} term in the denominator) so a unit
     * with zero observations still has a usable score.
     *
     * <p>Confidence is the recall ranking's "trust"
     * factor; combine with effective utility for the
     * full picture:</p>
     *
     * <pre>score = confidence * effectiveUtility</pre>
     */
    public double confidence() {
        return (double) okCount / (double) (okCount + notOkCount + 1L);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("taskKind", taskKind);
        m.put("errorPattern", errorPattern);
        m.put("fixStrategy", fixStrategy);
        m.put("example", example);
        m.put("utility", utility);
        m.put("uses", uses);
        m.put("okCount", okCount);
        m.put("notOkCount", notOkCount);
        m.put("createdAt", createdAt.toString());
        return m;
    }
}
