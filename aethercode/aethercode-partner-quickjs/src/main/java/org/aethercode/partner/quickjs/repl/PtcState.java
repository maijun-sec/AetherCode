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

package org.aethercode.partner.quickjs.repl;

import org.aethercode.partner.quickjs.repl.Exceptions.PtcCallBudgetExceededException;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-eval PTC state (reset on each eval call). 1:1 port of the
 * Python {@code _PTCState} frozen dataclass in
 * <code>_repl.py</code>.
 *
 * <p>Tracks the call budget plus outer runtime / loop dispatch
 * context for bridge invocations. Allocated at eval start and
 * cleared in {@code finally} so bridge calls cannot run outside the
 * current eval.</p>
 */
public final class PtcState {

    private final int remainingCalls;
    private final Object outerRuntime;
    private final Object outerLoop;

    public PtcState(Integer remainingCalls, Object outerRuntime, Object outerLoop) {
        this.remainingCalls = remainingCalls == null ? -1 : remainingCalls;
        this.outerRuntime = outerRuntime;
        this.outerLoop = outerLoop;
    }

    public Optional<Integer> remainingCalls() {
        return remainingCalls < 0 ? Optional.empty() : Optional.of(remainingCalls);
    }

    public Optional<Object> outerRuntime() {
        return Optional.ofNullable(outerRuntime);
    }

    public Optional<Object> outerLoop() {
        return Optional.ofNullable(outerLoop);
    }

    public Object outerRuntimeOrThrow() {
        Objects.requireNonNull(outerRuntime, "outerRuntime is not set");
        return outerRuntime;
    }

    public Object outerLoopOrNull() {
        return outerLoop;
    }

    /**
     * Count one PTC bridge call and enforce the per-eval limit.
     * Mirrors the Python {@code consume_call_budget}.
     */
    public PtcState consumeCallBudget(String functionName, int maxPtcCalls) {
        if (remainingCalls < 0) return this; // unlimited
        if (remainingCalls > 0) {
            return new PtcState(remainingCalls - 1, outerRuntime, outerLoop);
        }
        int normalizedLimit = maxPtcCalls;
        throw new PtcCallBudgetExceededException(
                normalizedLimit,
                normalizedLimit + 1,
                functionName);
    }
}
