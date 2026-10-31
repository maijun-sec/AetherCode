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
 * Type aliases that mirror the Python {@code deepagents.middleware.rubric}
 * public exports.
 *
 * <p>In Python, {@code CriterionPass} and {@code CriterionFail} are
 * separate {@code TypedDict} classes that the {@code CriterionEval}
 * discriminated union resolves to. The Java port models
 * {@code CriterionEval} as a sealed interface with two nested record
 * variants ({@link CriterionEval.Pass} and {@link CriterionEval.Fail}).
 * This file provides flat type aliases so callers can refer to
 * {@code CriterionPass} / {@code CriterionFail} without the
 * {@code CriterionEval.} prefix, matching the Python
 * {@code from deepagents.middleware.rubric import CriterionPass,
 * CriterionFail} import style.</p>
 *
 * <p>Java records are final, so the alias is a {@code static
 * CriterionEval} factory method instead of a subclass. Callers
 * should use the factory methods, not type references.</p>
 */
public final class CriterionAliases {
    private CriterionAliases() {}

    /** Python {@code CriterionPass} -- pass verdict for a single criterion. */
    public static CriterionEval criterionPass(String name) {
        return new CriterionEval.Pass(name);
    }

    /** Python {@code CriterionFail} -- fail verdict for a single criterion. */
    public static CriterionEval criterionFail(String name, String gap) {
        return new CriterionEval.Fail(name, gap);
    }
}
