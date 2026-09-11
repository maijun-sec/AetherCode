package org.aethercode.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link TaskScheduler}. Focuses on the 6 core
 * behaviors — schedule runs, priority dispatch, FIFO within priority,
 * pending cancellation, awaitIdle, listener events.
 */
class TaskSchedulerTest {

    private TaskScheduler sched;
    private List<TaskScheduler.StateChange> events;

    @BeforeEach
    void setUp() {
        events = new CopyOnWriteArrayList<>();
        sched = new TaskScheduler(2, events::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sched != null) sched.close();
    }

    @Test
    void schedule_runsAndEmitsLifecycle() throws Exception {
        AtomicInteger ran = new AtomicInteger(0);
        sched.schedule("t1", TaskScheduler.Priority.NORMAL, ran::incrementAndGet);
        assertTrue(sched.awaitIdle(2000), "task must complete within 2s");
        assertEquals(1, ran.get());
        // Must have emitted QUEUED, RUNNING, COMPLETED for t1.
        assertEquals(3, events.size());
        assertEquals(TaskScheduler.SchedulerState.QUEUED, events.get(0).state());
        assertEquals(TaskScheduler.SchedulerState.RUNNING, events.get(1).state());
        assertEquals(TaskScheduler.SchedulerState.COMPLETED, events.get(2).state());
    }

    @Test
    void schedule_dispatchesHighBeforeLowInQueue() {
        // The PriorityBlockingQueue's order is determined by the
        // comparator. With 2 items in the queue simultaneously, the
        // higher-priority one is dequeued first. We test the
        // comparator directly because end-to-end testing requires
        // racing the workers (which can't queue items faster than
        // they dequeue them in unit-test conditions).
        var low = new TaskScheduler.ScheduledItem("low",
                TaskScheduler.Priority.LOW, 1L, 1, () -> {});
        var high = new TaskScheduler.ScheduledItem("high",
                TaskScheduler.Priority.HIGH, 2L, 2, () -> {});
        var c = TaskScheduler.itemOrder();
        // high.priority < low.priority by weight → high comes first
        assertTrue(c.compare(high, low) < 0,
                "HIGH priority must sort before LOW (negative compare result)");
        assertTrue(c.compare(low, high) > 0,
                "LOW priority must sort after HIGH (positive compare result)");
    }

    @Test
    void schedule_fifoWithinSamePriorityViaComparator() {
        // Same priority → fall back to submittedAtMs (FIFO), then sequence.
        var a = new TaskScheduler.ScheduledItem("a",
                TaskScheduler.Priority.NORMAL, 1L, 1, () -> {});
        var b = new TaskScheduler.ScheduledItem("b",
                TaskScheduler.Priority.NORMAL, 2L, 2, () -> {});
        var c = TaskScheduler.itemOrder();
        assertTrue(c.compare(a, b) < 0, "earlier submittedAtMs must come first");
    }

    @Test
    void schedule_fifoWithinSamePriority() throws Exception {
        sched.close();
        events.clear();
        sched = new TaskScheduler(1, events::add);
        sched.schedule("a", TaskScheduler.Priority.NORMAL, () -> sleep(10));
        sched.schedule("b", TaskScheduler.Priority.NORMAL, () -> sleep(10));
        sched.schedule("c", TaskScheduler.Priority.NORMAL, () -> sleep(10));
        assertTrue(sched.awaitIdle(3000));
        int aRun = indexOfRun("a");
        int bRun = indexOfRun("b");
        int cRun = indexOfRun("c");
        assertTrue(aRun > 0 && bRun > 0 && cRun > 0, "all three must have RUNNING events");
        assertTrue(aRun < bRun && bRun < cRun, "expected FIFO order a→b→c, got "
                + aRun + "/" + bRun + "/" + cRun);
    }

    @Test
    void cancel_pendingTaskDoesNotRun() throws Exception {
        // Single worker so "doomed" stays pending until "blocker" finishes.
        sched.close();
        events.clear();
        sched = new TaskScheduler(1, events::add);
        sched.schedule("blocker", TaskScheduler.Priority.NORMAL, () -> sleep(150));
        AtomicInteger ran = new AtomicInteger(0);
        sched.schedule("doomed", TaskScheduler.Priority.NORMAL, ran::incrementAndGet);
        // Wait until blocker is running (poll, don't hard-sleep).
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && sched.runningCount() == 0) {
            sleep(5);
        }
        assertEquals(1, sched.runningCount(), "only blocker should be running");
        assertTrue(sched.cancel("doomed"));
        assertTrue(sched.awaitIdle(2000));
        assertEquals(0, ran.get(), "cancelled task must not have run");
        boolean sawCancelled = events.stream()
                .anyMatch(e -> "doomed".equals(e.taskId())
                        && e.state() == TaskScheduler.SchedulerState.CANCELLED);
        assertTrue(sawCancelled, "expected CANCELLED event for doomed task");
    }

    @Test
    void failedTaskEmitsFailedWithError() throws Exception {
        java.util.concurrent.CountDownLatch failedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.List<TaskScheduler.StateChange> sink = new java.util.concurrent.CopyOnWriteArrayList<>();
        TaskScheduler live = new TaskScheduler(1, ev -> {
            sink.add(ev);
            if (ev.state() == TaskScheduler.SchedulerState.FAILED) failedLatch.countDown();
        });
        try {
            live.schedule("boom", TaskScheduler.Priority.NORMAL, () -> {
                throw new IllegalStateException("kaboom");
            });
            // Wait for the FAILED event to be observed by the listener
            // before reading — avoids the race where awaitIdle returns
            // before the listener has been invoked.
            assertTrue(failedLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "FAILED event should fire within 2s; sink=" + sink);
            TaskScheduler.StateChange failed = sink.stream()
                    .filter(e -> "boom".equals(e.taskId())
                            && e.state() == TaskScheduler.SchedulerState.FAILED)
                    .findFirst().orElseThrow();
            assertNotNull(failed.error(), "FAILED event must carry the throwable");
            assertEquals("kaboom", failed.error().getMessage());
        } finally {
            live.close();
        }
    }

    @Test
    void awaitIdle_returnsImmediatelyWhenEmpty() throws Exception {
        assertTrue(sched.awaitIdle(100));
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> new TaskScheduler(0, ev -> {}));
        assertThrows(IllegalArgumentException.class, () -> new TaskScheduler(1, null));
    }

    @Test
    void handle_carriesPriority() {
        TaskScheduler.ScheduleHandle h = sched.schedule("h",
                TaskScheduler.Priority.HIGH, () -> {});
        assertEquals(TaskScheduler.Priority.HIGH, h.priority());
        assertFalse(h.isCancelled());
    }

    @Test
    void close_isIdempotent() {
        sched.close();
        sched.close(); // no throw
    }

    // -- helpers --

    private int indexOfRun(String taskId) {
        for (int i = 0; i < events.size(); i++) {
            TaskScheduler.StateChange e = events.get(i);
            if (taskId.equals(e.taskId())
                    && e.state() == TaskScheduler.SchedulerState.RUNNING) {
                return i;
            }
        }
        return -1;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
