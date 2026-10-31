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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured output the grader sub-agent returns.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.rubric.GraderResponse} Pydantic
 * model. The record carries the verdict, the per-criterion
 * evaluations, and a free-form comments field. The Java port
 * stores the evaluations as a list of
 * {@link CriterionEval} records, so the discriminated union's
 * shape is enforced at construction time.</p>
 */
public record GraderResponse(
        GraderVerdict result,
        List<CriterionEval> evaluations,
        String comments) {

    public GraderResponse {
        Objects.requireNonNull(result, "result");
        evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
    }

    public static GraderResponse satisfied(List<CriterionEval> evals) {
        return new GraderResponse(GraderVerdict.SATISFIED, evals, null);
    }
    public static GraderResponse needsRevision(List<CriterionEval> evals, String comments) {
        return new GraderResponse(GraderVerdict.NEEDS_REVISION, evals, comments);
    }
    public static GraderResponse failed(List<CriterionEval> evals, String comments) {
        return new GraderResponse(GraderVerdict.FAILED, evals, comments);
    }

    /** Build a {@link GraderResponse} from a raw map (parsed JSON). */
    @SuppressWarnings("unchecked")
    public static GraderResponse fromMap(Map<String, Object> map) {
        if (map == null) throw new IllegalArgumentException("map is null");
        Object r = map.get("result");
        GraderVerdict verdict = GraderVerdict.fromJson(String.valueOf(r));
        if (verdict == null) {
            throw new IllegalArgumentException("Unknown grader verdict: " + r);
        }
        List<CriterionEval> evals = new java.util.ArrayList<>();
        Object evalsRaw = map.get("evaluations");
        if (evalsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> mm = (Map<String, Object>) m;
                    String name = String.valueOf(mm.get("name"));
                    Object passedRaw = mm.get("passed");
                    boolean passed = Boolean.TRUE.equals(passedRaw);
                    Object gapRaw = mm.get("gap");
                    String gap = gapRaw == null ? null : String.valueOf(gapRaw);
                    if (passed) {
                        evals.add(CriterionEval.pass(name));
                    } else {
                        if (gap == null || gap.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Criterion '" + name + "' has passed=false but no gap");
                        }
                        evals.add(CriterionEval.fail(name, gap));
                    }
                }
            }
        }
        // Cross-field consistency: the top-level verdict
        // must agree with the per-criterion data when
        // criteria are present. Mirrors the Python
        // port's Pydantic model_validator.
        if (!evals.isEmpty()) {
            boolean anyFailing = evals.stream()
                    .anyMatch(e -> e instanceof CriterionEval.Fail);
            boolean anyPassing = evals.stream()
                    .anyMatch(e -> e instanceof CriterionEval.Pass);
            switch (verdict) {
                case SATISFIED -> {
                    if (anyFailing) {
                        throw new IllegalArgumentException(
                                "Top-level verdict 'satisfied' is inconsistent with failing criteria");
                    }
                }
                case NEEDS_REVISION -> {
                    if (!anyFailing && anyPassing) {
                        throw new IllegalArgumentException(
                                "Top-level verdict 'needs_revision' is inconsistent with all-passing criteria");
                    }
                }
                case FAILED -> {
                    // FAILED is the catch-all; no invariant.
                }
            }
        }
        Object comments = map.get("comments");
        return new GraderResponse(verdict, evals, comments == null ? null : String.valueOf(comments));
    }
}
