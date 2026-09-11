package org.aethercode.tasks;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link TaskSchedulerBridge}. Verifies the
 * scheduler state mapping drives the registry correctly:
 * QUEUED → PENDING (no-op), RUNNING → RUNNING, COMPLETED → COMPLETED,
 * FAILED → FAILED, CANCELLED → KILLED.
 */
class TaskSchedulerBridgeTest {

    @Test
    void bridge_runsTaskAndUpdatesRegistryToCompleted() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(1, TaskSchedulerBridge.toRegistry(registry));
        Task task = registry.create(TaskType.AGENT, "do work", null);
        // Task is PENDING.
        assertEquals(TaskStatus.PENDING, task.status());

        AtomicInteger ran = new AtomicInteger(0);
        sched.schedule(task.id(), TaskScheduler.Priority.NORMAL, ran::incrementAndGet);
        assertTrue(sched.awaitIdle(2000));
        assertEquals(1, ran.get());
        // Registry should have transitioned PENDING → RUNNING → COMPLETED.
        Task finalState = registry.get(task.id()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, finalState.status());
        sched.close();
    }

    @Test
    void bridge_failedTaskSetsFailed() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(1, TaskSchedulerBridge.toRegistry(registry));
        Task task = registry.create(TaskType.AGENT, "boom", null);
        sched.schedule(task.id(), TaskScheduler.Priority.NORMAL, () -> {
            throw new RuntimeException("nope");
        });
        assertTrue(sched.awaitIdle(2000));
        assertEquals(TaskStatus.FAILED, registry.get(task.id()).orElseThrow().status());
        sched.close();
    }

    @Test
    void bridge_cancelledPendingTaskSetsKilled() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(1, TaskSchedulerBridge.toRegistry(registry));
        Task blocker = registry.create(TaskType.AGENT, "block", null);
        Task doomed = registry.create(TaskType.AGENT, "doomed", null);
        sched.schedule(blocker.id(), TaskScheduler.Priority.NORMAL, () -> sleep(150));
        sched.schedule(doomed.id(), TaskScheduler.Priority.NORMAL, () -> {});
        Thread.sleep(30);
        sched.cancel(doomed.id());
        assertTrue(sched.awaitIdle(2000));
        assertEquals(TaskStatus.KILLED, registry.get(doomed.id()).orElseThrow().status());
        // Blocker ran to completion.
        assertEquals(TaskStatus.COMPLETED, registry.get(blocker.id()).orElseThrow().status());
        sched.close();
    }

    @Test
    void bridge_unknownTaskIdDoesNotThrow() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(1, TaskSchedulerBridge.toRegistry(registry));
        // Schedule with a taskId that's not in the registry. The bridge
        // swallows the "unknown task" exception.
        sched.schedule("ghost", TaskScheduler.Priority.NORMAL, () -> {});
        assertTrue(sched.awaitIdle(2000));
        // No exception thrown — bridge is best-effort.
        sched.close();
    }

    @Test
    void bridge_factoryRejectsNullRegistry() {
        assertThrows(IllegalArgumentException.class, () -> TaskSchedulerBridge.toRegistry(null));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
