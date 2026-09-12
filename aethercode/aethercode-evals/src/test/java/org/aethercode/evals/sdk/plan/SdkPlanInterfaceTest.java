package org.aethercode.evals.sdk.plan;

import org.aethercode.sdk.DagPlan;
import org.aethercode.sdk.DagPlan.Step;
import org.aethercode.sdk.PlanClassifier;
import org.aethercode.sdk.PlanClassifier.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-2: AetherCode SDK Plan Interface conformance.
 *
 * <p>Companion to {@code PlanningCapabilityTest} (R-eval-1). The
 * capability suite proves planning works on a self-contained
 * Plan/PlanStep/PlanVerifier model; this suite proves the actual
 * SDK class that the TUI / CLI / IDE plugin instantiate (the
 * {@code DagPlan} the user sees in {@code PlanPanel}, the
 * {@code PlanClassifier} that decides auto-approve vs ask) behaves
 * the way the front-end assumes it does.</p>
 */
class SdkPlanInterfaceTest {

    /* ---------------- DagPlan: construction & invariants ---------------- */

    @Test
    void dagPlanRejectsNullSteps() {
        assertThrows(IllegalArgumentException.class, () -> new DagPlan(null));
    }

    @Test
    void dagPlanRejectsDuplicateStepIds() {
        List<Step> steps = List.of(
                new Step("a", "step a", List.of()),
                new Step("a", "step a duplicate", List.of())
        );
        assertThrows(IllegalArgumentException.class, () -> new DagPlan(steps));
    }

    @Test
    void dagPlanRejectsUnknownDependency() {
        List<Step> steps = List.of(
                new Step("a", "step a", List.of("does_not_exist"))
        );
        assertThrows(IllegalArgumentException.class, () -> new DagPlan(steps));
    }

    @Test
    void dagPlanRejectsCycle() {
        // a -> b -> a is a cycle, the topo sort should fail.
        List<Step> steps = List.of(
                new Step("a", "a", List.of("b")),
                new Step("b", "b", List.of("a"))
        );
        assertThrows(IllegalStateException.class, () -> new DagPlan(steps));
    }

    /* ---------------- DagPlan: ready & complete ---------------- */

    @Test
    void dagPlanReadyStepsAreInTopoOrder() {
        // a no-deps, b depends on a, c depends on a, d depends on b + c.
        List<Step> steps = List.of(
                new Step("a", "a", List.of()),
                new Step("b", "b", List.of("a")),
                new Step("c", "c", List.of("a")),
                new Step("d", "d", List.of("b", "c"))
        );
        DagPlan plan = new DagPlan(steps);
        // Nothing completed -> only a is ready.
        assertEquals(List.of("a"), plan.readySteps(Set.of()));
        // a completed -> b and c are ready.
        assertEquals(List.of("b", "c"), plan.readySteps(Set.of("a")));
        // a + b + c completed -> d is ready.
        assertEquals(List.of("d"), plan.readySteps(Set.of("a", "b", "c")));
        // All done -> nothing ready.
        assertEquals(List.of(), plan.readySteps(Set.of("a", "b", "c", "d")));
    }

    @Test
    void dagPlanIsCompleteReturnsFalseUntilAllStepsDone() {
        List<Step> steps = List.of(
                new Step("a", "a", List.of()),
                new Step("b", "b", List.of("a"))
        );
        DagPlan plan = new DagPlan(steps);
        assertFalse(plan.isComplete(Set.of()));
        assertFalse(plan.isComplete(Set.of("a")));
        assertTrue(plan.isComplete(Set.of("a", "b")));
    }

    @Test
    void dagPlanEmptyPlanIsTriviallyComplete() {
        DagPlan plan = new DagPlan(List.of());
        assertEquals(0, plan.steps().size());
        assertTrue(plan.isComplete(Set.of()));
    }

    @Test
    void dagPlanLookupById() {
        DagPlan plan = new DagPlan(List.of(
                new Step("a", "first", List.of()),
                new Step("b", "second", List.of("a"))
        ));
        assertNotNull(plan.get("a"));
        assertEquals("first", plan.get("a").title());
        assertEquals("second", plan.get("b").title());
        assertEquals(null, plan.get("does_not_exist"));
    }

    /* ---------------- PlanClassifier ---------------- */

    @Test
    void planClassifierApprovesTrivialPlan() {
        // 2 read-only steps, no destructive tools -> TRIVIAL.
        List<PlanClassifier.Step> steps = List.of(
                new PlanClassifier.Step("read /etc/hostname"),
                new PlanClassifier.Step("summarise first 10 lines")
        );
        PlanClassifier classifier = new PlanClassifier();
        assertEquals(Verdict.TRIVIAL, classifier.classify(steps));
    }

    @Test
    void planClassifierRejectsBashStep() {
        // bash is in DEFAULT_DESTRUCTIVE_TOOLS.
        List<PlanClassifier.Step> steps = List.of(
                new PlanClassifier.Step("read file"),
                new PlanClassifier.Step("run bash command")
        );
        assertEquals(Verdict.NEEDS_APPROVAL, new PlanClassifier().classify(steps));
    }

    @Test
    void planClassifierRejectsLongPlan() {
        // 4 read-only steps > DEFAULT_MAX_STEPS (3).
        List<PlanClassifier.Step> steps = List.of(
                new PlanClassifier.Step("read a"),
                new PlanClassifier.Step("read b"),
                new PlanClassifier.Step("read c"),
                new PlanClassifier.Step("read d")
        );
        assertEquals(Verdict.NEEDS_APPROVAL, new PlanClassifier().classify(steps));
    }

    @Test
    void planClassifierWithLargerMaxApprovesLongPlan() {
        // Same 4 steps, but maxSteps=10.
        List<PlanClassifier.Step> steps = List.of(
                new PlanClassifier.Step("read a"),
                new PlanClassifier.Step("read b"),
                new PlanClassifier.Step("read c"),
                new PlanClassifier.Step("read d")
        );
        PlanClassifier classifier = new PlanClassifier(10, PlanClassifier.DEFAULT_DESTRUCTIVE_TOOLS);
        assertEquals(Verdict.TRIVIAL, classifier.classify(steps));
    }

    @Test
    void planClassifierWithEmptyDestructiveSetIgnoresToolNames() {
        // empty destructive set -> even bash is fine.
        List<PlanClassifier.Step> steps = List.of(
                new PlanClassifier.Step("run bash command")
        );
        PlanClassifier classifier = new PlanClassifier(3, Set.of());
        assertEquals(Verdict.TRIVIAL, classifier.classify(steps));
    }

    @Test
    void planClassifierDestructiveMatchIsCaseInsensitive() {
        // "BASH" upper case, but default set is lower-case, so case-insensitive
        // match should still flag it.
        List<PlanClassifier.Step> steps = List.of(
                new PlanClassifier.Step("BASH shell one-liner")
        );
        assertEquals(Verdict.NEEDS_APPROVAL, new PlanClassifier().classify(steps));
    }

    /* ---------------- Step.record immutability ---------------- */

    @Test
    void dagPlanStepDependsOnIsCopied() {
        // The SDK should copy the dependsOn list so external mutation
        // cannot change plan semantics after construction.
        java.util.List<String> mutableDeps = new java.util.ArrayList<>();
        mutableDeps.add("a");
        Step step = new Step("b", "b", mutableDeps);
        mutableDeps.add("z"); // try to mutate after construction
        // step.dependsOn() should still be ["a"], not ["a", "z"].
        assertEquals(List.of("a"), step.dependsOn());
    }
}
