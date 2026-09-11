package org.aethercode.deepagents.middleware;

import java.util.List;
import java.util.Map;

/**
 * One grader evaluation, appended to {@code _rubric_evaluations}
 * each iteration.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.rubric.RubricEvaluation} TypedDict.
 * The record carries the iteration index, the verdict, the
 * per-criterion evaluations, and a free-form details map.</p>
 */
public record RubricEvaluation(
        int iteration,
        RubricResult result,
        GraderResponse response,
        Map<String, Object> details) {

    public RubricEvaluation {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public List<CriterionEval> evaluations() {
        return response == null ? List.of() : response.evaluations();
    }
}
