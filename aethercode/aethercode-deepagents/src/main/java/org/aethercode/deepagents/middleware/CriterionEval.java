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
