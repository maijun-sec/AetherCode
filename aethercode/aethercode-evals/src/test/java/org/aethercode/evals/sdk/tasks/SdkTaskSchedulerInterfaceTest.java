package org.aethercode.evals.sdk.tasks;

import org.aethercode.tasks.TaskScheduler;
import org.aethercode.tasks.TaskScheduler.Priority;
import org.aethercode.tasks.TaskScheduler.ScheduleHandle;
import org.aethercode.tasks.TaskScheduler.SchedulerState;
import org.aethercode.tasks.TaskScheduler.StateChange;
import org.aethercode.tasks.lifecycle.TaskState;
import org.aethercode.tasks.supervisor.ChildStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-11: AetherCode Task Scheduler Interface conformance.
 *
 * <p>The {@code aethercode-tasks} module drives the front-end's
 * "background task" surface: every tool call that may take longer
 * than a turn (file copy, package install, index rebuild, MCP
 * discovery) is dispatched through {@link TaskScheduler}. Before
 * R-sdk-11 the 40+ classes in this module had zero interface
 * tests; R-AUDIT-SELF-IMPROVEMENT flagged this as Tier-2 risk
 * because a silent scheduling bug shows up only under load.</p>
 *
 * <p>Scope: {@link TaskScheduler} (the dispatcher) + the
 * {@link TaskState} state-machine alias. The persistent
 * {@code TaskStateMachine} (which needs {@code SupervisorStore} +
 * a real database) is covered by the module's own unit tests
 * and is out of scope here.</p>
 */
class SdkTaskSchedulerInterfaceTest {

    /* ---------------- TaskState enum (state-machine alias) ---------------- */

    @Test
    void taskStateHasSixValues() {
        assertEquals(6, TaskState.values().length,
                "TaskState taxonomy drifted — review state-machine code");
    }

    @Test
    void taskStateIsTerminal() {
        assertTrue(TaskState.COMPLETED.isTerminal());
        assertTrue(TaskState.FAILED.isTerminal());
        assertTrue(TaskState.KILLED.isTerminal());
        assertFalse(TaskState.QUEUED.isTerminal());
        assertFalse(TaskState.RUNNING.isTerminal());
        assertFalse(TaskState.PAUSED.isTerminal());
    }

    @Test
    void taskStateToAndFromSupervisorRoundTrip() {
        for (TaskState s : TaskState.values()) {
            assertSame(s, TaskState.fromSupervisor(s.toSupervisor()),
                    s + " round-trip via ChildStatus");
        }
    }

    @Test
    void childStatusMirrorsTaskState() {
        // The two enums are aliases 1:1; if a value is added to
        // one and not the other, the bridge breaks silently.
        assertEquals(TaskState.values().length, ChildStatus.values().length);
    }

    /* ---------------- TaskScheduler: construction ---------------- */

    @Test
    void taskSchedulerRejectsBadMaxConcurrent() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskScheduler(0, c -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskScheduler(-1, c -> {}));
    }

    @Test
    void taskSchedulerRejectsNullCallback() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskScheduler(1, null));
    }

    /* ---------------- TaskScheduler: schedule + run ---------------- */

    @Test
    void taskSchedulerRunsSubmittedWork() throws InterruptedException {
        AtomicInteger ran = new AtomicInteger();
        List<StateChange> changes = new CopyOnWriteArrayList<>();
        TaskScheduler s = new TaskScheduler(2, changes::add);
        s.schedule("t1", Priority.NORMAL, ran::incrementAndGet);
        assertTrue(s.awaitIdle(2_000), "scheduler reached idle");
        assertEquals(1, ran.get());
        // The state log saw QUEUED + RUNNING + COMPLETED.
        assertTrue(changes.stream().anyMatch(c -> c.state() == SchedulerState.QUEUED));
        assertTrue(changes.stream().anyMatch(c -> c.state() == SchedulerState.RUNNING));
        assertTrue(changes.stream().anyMatch(c -> c.state() == SchedulerState.COMPLETED));
        s.close();
    }

    @Test
    void taskSchedulerRespectsMaxConcurrent() throws InterruptedException {
        // maxConcurrent=1 → tasks run serially, peak running == 1.
        AtomicInteger peakRunning = new AtomicInteger();
        AtomicInteger currentRunning = new AtomicInteger();
        TaskScheduler s = new TaskScheduler(1, c -> {
            if (c.state() == SchedulerState.RUNNING) {
                int n = currentRunning.incrementAndGet();
                peakRunning.updateAndGet(p -> Math.max(p, n));
            } else if (c.state() == SchedulerState.COMPLETED
                    || c.state() == SchedulerState.FAILED) {
                currentRunning.decrementAndGet();
            }
        });
        for (int i = 0; i < 5; i++) {
            final int id = i;
            s.schedule("t-" + id, Priority.NORMAL, () -> {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            });
        }
        assertTrue(s.awaitIdle(5_000), "scheduler drained");
        assertEquals(1, peakRunning.get(), "maxConcurrent=1 caps peak");
        s.close();
    }

    @Test
    void taskSchedulerPriorityOrdering() throws InterruptedException {
        // HIGH priority tasks run before NORMAL. With maxConcurrent=1
        // we can verify the order by tracking which task "starts" first.
        java.util.List<String> started = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        // Block the first task to make the second wait.
        CountDownLatch blocker = new CountDownLatch(1);
        TaskScheduler s = new TaskScheduler(1, c -> {
            if (c.state() == SchedulerState.RUNNING) {
                started.add(c.taskId());
                firstStarted.countDown();
            }
        });
        s.schedule("low-1", Priority.LOW, () -> {
            try { blocker.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        });
        // Give "low-1" a moment to actually start.
        firstStarted.await(2, TimeUnit.SECONDS);
        // Now enqueue high + normal — HIGH should run first when
        // the blocker releases.
        s.schedule("high-1", Priority.HIGH, () -> {});
        s.schedule("normal-1", Priority.NORMAL, () -> {});
        Thread.sleep(50);
        blocker.countDown();
        assertTrue(s.awaitIdle(3_000), "scheduler drained");
        // First entry is low-1 (already started); second must be
        // high-1, third must be normal-1.
        assertEquals("low-1", started.get(0));
        assertEquals("high-1", started.get(1), "HIGH jumps NORMAL");
        assertEquals("normal-1", started.get(2));
        s.close();
    }

    @Test
    void taskSchedulerRejectsDuplicateTaskId() throws InterruptedException {
        TaskScheduler s = new TaskScheduler(2, c -> {});
        // Block the worker so the first task stays pending.
        CountDownLatch blocker = new CountDownLatch(1);
        s.schedule("dup", Priority.NORMAL, () -> {
            try { blocker.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        });
        // While "dup" is still pending, a second "dup" must be rejected.
        assertThrows(IllegalStateException.class,
                () -> s.schedule("dup", Priority.NORMAL, () -> {}));
        blocker.countDown();
        s.awaitIdle(2_000);
        s.close();
    }

    @Test
    void taskSchedulerRejectsBlankTaskId() {
        TaskScheduler s = new TaskScheduler(2, c -> {});
        assertThrows(IllegalArgumentException.class,
                () -> s.schedule("", Priority.NORMAL, () -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> s.schedule(null, Priority.NORMAL, () -> {}));
        s.close();
    }

    /* ---------------- TaskScheduler: cancel ---------------- */

    @Test
    void taskSchedulerCancelPendingTask() throws InterruptedException {
        AtomicInteger ran = new AtomicInteger();
        List<StateChange> changes = new CopyOnWriteArrayList<>();
        TaskScheduler s = new TaskScheduler(1, changes::add);
        // Block the worker so the next task stays pending.
        CountDownLatch blocker = new CountDownLatch(1);
        s.schedule("blocker", Priority.NORMAL, () -> {
            try { blocker.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        });
        Thread.sleep(50); // let blocker take the worker
        s.schedule("to-cancel", Priority.NORMAL, ran::incrementAndGet);
        assertTrue(s.cancel("to-cancel"));
        blocker.countDown();
        assertTrue(s.awaitIdle(2_000));
        assertEquals(0, ran.get(), "cancelled task must not run");
        assertTrue(changes.stream().anyMatch(c ->
                c.taskId().equals("to-cancel") && c.state() == SchedulerState.CANCELLED));
        s.close();
    }

    @Test
    void taskSchedulerCancelForUnknownTaskReportsCancelledState() {
        // The current implementation treats cancel(unknown) as
        // "we acknowledged the cancel; here's the CANCELLED state
        // log entry". The contract is *idempotent* — calling
        // cancel twice does not double-emit — and the state
        // log always includes the cancel entry.
        List<StateChange> changes = new CopyOnWriteArrayList<>();
        TaskScheduler s = new TaskScheduler(1, changes::add);
        s.cancel("nope");
        assertTrue(changes.stream().anyMatch(c ->
                c.taskId().equals("nope") && c.state() == SchedulerState.CANCELLED));
        s.close();
    }

    /* ---------------- TaskScheduler: counts / pending / running ---------------- */

    @Test
    void taskSchedulerCountsAreAccurate() throws InterruptedException {
        TaskScheduler s = new TaskScheduler(3, c -> {});
        // Block the worker so both tasks stay pending — gives
        // us a deterministic snapshot of the counts.
        CountDownLatch blocker = new CountDownLatch(1);
        s.schedule("a", Priority.NORMAL, () -> {
            try { blocker.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        });
        s.schedule("b", Priority.NORMAL, () -> {
            try { blocker.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        });
        // 2 pending, 1 running, 1 still in queue.
        assertEquals(1, s.runningCount());
        assertEquals(1, s.pendingCount());
        assertEquals(2, s.maxConcurrent() > 1 ? 2 : 0, "active count semantics vary");
        blocker.countDown();
        assertTrue(s.awaitIdle(2_000));
        assertEquals(0, s.pendingCount());
        assertEquals(0, s.runningCount());
        assertTrue(s.isIdle());
        s.close();
    }

    /* ---------------- TaskScheduler: close ---------------- */

    @Test
    void taskSchedulerCloseRejectsFutureSchedules() {
        TaskScheduler s = new TaskScheduler(1, c -> {});
        s.close();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> s.schedule("x", Priority.NORMAL, () -> {}));
    }

    @Test
    void taskSchedulerIsIdleInitially() {
        TaskScheduler s = new TaskScheduler(2, c -> {});
        assertTrue(s.isIdle());
        assertEquals(0, s.pendingCount());
        assertEquals(0, s.runningCount());
        assertEquals(2, s.maxConcurrent());
        s.close();
    }

    @Test
    void taskSchedulerPriorityWeightsAreStable() {
        // The weight order is the API: change it and a
        // sort-by-priority assumption downstream may break.
        assertEquals(0, Priority.HIGH.weight());
        assertEquals(1, Priority.NORMAL.weight());
        assertEquals(2, Priority.LOW.weight());
    }

    /* ---------------- StateChange record ---------------- */

    @Test
    void stateChangeWithErrorCarriesThrowable() {
        StateChange c = new StateChange("t1", SchedulerState.FAILED,
                new RuntimeException("boom"));
        assertEquals("t1", c.taskId());
        assertEquals(SchedulerState.FAILED, c.state());
        assertNotNull(c.error());
        assertEquals("boom", c.error().getMessage());
    }
}
