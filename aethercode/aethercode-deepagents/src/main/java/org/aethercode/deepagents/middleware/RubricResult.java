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
