package org.aethercode.hooks.builtin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-phase tool-call budget tracker.
 *
 * <p>The 4-phase methodology (plan → explore → implement → verify,
 * see the {@code defaultWorkflow()} in {@code SystemPrompt.java}) gives
 * the model a structure to follow. Without a budget, the model can
 * easily burn a 50-turn budget on a single phase (e.g. 30 reads
 * during explore) and run out before it gets to verify. prior round caps
 * the cost of each phase.
 *
 * <p>The tracker is intentionally simple:
 * <ul>
 *   <li>One current {@code phase} (string, defaults to {@code "plan"}).
 *       The phase is set by the engine, the user (via a
 *       {@code /phase <name>} slash command), or the model
 *       (via a {@code todo_write} call — see {@link #inferPhaseFromTodo}).</li>
 *   <li>Per-phase counters: how many tool calls have been made
 *       in the current phase, and how much cost (USD) has
 *       accumulated.</li>
 *   <li>Per-phase caps: max tool calls + max cost. Configurable
 *       via {@link Builder} or {@link #setBudget(String, int, double)}.</li>
 *   <li>Thread-safe: a daemon with multiple TUI clients
 *       concurrently querying / transitioning phases can race
 *       on the counters. We use {@link ConcurrentHashMap} for
 *       the per-phase buckets and a single volatile
 *       {@code currentPhase} reference.</li>
 * </ul>
 *
 * <p>The hook side of the contract is in
 * {@link PhaseBudgetHook}. The tracker is the pure state
 * primitive; the hook does the policy enforcement.
 */
public final class PhaseTracker {

    private static final Logger LOG = LoggerFactory.getLogger(PhaseTracker.class);

    /** Default phase name used when the engine boots. */
    public static final String DEFAULT_PHASE = "plan";
    /** All four phases the methodology expects, in order. The
     *  tracker is permissive — callers can introduce new phase
     *  names — but the default budget covers these four. */
    public static final String PHASE_PLAN      = "plan";
    public static final String PHASE_EXPLORE   = "explore";
    public static final String PHASE_IMPLEMENT = "implement";
    public static final String PHASE_VERIFY    = "verify";

    /** Per-phase counts. We use a regular HashMap (not
     *  ConcurrentHashMap) inside the buckets but every
     *  read/write goes through a synchronized method.
     *  The set of phases is small and the access pattern
     *  is simple, so a coarse lock is fine. */
    public static final class Bucket {
        public volatile int toolCalls;
        public volatile double costUsd;
        public volatile int maxToolCalls;
        public volatile double maxCostUsd;
        public Bucket(int maxToolCalls, double maxCostUsd) {
            this.maxToolCalls = maxToolCalls;
            this.maxCostUsd = maxCostUsd;
        }
        public int remainingToolCalls() {
            int cap = maxToolCalls;
            if (cap <= 0) return Integer.MAX_VALUE;  // 0 = unlimited
            return Math.max(0, cap - toolCalls);
        }
        public double remainingCostUsd() {
            double cap = maxCostUsd;
            if (cap <= 0) return Double.POSITIVE_INFINITY;
            return Math.max(0.0, cap - costUsd);
        }
    }

    private volatile String currentPhase = DEFAULT_PHASE;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    /** Lock for atomic transitions (currentPhase + new bucket). */
    private final Object transitionLock = new Object();

    public PhaseTracker() {
        this(defaultBudgets());
    }

    public PhaseTracker(Map<String, Bucket> initialBudgets) {
        if (initialBudgets != null) {
            buckets.putAll(initialBudgets);
        }
        // Always seed the four canonical phases so a
        // getBucket(plan) before any setBudget call
        // returns a sensible default (unlimited).
        for (String p : new String[]{PHASE_PLAN, PHASE_EXPLORE, PHASE_IMPLEMENT, PHASE_VERIFY}) {
            buckets.computeIfAbsent(p, k -> new Bucket(0, 0.0));
        }
    }

    /** built-in budget that errs on the generous
     *  side. Users can override per-phase with
     *  {@link #setBudget}. The numbers come from
     *  observation: a typical "edit one file" task
     *  uses ~5 explore, ~3 implement, ~2 verify.
     *  We pad 4x so the cap only fires on runaway
     *  loops. The cost cap is in USD; the model
     *  itself tracks cost in the engine metrics. */
    public static Map<String, Bucket> defaultBudgets() {
        Map<String, Bucket> m = new LinkedHashMap<>();
        // plan: no tools expected (only todo_write).
        // Cap at 2 to allow a small "let me re-check
        // the file" allowance without runaway.
        m.put(PHASE_PLAN,      new Bucket(2,  0.00));
        m.put(PHASE_EXPLORE,   new Bucket(20, 0.10));
        m.put(PHASE_IMPLEMENT, new Bucket(50, 0.20));
        m.put(PHASE_VERIFY,    new Bucket(15, 0.05));
        return m;
    }

    /** get the current phase. */
    public String currentPhase() {
        return currentPhase;
    }

    /** transition to a new phase. Resets the
     *  per-phase counters so the new phase starts
     *  with a fresh budget. The new phase must
     *  have a bucket (auto-created with unlimited
     *  caps if missing). */
    public void setPhase(String phase) {
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("phase must not be blank");
        }
        synchronized (transitionLock) {
            // When leaving the old phase, leave the
            // counters alone — the bucket is the
            // historical record. The new phase starts
            // at zero.
            this.currentPhase = phase;
            buckets.computeIfAbsent(phase, k -> new Bucket(0, 0.0));
        }
    }

    /** update the per-phase caps. {@code maxToolCalls}
     *  or {@code maxCostUsd} of 0 means unlimited. The
     *  counters (already-spent) are NOT reset. */
    public void setBudget(String phase, int maxToolCalls, double maxCostUsd) {
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("phase must not be blank");
        }
        if (maxToolCalls < 0 || maxCostUsd < 0) {
            throw new IllegalArgumentException("caps must be non-negative");
        }
        synchronized (transitionLock) {
            Bucket b = buckets.computeIfAbsent(phase, k -> new Bucket(0, 0.0));
            b.maxToolCalls = maxToolCalls;
            b.maxCostUsd = maxCostUsd;
        }
    }

    /** record a tool call against the CURRENT
     *  phase. Returns the bucket after the update so
     *  the caller can check whether the call just
     *  exhausted the budget. The tool name is
     *  recorded for the audit log (Phase 2 / prior round+
     *  might surface this on a dashboard). */
    public Bucket recordToolCall(String toolName, double costUsd) {
        synchronized (transitionLock) {
            Bucket b = buckets.computeIfAbsent(currentPhase, k -> new Bucket(0, 0.0));
            b.toolCalls++;
            b.costUsd += costUsd;
            LOG.debug("phase={} tool={} count={}/{} cost=${}/{}",
                    currentPhase, toolName, b.toolCalls, b.maxToolCalls,
                    String.format("%.4f", b.costUsd), String.format("%.4f", b.maxCostUsd));
            return b;
        }
    }

    /** check whether the current phase is over
     *  budget. The hook calls this from
     *  {@code PRE_TOOL_USE} and blocks if true. */
    public boolean isOverBudget() {
        synchronized (transitionLock) {
            Bucket b = buckets.get(currentPhase);
            if (b == null) return false;
            if (b.maxToolCalls > 0 && b.toolCalls >= b.maxToolCalls) return true;
            if (b.maxCostUsd > 0 && b.costUsd >= b.maxCostUsd) return true;
            return false;
        }
    }

    /** snapshot of the current state. The wire
     *  format for {@code getPhaseBudget}. Immutable. */
    public Snapshot snapshot() {
        synchronized (transitionLock) {
            Map<String, Bucket> snap = new LinkedHashMap<>();
            for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
                Bucket src = e.getValue();
                Bucket copy = new Bucket(src.maxToolCalls, src.maxCostUsd);
                copy.toolCalls = src.toolCalls;
                copy.costUsd = src.costUsd;
                snap.put(e.getKey(), copy);
            }
            return new Snapshot(currentPhase, snap);
        }
    }

    /** infer a phase name from a tool call. Read-only
     *  tools map to {@code explore}; mutating tools map to
     *  {@code implement}; test tools map to {@code verify}.
     *  The phase name is returned for the caller to set
     *  via {@link #setPhase}. This is a heuristic — the
     *  caller may override.
     *
     *  <p>The set of mutating tools is the inverse of the
     *  Tools policy in {@code aethercode-permission}; the
     *  names match the canonical tool list. We err on
     *  the side of "implement" for unknown tools so a
     *  capability check (the safest mode) is the
     *  default. */
    public static String inferPhaseFromTool(String toolName) {
        if (toolName == null) return PHASE_EXPLORE;
        String t = toolName.toLowerCase();
        // Implement: file writes, edits, bash that creates / deletes.
        if (t.contains("write") || t.contains("edit") || t.equals("bash")
                || t.contains("delete") || t.contains("mkdir")
                || t.contains("rm_") || t.contains("mv_") || t.contains("cp_")) {
            // Bash is ambiguous (test commands are also
            // bash). Treat as implement; the caller can
            // transition to verify explicitly.
            return PHASE_IMPLEMENT;
        }
        // Verify: test runners, linters, build commands.
        if (t.contains("test") || t.contains("lint") || t.contains("check")
                || t.contains("verify") || t.contains("run_")) {
            return PHASE_VERIFY;
        }
        // Default: read-only tools (read_file, ls, grep,
        // glob, file_search, etc.) — explore.
        return PHASE_EXPLORE;
    }

    /** immutable wire snapshot. */
    public record Snapshot(String currentPhase, Map<String, Bucket> buckets) {
        public Snapshot {
            if (currentPhase == null) currentPhase = DEFAULT_PHASE;
            if (buckets == null) buckets = Map.of();
            // Defensive copy on the outer map.
            buckets = Map.copyOf(buckets);
        }
    }
}
