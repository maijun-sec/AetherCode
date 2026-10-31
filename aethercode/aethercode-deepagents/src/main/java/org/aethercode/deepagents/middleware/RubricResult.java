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

import java.util.Set;

/**
 * Status recorded on each rubric evaluation.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.rubric.RubricResult} literal type.
 * Superset of {@link GraderVerdict} with two
 * middleware-synthesized terminal statuses the grader cannot emit
 * itself: {@code max_iterations_reached} and
 * {@code grader_error}.</p>
 */
public enum RubricResult {
    SATISFIED("satisfied"),
    NEEDS_REVISION("needs_revision"),
    FAILED("failed"),
    MAX_ITERATIONS_REACHED("max_iterations_reached"),
    GRADER_ERROR("grader_error");

    private final String json;
    RubricResult(String json) { this.json = json; }
    public String jsonValue() { return json; }
    public static RubricResult fromJson(String s) {
        if (s == null) return null;
        for (RubricResult v : values()) if (v.json.equalsIgnoreCase(s)) return v;
        return null;
    }

    /** Statuses that signal a completed grading run. */
    public static final Set<RubricResult> TERMINAL_RESULTS = Set.of(
            SATISFIED, MAX_ITERATIONS_REACHED, FAILED, GRADER_ERROR);
}
