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

package org.aethercode.orchestration.selfcorrect;

import org.aethercode.orchestration.verifier.Verifier.VerificationResult;

/**
 * Strategy for producing a corrected action after a verification failure.
 *
 * <p>Self-correction (paper 2508.17281 §5.2 "反思重规划"; Reflexion /
 * Self-Refine families) is the bridge between the V verifier set
 * (R-radar-6) and the actual action: when V rejects an action, the
 * agent must decide what to do next — retry, ask the LLM to revise,
 * ask a human, fall back, etc.</p>
 *
 * <p>The strategy is parameterised on the action type {@code T} so
 * the same loop can self-correct structured actions (tool calls,
 * JSON responses) and free-form strings (LLM continuations) without
 * losing type safety.</p>
 */
public interface CorrectionStrategy<T> {

    /**
     * Produce the next action to try, given the previous action and
     * the verifier's failure result.
     *
     * <p>Returns {@code null} to signal "give up" (no more options).
     * The loop will then terminate with a {@code LoopResult} whose
     * {@code terminal} field is set to {@code TerminalReason.GAVE_UP}.</p>
     */
    T next(T previous, VerificationResult failure, int attempt);

    /** Optional one-line description of what this strategy does. */
    default String description() { return ""; }

    /** Why the strategy chose to give up. */
    enum GiveUpReason {
        /** Strategy returned null. */
        STRATEGY_RETURNED_NULL,
        /** Out of budget. */
        BUDGET_EXHAUSTED,
        /** Verifier passed — no correction needed. */
        PASSED,
        /** External interrupt (e.g. timeout). */
        INTERRUPTED
    }
}
