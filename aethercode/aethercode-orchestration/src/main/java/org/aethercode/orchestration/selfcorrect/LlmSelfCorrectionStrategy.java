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

import java.util.function.BiFunction;

/**
 * LLM-backed self-correction strategy. On a verification failure,
 * asks an LLM "the previous action was rejected for the following
 * reason — please produce a corrected action".
 *
 * <p>Mirrors the Reflexion / Self-Refine pattern from paper
 * 2508.17281 §5.2 ("反思重规划"). The LLM is given:</p>
 * <ul>
 *   <li>The previous action it produced</li>
 *   <li>The verifier's structured result (severity, reason, metadata)</li>
 *   <li>The attempt number (1, 2, 3, ...) so the LLM can adapt
 *       ("this is my second try — try a more conservative action")</li>
 * </ul>
 *
 * <p>Design notes (same as {@link org.aethercode.orchestration.verifier.LlmJudgeVerifier}):</p>
 * <ul>
 *   <li>The actual LLM call is <b>not</b> in this class — it's
 *       pluggable via {@link ReviseFn}. Tests pass a stub; production
 *       wires a real LLM.</li>
 *   <li>If the LLM throws, the strategy propagates the exception so
 *       the {@link SelfCorrectionLoop} can record it as a
 *       {@code STRATEGY_RETURNED_NULL} terminal.</li>
 *   <li>The {@code ReviseFn} can return {@code null} to give up
 *       (e.g. the LLM has decided the problem is unsolvable). The
 *       loop will then terminate gracefully.</li>
 * </ul>
 *
 * <p>Optional fallback: the strategy can wrap another strategy to
 * fall back to when the LLM gives up. The default is no fallback
 * (LLM null is propagated).</p>
 */
public class LlmSelfCorrectionStrategy<T> implements CorrectionStrategy<T> {

    /**
     * The revision function — given (previous, failure, attempt)
     * return the next action, or {@code null} to give up.
     */
    @FunctionalInterface
    public interface ReviseFn<T> {
        T revise(T previous, VerificationResult failure, int attempt);
    }

    private final String name;
    private final ReviseFn<T> revise;
    private final CorrectionStrategy<T> fallback;

    public LlmSelfCorrectionStrategy(String name, ReviseFn<T> revise) {
        this(name, revise, null);
    }

    public LlmSelfCorrectionStrategy(String name, ReviseFn<T> revise,
                                     CorrectionStrategy<T> fallback) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (revise == null) {
            throw new IllegalArgumentException("revise must be non-null");
        }
        this.name = name;
        this.revise = revise;
        this.fallback = fallback;
    }

    /**
     * Build an LLM strategy from a generic {@code (T, FailureInfo) -> T}
     * function. Useful when callers already have a BiFunction lying
     * around (e.g. an LLM client method reference).
     */
    public static <T> LlmSelfCorrectionStrategy<T> of(String name,
                                                       BiFunction<T, VerificationResult, T> revise) {
        return new LlmSelfCorrectionStrategy<>(name,
                (prev, fail, attempt) -> revise.apply(prev, fail));
    }

    @Override
    public T next(T previous, VerificationResult failure, int attempt) {
        T revised = revise.revise(previous, failure, attempt);
        if (revised == null && fallback != null) {
            return fallback.next(previous, failure, attempt);
        }
        return revised;
    }

    @Override
    public String description() {
        return "LLM self-correction" + (fallback == null ? "" : " with fallback");
    }

    public String name() { return name; }
}
