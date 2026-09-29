package org.aethercode.core.eval;

import java.util.List;
import java.util.Map;

/**
 * One run of one test, returned to the harness via JSON-RPC.
 *
 * <p>The Python harness expects these fields when calling
 * {@code agent.run} (see {@code harness/runner.py}):
 *
 * <pre>{@code
 * {
 *   "output": "...",
 *   "trace": [...tool calls...],
 *   "cost_usd": 0.05,
 *   "tokens": {"input": 1000, "output": 500},
 *   "wall_clock_ms": 12000
 * }
 * }</pre>
 *
 * <p>The harness records these to disk and aggregates per-category
 * 4-tuple metrics (accuracy x cost x latency x reliability).
 *
 * <p>Tool calls in {@link #trace} are flat maps with at least
 * {@code name} and {@code args}. Captured by {@link TraceRecorder}
 * via {@link org.aethercode.core.tool.ToolHook}.
 */
public final class EvalResult {

    public final String testId;
    public final int runIdx;
    public final String output;
    public final List<Map<String, Object>> trace;  // tool calls
    public final double costUsd;
    public final long tokensInput;
    public final long tokensOutput;
    public final long wallClockMs;
    public final String error;
    public final Map<String, Object> metadata;

    public EvalResult(
            String testId,
            int runIdx,
            String output,
            List<Map<String, Object>> trace,
            double costUsd,
            long tokensInput,
            long tokensOutput,
            long wallClockMs,
            String error,
            Map<String, Object> metadata) {
        this.testId = testId;
        this.runIdx = runIdx;
        this.output = output;
        this.trace = trace;
        this.costUsd = costUsd;
        this.tokensInput = tokensInput;
        this.tokensOutput = tokensOutput;
        this.wallClockMs = wallClockMs;
        this.error = error;
        this.metadata = metadata;
    }

    public boolean isSuccess() {
        return error == null || error.isEmpty();
    }

    public long totalTokens() {
        return tokensInput + tokensOutput;
    }
}