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

import java.util.function.UnaryOperator;

/**
 * Simple correction strategies for {@link SelfCorrectionLoop}.
 *
 * <p>This file holds the cheap, deterministic strategies that don't
 * need an LLM or a human. The LLM-backed {@link LlmSelfCorrectionStrategy}
 * and the human-gated {@link HumanCorrectionStrategy} live in their
 * own files for clarity.</p>
 */
public final class RetryStrategy {

    private RetryStrategy() {}

    /**
     * Strategy that returns the previous action unchanged. Useful for
     * "wait, just retry" semantics where the verifier's failure is
     * expected to be transient (e.g. a race condition).
     *
     * <p>Note: this strategy never returns {@code null}, so the loop
     * will exhaust the budget rather than give up early.</p>
     */
    public static <T> CorrectionStrategy<T> retrySame() {
        return new CorrectionStrategy<>() {
            @Override
            public T next(T previous, VerificationResult failure, int attempt) {
                return previous;
            }
            @Override
            public String description() { return "retry same action"; }
        };
    }

    /**
     * Strategy that applies a fixed transformation to the previous
     * action. Useful for "strip the leading 4 chars" / "lowercase the
     * whole thing" / "double the number" — the cheap edits that
     * model a domain-specific fix.
     */
    public static <T> CorrectionStrategy<T> transform(UnaryOperator<T> op) {
        if (op == null) throw new IllegalArgumentException("op must be non-null");
        return new CorrectionStrategy<>() {
            @Override
            public T next(T previous, VerificationResult failure, int attempt) {
                return op.apply(previous);
            }
            @Override
            public String description() { return "transform: " + op; }
        };
    }

    /**
     * Strategy that gives up immediately on the first failure. Useful
     * for tests that want to assert the loop terminates with
     * {@code STRATEGY_RETURNED_NULL} on the first verifier fail.
     */
    public static <T> CorrectionStrategy<T> giveUpImmediately() {
        return new CorrectionStrategy<>() {
            @Override
            public T next(T previous, VerificationResult failure, int attempt) {
                return null;
            }
            @Override
            public String description() { return "give up on first failure"; }
        };
    }

    /**
     * Strategy that retries a fixed number of times, then gives up.
     * The {@code retryLimit} counts the number of corrections
     * produced (not the number of attempts). The final
     * {@code null} tells the loop to terminate.
     */
    public static <T> CorrectionStrategy<T> boundedRetry(UnaryOperator<T> op, int retryLimit) {
        if (op == null) throw new IllegalArgumentException("op must be non-null");
        if (retryLimit < 1) throw new IllegalArgumentException("retryLimit must be >= 1");
        return new CorrectionStrategy<>() {
            @Override
            public T next(T previous, VerificationResult failure, int attempt) {
                if (attempt > retryLimit) return null;
                return op.apply(previous);
            }
            @Override
            public String description() { return "bounded retry (" + retryLimit + " times)"; }
        };
    }
}
