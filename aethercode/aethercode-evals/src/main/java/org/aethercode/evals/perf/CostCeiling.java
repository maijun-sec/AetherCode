package org.aethercode.evals.perf;

import java.util.Objects;

/**
 * Hard budget for an {@link org.aethercode.evals.orchestration.AgentRuntime}
 * invocation. Tracks calls, wall-clock millis, and an externally counted
 * token total, and short-circuits the run when any axis blows past its
 * ceiling.
 *
 * <p>The ceiling is shared between the verifier, the self-correction
 * loop, and the multi-agent ensemble: a single counter is incremented
 * by every node so the budget is uniform across the whole pipeline.
 * (This matches the "Plan-and-Execute + Reflexion" loop budget from
 * paper 2508.17281 §5.2, and the self-consistency / debate cost ceiling
 * from paper 2601.01743 §III.1.1.)</p>
 *
 * <p>The {@link #exceeded()} check is a pure function of the counters;
 * the caller decides what to do on exceed (typically return a
 * "budget-exhausted" outcome rather than forge ahead and blow the
 * budget on the next call). This keeps the ceiling non-invasive: the
 * orchestration does not depend on the {@link CostCeiling} type
 * existing.</p>
 */
public final class CostCeiling {

    private final int maxCalls;
    private final long maxMillis;
    private final long maxTokens;
    private final long startMillis;
    private int calls;
    private long tokens;

    public CostCeiling(int maxCalls, long maxMillis, long maxTokens) {
        if (maxCalls < 1) {
            throw new IllegalArgumentException("maxCalls must be >= 1");
        }
        if (maxMillis < 1) {
            throw new IllegalArgumentException("maxMillis must be >= 1");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1");
        }
        this.maxCalls = maxCalls;
        this.maxMillis = maxMillis;
        this.maxTokens = maxTokens;
        this.startMillis = System.currentTimeMillis();
    }

    /**
     * Bookkeeping hook: a call is about to happen, possibly with a
     * token cost. Returns {@code true} iff the ceiling is now exceeded.
     * Both axes are checked after the increment so a single over-budget
     * call is captured rather than allowed to slip through.
     */
    public synchronized boolean recordCall(long tokenCost) {
        if (tokenCost < 0) {
            throw new IllegalArgumentException("tokenCost must be >= 0");
        }
        calls++;
        tokens += tokenCost;
        return exceeded();
    }

    /** True iff any axis (calls, millis, tokens) is at or over budget. */
    public synchronized boolean exceeded() {
        if (calls >= maxCalls) return true;
        if (tokens >= maxTokens) return true;
        if (System.currentTimeMillis() - startMillis >= maxMillis) return true;
        return false;
    }

    public synchronized int calls() { return calls; }
    public synchronized long tokens() { return tokens; }
    public synchronized long elapsedMillis() { return System.currentTimeMillis() - startMillis; }

    public int maxCalls() { return maxCalls; }
    public long maxMillis() { return maxMillis; }
    public long maxTokens() { return maxTokens; }

    /** Read-only snapshot of current usage. */
    public synchronized Usage usage() {
        return new Usage(calls, tokens, System.currentTimeMillis() - startMillis,
                maxCalls, maxMillis, maxTokens);
    }

    /** Read-only snapshot of the ceiling's current usage and limits. */
    public record Usage(
            int calls, long tokens, long elapsedMillis,
            int maxCalls, long maxMillis, long maxTokens) {
        public double callFraction() { return (double) calls / (double) maxCalls; }
        public double tokenFraction() { return (double) tokens / (double) maxTokens; }
        public double timeFraction() { return (double) elapsedMillis / (double) maxMillis; }
        public boolean anyExceeded() {
            return calls > maxCalls || tokens > maxTokens || elapsedMillis > maxMillis;
        }
    }

    /** Builder for ceilings with only some axes set; the rest get a generous default. */
    public static final class Builder {
        private int maxCalls = 64;
        private long maxMillis = 60_000L;
        private long maxTokens = 1_000_000L;

        public Builder maxCalls(int v) { this.maxCalls = v; return this; }
        public Builder maxMillis(long v) { this.maxMillis = v; return this; }
        public Builder maxTokens(long v) { this.maxTokens = v; return this; }

        public CostCeiling build() { return new CostCeiling(maxCalls, maxMillis, maxTokens); }
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CostCeiling that)) return false;
        return maxCalls == that.maxCalls
                && maxMillis == that.maxMillis
                && maxTokens == that.maxTokens;
    }

    @Override
    public int hashCode() { return Objects.hash(maxCalls, maxMillis, maxTokens); }
}
