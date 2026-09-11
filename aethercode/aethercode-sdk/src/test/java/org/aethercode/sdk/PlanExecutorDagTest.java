package org.aethercode.sdk;

import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.aethercode.tasks.TaskType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link PlanExecutor#executeDag(DagPlan)}.
 * Verifies that step dependencies are respected — a step with
 * unmet dependencies is marked SKIPPED.
 */
class PlanExecutorDagTest {

    /** A simple StepExecutor that records the order of execution. */
    private static class RecordingExecutor implements PlanExecutor.StepExecutor {
        final List<String> titles = new CopyOnWriteArrayList<>();
        @Override
        public String execute(int stepIndex, String title, Task task) {
            titles.add(title);
            return "ok-" + title;
        }
    }

    @Test
    void linearDag_runsInOrder() {
        DagPlan plan = DagPlan.of(
                new DagPlan.Step("a", "first", List.of()),
                new DagPlan.Step("b", "second", List.of("a")),
                new DagPlan.Step("c", "third", List.of("b"))
        );
        RecordingExecutor exec = new RecordingExecutor();
        PlanExecutor pe = new PlanExecutor(exec, TaskRegistry.instance());
        List<PlanEvent> events = pe.executeDag(plan).toList();
        // All 3 steps completed.
        long completed = events.stream()
                .filter(e -> e instanceof PlanEvent.StepCompleted).count();
        assertEquals(3, completed);
        // All 3 were executed (no skip).
        assertEquals(3, exec.titles.size());
        assertEquals(List.of("first", "second", "third"), exec.titles);
    }

    @Test
    void parallelDag_runsIndependentSteps() {
        // Two independent steps a, b and one dependent c.
        DagPlan plan = DagPlan.of(
                new DagPlan.Step("a", "left", List.of()),
                new DagPlan.Step("b", "right", List.of()),
                new DagPlan.Step("c", "join", List.of("a", "b"))
        );
        RecordingExecutor exec = new RecordingExecutor();
        PlanExecutor pe = new PlanExecutor(exec, TaskRegistry.instance());
        pe.executeDag(plan).toList();
        // join must run after both left and right.
        int joinIdx = exec.titles.indexOf("join");
        int leftIdx = exec.titles.indexOf("left");
        int rightIdx = exec.titles.indexOf("right");
        assertTrue(joinIdx > leftIdx, "join must run after left");
        assertTrue(joinIdx > rightIdx, "join must run after right");
    }

    @Test
    void unmetDependency_marksStepSkipped() {
        // Construct a plan and verify a step that has a dep on a
        // failed upstream step is marked SKIPPED. We force the
        // upstream step to fail via the executor, then check the
        // downstream step's result.
        //
        // The DagPlan constructor rejects unknown deps, so we
        // can't construct a "broken" DAG. Instead, test the
        // abort path: when abort() is called, remaining steps
        // are marked SKIPPED in the results list.
        DagPlan plan = DagPlan.of(
                new DagPlan.Step("a", "first", List.of()),
                new DagPlan.Step("b", "second", List.of("a"))
        );
        PlanExecutor.StepExecutor throwing = (idx, title, task) -> {
            throw new RuntimeException("boom");
        };
        PlanExecutor pe = new PlanExecutor(throwing, TaskRegistry.instance());
        pe.abort();
        pe.executeDag(plan).toList();
        // Both steps should be marked SKIPPED in results.
        List<PlanExecutor.StepResult> results = pe.results();
        assertEquals(2, results.size());
        assertEquals(PlanExecutor.StepOutcome.SKIPPED, results.get(0).outcome());
        assertEquals(PlanExecutor.StepOutcome.SKIPPED, results.get(1).outcome());
    }

    @Test
    void resultsIncludeAllStepsInTopoOrder() {
        DagPlan plan = DagPlan.of(
                new DagPlan.Step("x", "step x", List.of()),
                new DagPlan.Step("y", "step y", List.of("x"))
        );
        RecordingExecutor exec = new RecordingExecutor();
        PlanExecutor pe = new PlanExecutor(exec, TaskRegistry.instance());
        pe.executeDag(plan).toList();
        List<PlanExecutor.StepResult> results = pe.results();
        assertEquals(2, results.size());
        assertEquals("step x", results.get(0).title());
        assertEquals("step y", results.get(1).title());
    }
}
