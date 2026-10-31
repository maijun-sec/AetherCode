/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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