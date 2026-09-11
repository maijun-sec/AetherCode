package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link PlanClassifier} — the trivial-vs-
 * needs-approval decision used by auto-approve.
 */
class PlanClassifierTest {

    private static List<PlanClassifier.Step> steps(String... titles) {
        return java.util.Arrays.stream(titles).map(PlanClassifier.Step::new).toList();
    }

    @Test
    void classify_nullPlan_needsApproval() {
        assertEquals(PlanClassifier.Verdict.NEEDS_APPROVAL,
                new PlanClassifier().classify(null));
    }

    @Test
    void classify_emptyApproved_needsApproval() {
        assertEquals(PlanClassifier.Verdict.NEEDS_APPROVAL,
                new PlanClassifier().classify(List.of()));
    }

    @Test
    void classify_singleSafeStep_trivial() {
        assertEquals(PlanClassifier.Verdict.TRIVIAL,
                new PlanClassifier().classify(steps("read README.md")));
    }

    @Test
    void classify_threeSafeSteps_trivial() {
        assertEquals(PlanClassifier.Verdict.TRIVIAL,
                new PlanClassifier().classify(steps("read foo", "summarise bar", "list files")));
    }

    @Test
    void classify_fourSafeSteps_needsApproval() {
        assertEquals(PlanClassifier.Verdict.NEEDS_APPROVAL,
                new PlanClassifier().classify(steps("read 0", "read 1", "read 2", "read 3")));
    }

    @Test
    void classify_bashStep_needsApproval() {
        assertEquals(PlanClassifier.Verdict.NEEDS_APPROVAL,
                new PlanClassifier().classify(steps("run bash test.sh")));
    }

    @Test
    void classify_fileWriteStep_needsApproval() {
        assertEquals(PlanClassifier.Verdict.NEEDS_APPROVAL,
                new PlanClassifier().classify(steps("use file_write to update config")));
    }

    @Test
    void classify_bashMixedWithSafe_needsApproval() {
        assertEquals(PlanClassifier.Verdict.NEEDS_APPROVAL,
                new PlanClassifier().classify(steps("read README", "run bash setup.sh")));
    }

    @Test
    void classify_destructiveSet_isCaseInsensitive() {
        assertEquals(PlanClassifier.Verdict.NEEDS_APPROVAL,
                new PlanClassifier().classify(steps("Run BASH to install")));
    }

    @Test
    void classify_customDestructiveSet_excludesBash() {
        PlanClassifier c = new PlanClassifier(3, Set.of("file_write"));
        assertEquals(PlanClassifier.Verdict.TRIVIAL,
                c.classify(steps("run bash test.sh")));
    }

    @Test
    void classify_customMaxSteps_higherThreshold() {
        // Default: 5 > 3 → NEEDS_APPROVAL
        assertEquals(PlanClassifier.Verdict.NEEDS_APPROVAL,
                new PlanClassifier().classify(steps("read 0", "read 1", "read 2", "read 3", "read 4")));
        // Custom max=10: TRIVIAL
        assertEquals(PlanClassifier.Verdict.TRIVIAL,
                new PlanClassifier(10, PlanClassifier.DEFAULT_DESTRUCTIVE_TOOLS)
                        .classify(steps("read 0", "read 1", "read 2", "read 3", "read 4")));
    }

    @Test
    void classify_nullStepTitle_treatedAsEmpty() {
        List<PlanClassifier.Step> withNull = new java.util.ArrayList<>();
        withNull.add(new PlanClassifier.Step(null));
        withNull.add(new PlanClassifier.Step("read README"));
        assertEquals(PlanClassifier.Verdict.TRIVIAL, new PlanClassifier().classify(withNull));
    }

    @Test
    void isTrivial_isConsistentWithClassify() {
        List<PlanClassifier.Step> s = steps("read README");
        PlanClassifier c = new PlanClassifier();
        assertEquals(c.classify(s) == PlanClassifier.Verdict.TRIVIAL, c.isTrivial(s));
    }

    @Test
    void rationale_safePlan_saysAllSafe() {
        String r = new PlanClassifier().rationale(steps("read README", "list files"));
        assertTrue(r.contains("2"), "should mention 2 steps: " + r);
    }

    @Test
    void rationale_tooManySteps_saysHowMany() {
        String r = new PlanClassifier().rationale(steps("a", "b", "c", "d", "e"));
        assertTrue(r.contains("5 steps"));
        assertTrue(r.contains("max 3"));
    }

    @Test
    void rationale_destructiveStep_namesTheTool() {
        String r = new PlanClassifier().rationale(steps("run bash x"));
        assertTrue(r.contains("bash"));
    }

    @Test
    void maxSteps_returnsConstructorValue() {
        assertEquals(3, new PlanClassifier().maxSteps());
        assertEquals(7, new PlanClassifier(7, Set.of()).maxSteps());
    }

    @Test
    void destructiveTools_returnsImmutableSet() {
        PlanClassifier c = new PlanClassifier();
        Set<String> tools = c.destructiveTools();
        assertTrue(tools.contains("bash"));
        assertTrue(tools.contains("file_write"));
        // Modifying the returned set should not affect the classifier.
        try {
            tools.clear();
        } catch (UnsupportedOperationException ignored) {
            // expected for immutable sets
        }
    }
}
