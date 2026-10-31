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

package org.aethercode.deepagents.middleware;

/**
 * Per-criterion verdict &mdash; sealed interface mirroring the
 * Python port's discriminated union on {@code passed}.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.rubric.CriterionEval} discriminated
 * union. Pass-verdicts ({@link Pass}) carry no {@code gap};
 * fail-verdicts ({@link Fail}) require one.</p>
 */
public sealed interface CriterionEval
        permits CriterionEval.Pass, CriterionEval.Fail {

    String name();

    record Pass(String name) implements CriterionEval {}

    record Fail(String name, String gap) implements CriterionEval {
        public Fail {
            if (gap == null || gap.isBlank()) {
                throw new IllegalArgumentException(
                        "CriterionFail.gap is required when passed is false");
            }
        }
    }

    static CriterionEval pass(String name) { return new Pass(name); }
    static CriterionEval fail(String name, String gap) { return new Fail(name, gap); }
}
