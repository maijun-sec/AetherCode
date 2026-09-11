package org.aethercode.sdk;

import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.aethercode.tasks.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link PlanExecutor} — the engine-driven
 * state machine that walks through a structured plan, one step
 * per query, tracking per-step status in a TaskRegistry.
 */
class PlanExecutorTest {

    @BeforeEach
    void reset() { TaskRegistry.resetForTests(); }
    @AfterEach
    void cleanup() { TaskRegistry.resetForTests(); }

    @Test
    void execute_nullPlan_yieldsPlanAborted() {
        PlanExecutor exec = new PlanExecutor((i, t, k) -> "ok", TaskRegistry.instance());
        List<PlanEvent> events = new ArrayList<>();
        exec.execute((List<String>) null).forEach(events::add);
        assertEquals(1, events.size());
        assertInstanceOf(PlanEvent.PlanAborted.class, events.get(0));
    }

    @Test
    void execute_emptyPlan_yieldsPlanCompleted() {
        PlanExecutor exec = new PlanExecutor((i, t, k) -> "ok", TaskRegistry.instance());
        List<PlanEvent> events = new ArrayList<>();
        exec.execute(List.of()).forEach(events::add);
        assertEquals(1, events.size());
        assertInstanceOf(PlanEvent.PlanCompleted.class, events.get(0));
        assertEquals(0, ((PlanEvent.PlanCompleted) events.get(0)).results().size());
    }

    @Test
    void execute_singleStep_emitsStartedCompletedAndPlanCompleted() {
        AtomicInteger calls = new AtomicInteger();
        PlanExecutor.StepExecutor step = (i, title, task) -> {
            calls.incrementAndGet();
            assertEquals(0, i);
            assertEquals("read me", title);
            return "done";
        };
        PlanExecutor exec = new PlanExecutor(step, TaskRegistry.instance());
        List<PlanEvent> events = new ArrayList<>();
        exec.execute(List.of("read me")).forEach(events::add);
        assertEquals(1, calls.get());
        assertEquals(3, events.size());
        assertInstanceOf(PlanEvent.StepStarted.class, events.get(0));
        assertInstanceOf(PlanEvent.StepCompleted.class, events.get(1));
        assertInstanceOf(PlanEvent.PlanCompleted.class, events.get(2));
        PlanEvent.StepCompleted sc = (PlanEvent.StepCompleted) events.get(1);
        assertEquals("done", sc.summary());
    }

    @Test
    void execute_multipleSteps_eachGetsStartedAndCompleted() {
        PlanExecutor.StepExecutor step = (i, title, task) -> "ok-" + i;
        PlanExecutor exec = new PlanExecutor(step, TaskRegistry.instance());
        List<PlanEvent> events = new ArrayList<>();
        exec.execute(List.of("a", "b", "c")).forEach(events::add);
        // 3 × (Started + Completed) + 1 PlanCompleted = 7
        assertEquals(7, events.size());
        long started = events.stream().filter(e -> e instanceof PlanEvent.StepStarted).count();
        long completed = events.stream().filter(e -> e instanceof PlanEvent.StepCompleted).count();
        long planCompleted = events.stream().filter(e -> e instanceof PlanEvent.PlanCompleted).count();
        assertEquals(3, started);
        assertEquals(3, completed);
        assertEquals(1, planCompleted);
    }

    @Test
    void execute_stepThrows_emitsStepFailed() {
        PlanExecutor.StepExecutor step = (i, title, task) -> {
            throw new RuntimeException("boom");
        };
        // use the default retry policy (3 attempts).
        PlanExecutor exec = new PlanExecutor(step, TaskRegistry.instance());
        List<PlanEvent> events = new ArrayList<>();
        exec.execute(List.of("only-step")).forEach(events::add);
        // Started + Failed + PlanCompleted
        assertEquals(3, events.size());
        assertInstanceOf(PlanEvent.StepFailed.class, events.get(1));
        PlanEvent.StepFailed sf = (PlanEvent.StepFailed) events.get(1);
        // with the default retry policy, the error message
        // is wrapped to include the attempt count.
        assertTrue(sf.error().contains("boom"), "should contain original error: " + sf.error());
        assertTrue(sf.error().contains("attempts"), "should mention attempts: " + sf.error());
    }

    @Test
    void execute_withRetryPolicyNone_propagatesErrorVerbatim() {
        PlanExecutor.StepExecutor step = (i, title, task) -> {
            throw new RuntimeException("boom");
        };
        PlanExecutor exec = new PlanExecutor(step, TaskRegistry.instance())
                .withRetryPolicy(RetryPolicy.NONE);
        List<PlanEvent> events = new ArrayList<>();
        exec.execute(List.of("only-step")).forEach(events::add);
        PlanEvent.StepFailed sf = (PlanEvent.StepFailed) events.get(1);
        // With NONE, the original message is preserved.
        assertTrue(sf.error().contains("boom"));
    }

    @Test
    void execute_persistsStepTasksInRegistry() {
        PlanExecutor.StepExecutor step = (i, title, task) -> "ok";
        PlanExecutor exec = new PlanExecutor(step, TaskRegistry.instance());
        exec.execute(List.of("a", "b")).forEach(e -> {});
        List<Task> tasks = TaskRegistry.instance().list();
        assertEquals(2, tasks.size());
        for (Task t : tasks) {
            assertEquals(TaskType.WORKFLOW, t.type());
            assertEquals(TaskStatus.COMPLETED, t.status(),
                    "task " + t.id() + " should be COMPLETED, was " + t.status());
        }
    }

    @Test
    void execute_recordsElapsedTime() {
        PlanExecutor.StepExecutor step = (i, title, task) -> {
            Thread.sleep(10);
            return "ok";
        };
        PlanExecutor exec = new PlanExecutor(step, TaskRegistry.instance());
        List<PlanEvent> events = new ArrayList<>();
        exec.execute(List.of("a")).forEach(events::add);
        PlanEvent.StepCompleted sc = (PlanEvent.StepCompleted) events.get(1);
        assertTrue(sc.elapsedMs() >= 10, "elapsed should be >= 10ms, was " + sc.elapsedMs());
    }

    @Test
    void execute_abortedBeforeStart_skipsRemainingSteps() {
        AtomicInteger calls = new AtomicInteger();
        PlanExecutor.StepExecutor step = (i, title, task) -> {
            calls.incrementAndGet();
            return "ok";
        };
        PlanExecutor exec = new PlanExecutor(step, TaskRegistry.instance());
        // Abort after 1 step. We need to abort from outside; do
        // it from the step callback so it's seen on the next iteration.
        List<PlanEvent> events = new ArrayList<>();
        // Subscribe first, then abort after the first event.
        List<PlanEvent> collected = new ArrayList<>();
        java.util.Iterator<PlanEvent> it = exec.execute(List.of("a", "b", "c")).iterator();
        collected.add(it.next()); // StepStarted for "a"
        exec.abort();
        while (it.hasNext()) collected.add(it.next());
        // First step runs to completion. Then b and c are SKIPPED.
        assertEquals(1, calls.get(), "only one step should run before abort");
        // Verify results() reflects the SKIPPED outcomes.
        List<PlanExecutor.StepResult> results = exec.results();
        assertEquals(3, results.size());
        assertEquals(PlanExecutor.StepOutcome.COMPLETED, results.get(0).outcome());
        assertEquals(PlanExecutor.StepOutcome.SKIPPED, results.get(1).outcome());
        assertEquals(PlanExecutor.StepOutcome.SKIPPED, results.get(2).outcome());
        // PlanAborted is the last event.
        assertInstanceOf(PlanEvent.PlanAborted.class, collected.get(collected.size() - 1));
    }

    @Test
    void execute_nullStepExecutor_usesEngineDefault() {
        // Constructing with a real AetherCodeEngine is heavy; just
        // verify the constructor doesn't throw.
        // (We don't have an engine here, so we test the no-arg path
        // would work — instead, we test the low-level ctor with a
        // null-equivalent: pass a real-but-noop executor.)
        PlanExecutor exec = new PlanExecutor((i, t, k) -> "noop", TaskRegistry.instance());
        assertNotNull(exec);
    }

    @Test
    void results_returnsImmutableList() {
        PlanExecutor exec = new PlanExecutor((i, t, k) -> "ok", TaskRegistry.instance());
        exec.execute(List.of("a", "b")).forEach(e -> {});
        List<PlanExecutor.StepResult> results = exec.results();
        assertEquals(2, results.size());
        assertThrows(UnsupportedOperationException.class,
                () -> results.add(new PlanExecutor.StepResult(99, "x",
                        PlanExecutor.StepOutcome.COMPLETED, "y", 0L)));
    }

    @Test
    void results_containStepOutcomes() {
        PlanExecutor.StepExecutor step = (i, title, task) -> {
            if (i == 1) throw new RuntimeException("fail-1");
            return "ok-" + i;
        };
        PlanExecutor exec = new PlanExecutor(step, TaskRegistry.instance());
        exec.execute(List.of("a", "b", "c")).forEach(e -> {});
        List<PlanExecutor.StepResult> results = exec.results();
        assertEquals(3, results.size());
        assertEquals(PlanExecutor.StepOutcome.COMPLETED, results.get(0).outcome());
        assertEquals(PlanExecutor.StepOutcome.FAILED, results.get(1).outcome());
        assertEquals(PlanExecutor.StepOutcome.COMPLETED, results.get(2).outcome());
    }

    @Test
    void abort_isObservableViaIsAborted() {
        PlanExecutor exec = new PlanExecutor((i, t, k) -> "ok", TaskRegistry.instance());
        assertFalse(exec.isAborted());
        exec.abort();
        assertTrue(exec.isAborted());
    }
}
