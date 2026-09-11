package org.aethercode.sdk;

import java.util.List;

/**
 * aggregate statistics over a finished plan. Useful for
 * long-running task execution — the user can see which step was
 * the bottleneck, how much time was spent in retries, and the
 * overall plan duration.
 *
 * <p>Computed from a list of {@link PlanExecutor.StepResult} by
 * {@link #from(List)}. Pure data record, no side effects.
 *
 * <p>Example:
 * <pre>{@code
 * PlanStats stats = PlanStats.from(executor.results());
 * System.out.println("Total: " + stats.totalElapsedMs() + "ms");
 * System.out.println("Slowest: step " + stats.slowestStepIndex()
 *                    + " (" + stats.slowestStepMs() + "ms)");
 * System.out.println("Retries: " + stats.retriedSteps());
 * }</pre>
 */
public record PlanStats(
        int totalSteps,
        int completedSteps,
        int failedSteps,
        int skippedSteps,
        int retriedSteps,
        long totalElapsedMs,
        long avgStepMs,
        long slowestStepMs,
        int slowestStepIndex
) {

    /** number of steps that have not yet completed
     *  (PENDING + RUNNING, from the caller's perspective). Used to
     *  estimate ETA — {@link #etaMs()} multiplies this by
     *  {@link #avgStepMs}. */
    public int remainingSteps() {
        int done = completedSteps + failedSteps + skippedSteps;
        return Math.max(0, totalSteps - done);
    }

    /** rough ETA in milliseconds, computed as
     *  {@code remainingSteps() * avgStepMs}. Returns 0 when no
     *  steps have completed yet (no data) or when the plan is
     *  already done. The estimate is deliberately naive — it
     *  doesn't account for retries, slow steps, or parallel
     *  execution. Use {@link #etaPessimisticMs()} for an
     *  upper-bound estimate. */
    public long etaMs() {
        int remaining = remainingSteps();
        if (remaining <= 0 || avgStepMs <= 0) return 0L;
        return avgStepMs * remaining;
    }

    /** pessimistic ETA in milliseconds, using the slowest
     *  step's duration as the per-step estimate. Useful when the
     *  remaining steps are unknown or likely to be slow. Returns 0
     *  when no data. */
    public long etaPessimisticMs() {
        int remaining = remainingSteps();
        if (remaining <= 0 || slowestStepMs <= 0) return 0L;
        return slowestStepMs * remaining;
    }

    /** median step time in milliseconds. Computed from the
     *  input list on demand (we don't store all step times in
     *  the record to keep the data structure small). Returns 0
     *  when no completed steps. */
    public long medianStepMs(List<PlanExecutor.StepResult> allResults) {
        if (allResults == null || allResults.isEmpty()) return 0L;
        long[] times = allResults.stream()
                .filter(r -> r.outcome() == PlanExecutor.StepOutcome.COMPLETED)
                .mapToLong(PlanExecutor.StepResult::elapsedMs)
                .sorted()
                .toArray();
        if (times.length == 0) return 0L;
        return times[times.length / 2];
    }

    /** Nth percentile (0..100) of step times. Uses the
     *  nearest-rank method. Returns 0 for an empty list or
     *  out-of-range percentile. */
    public long percentile(List<PlanExecutor.StepResult> allResults, int percentile) {
        if (allResults == null || allResults.isEmpty()) return 0L;
        if (percentile < 0 || percentile > 100) return 0L;
        long[] times = allResults.stream()
                .filter(r -> r.outcome() == PlanExecutor.StepOutcome.COMPLETED)
                .mapToLong(PlanExecutor.StepResult::elapsedMs)
                .sorted()
                .toArray();
        if (times.length == 0) return 0L;
        int idx = (int) Math.ceil(percentile / 100.0 * times.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= times.length) idx = times.length - 1;
        return times[idx];
    }

    /** Build a stats record from a list of step results. Returns
     *  an empty-stats record (all zeros) for a null or empty list. */
    public static PlanStats from(List<PlanExecutor.StepResult> results) {
        if (results == null || results.isEmpty()) {
            return new PlanStats(0, 0, 0, 0, 0, 0L, 0L, 0L, -1);
        }
        int total = results.size();
        int completed = 0, failed = 0, skipped = 0, retried = 0;
        long totalMs = 0;
        long slowestMs = -1;
        int slowestIdx = -1;
        for (int i = 0; i < results.size(); i++) {
            PlanExecutor.StepResult r = results.get(i);
            switch (r.outcome()) {
                case COMPLETED -> completed++;
                case FAILED    -> failed++;
                case SKIPPED   -> skipped++;
            }
            totalMs += r.elapsedMs();
            // detect retries via the "[retried Nx]" detail prefix
            // set by PlanExecutor. Detail format is stable enough to
            // parse without a contract.
            if (r.detail() != null && r.detail().startsWith("[retried ")) {
                retried++;
            }
            if (r.elapsedMs() > slowestMs) {
                slowestMs = r.elapsedMs();
                slowestIdx = r.stepIndex();
            }
        }
        long avg = total > 0 ? totalMs / total : 0L;
        return new PlanStats(total, completed, failed, skipped, retried,
                totalMs, avg, Math.max(0, slowestMs), slowestIdx);
    }

    /** Format a human-readable summary, e.g. "5 steps in 12.3s
     *  (avg 2.5s, slowest step 3 @ 4.1s, 1 retried)". */
    public String summary() {
        if (totalSteps == 0) return "empty plan";
        StringBuilder sb = new StringBuilder();
        sb.append(totalSteps).append(" step");
        if (totalSteps != 1) sb.append('s');
        sb.append(" in ").append(formatMs(totalElapsedMs));
        if (completedSteps > 0) {
            sb.append(" (").append(completedSteps).append(" ok");
            if (failedSteps > 0) sb.append(", ").append(failedSteps).append(" failed");
            if (skippedSteps > 0) sb.append(", ").append(skippedSteps).append(" skipped");
            sb.append(")");
        }
        if (avgStepMs > 0 && totalSteps > 1) {
            sb.append(", avg ").append(formatMs(avgStepMs));
        }
        if (slowestStepIndex >= 0 && slowestStepMs > 0 && totalSteps > 1) {
            sb.append(", slowest step ").append(slowestStepIndex + 1)
              .append(" @ ").append(formatMs(slowestStepMs));
        }
        if (retriedSteps > 0) {
            sb.append(", ").append(retriedSteps).append(" retried");
        }
        return sb.toString();
    }

    private static String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long m = ms / 60_000;
        long s = (ms % 60_000) / 1000;
        return m + "m" + s + "s";
    }
}
