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
