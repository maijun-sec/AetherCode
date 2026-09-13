package org.aethercode.orchestration.verifier;

import org.aethercode.orchestration.verifier.WorkflowEval.Edge;
import org.aethercode.orchestration.verifier.WorkflowEval.Step;
import org.aethercode.orchestration.verifier.WorkflowEval.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEvalTest {

    private final WorkflowEval eval = new WorkflowEval();

    @Test
    void identicalWorkflowsScorePerfect() {
        Workflow a = Workflow.of("a", "b", "c");
        Workflow b = Workflow.of("a", "b", "c");
        var s = eval.evaluate(a, b);
        assertEquals(1.0, s.holistic());
        assertEquals(1.0, s.subsequence());
        assertEquals(1.0, s.subgraph());
    }

    @Test
    void differentOrderFailsHolisticButPartiallyMatchesSubsequence() {
        Workflow ref = Workflow.of("a", "b", "c", "d");
        Workflow cand = Workflow.of("a", "b", "x", "d");
        var s = eval.evaluate(ref, cand);
        assertEquals(0.0, s.holistic());
        // "a","b" is a 2-run matching; "d" is a 1-run matching; best is 2 of 4
        assertEquals(0.5, s.subsequence());
        assertEquals(1.0, s.subgraph());
    }

    @Test
    void missingEdgeCountsAsSubgraphMiss() {
        Workflow ref = new Workflow(
            List.of(new Step("a"), new Step("b"), new Step("c")),
            List.of(new Edge("a", "b"), new Edge("b", "c"))
        );
        Workflow cand = new Workflow(
            List.of(new Step("a"), new Step("b"), new Step("c")),
            List.of(new Edge("a", "b"))  // missing b->c
        );
        var s = eval.evaluate(ref, cand);
        assertEquals(0.0, s.holistic());  // different edge lists
        assertEquals(1.0, s.subsequence());
        assertEquals(0.5, s.subgraph());
    }

    @Test
    void compositeWeighting() {
        Workflow ref = Workflow.of("a", "b", "c");
        Workflow cand = Workflow.of("a", "b", "c");
        var s = eval.evaluate(ref, cand);
        assertEquals(1.0, s.composite());
    }

    @Test
    void emptyWorkflowsScoreZero() {
        Workflow ref = Workflow.of("a", "b");
        Workflow cand = Workflow.of();
        var s = eval.evaluate(ref, cand);
        assertEquals(0.0, s.holistic());
        assertEquals(0.0, s.subsequence());
    }
}
