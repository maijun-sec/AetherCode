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
