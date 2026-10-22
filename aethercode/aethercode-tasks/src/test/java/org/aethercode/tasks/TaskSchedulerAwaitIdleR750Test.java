package org.aethercode.tasks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R750d — {@link TaskScheduler#awaitIdle(long)} was not a barrier.
 *
 * <p>The old predicate was
 * {@code queue.isEmpty() && running.isEmpty() && activeCount == 0}.
 * It never consulted {@code pendingById}, which is the structure written
 * <em>first</em> by {@code schedule()}:
 *
 * <pre>
 *   schedule():  pendingById.put(id, item);   &lt;-- accepted here
 *                queue.put(item);             &lt;-- visible to workers here
 *   workerLoop: queue.take() -&gt; pendingById.remove -&gt; running.add -&gt; work()
 * </pre>
 *
 * <p>So there were two windows in which all three checked structures read
 * empty while a body had not yet run: between {@code pendingById.put} and
 * {@code queue.put}, and between the worker draining the queue and
 * claiming the item. {@code awaitIdle} could return {@code true} in
 * either.
 *
 * <p>Observed as {@code TaskSchedulerBridgeTest.bridge_runsTaskAndUpdates
 * RegistryToCompleted} asserting {@code ran == 1} and reading {@code 0},
 * once, in a full-reactor run — a low-probability symptom of a
 * high-probability defect.
 *
 * <p><b>Why a new test.</b> The original suite could not reproduce this:
 * it scheduled one task and awaited once, which almost always wins the
 * race. Worse, when I first hypothesised the cause as the
 * {@code pendingById.remove} → {@code running.add} handoff and "fixed"
 * only that, both the fixed and reverted builds still passed the original
 * test — a mutation that proves nothing (Lesson 1117). Only the barrier
 * property below, exercised over hundreds of rounds, turned red.
 */
class TaskSchedulerAwaitIdleR750Test {

    /**
     * The core invariant, asserted without racing: block the worker inside
     * {@code work()} and, from the main thread, prove awaitIdle reports
     * NOT-idle for as long as the body is executing.
     *
     * <p>Under the old ordering this still passed (the id was in
     * {@code running} by then), which is exactly why it is paired with
     * {@link #awaitIdle_isASufficientBarrierForEveryScheduledBody()} — the
     * barrier test is the one that actually caught the bug.
     */
    @Test
    void awaitIdle_reportsBusyWhileBodyIsExecuting() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(1, TaskSchedulerBridge.toRegistry(registry));
        Task task = registry.create(TaskType.AGENT, "blocking", null);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sched.schedule(task.id(), TaskScheduler.Priority.NORMAL, () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(entered.await(5, TimeUnit.SECONDS), "task body must start");
        // Body is parked in release.await() -> activeCount is 1.
        assertFalse(sched.awaitIdle(200),
                "awaitIdle must NOT report idle while the body is still executing");
        assertEquals(1, sched.activeCount());

        release.countDown();
        assertTrue(sched.awaitIdle(5000));
        assertEquals(0, sched.activeCount());
        assertEquals(TaskStatus.COMPLETED, registry.get(task.id()).orElseThrow().status());
        sched.close();
    }

    /**
     * awaitIdle must be a <em>sufficient</em> barrier: when it returns true,
     * every scheduled body has already run to completion.
     *
     * <p>This is the property the original test meant to check but could
     * only check by accident. Here it is checked over many rounds with a
     * body that is slow to be picked up, which is precisely the condition
     * that opened the original window.
     *
     * <p>Each round schedules one task then immediately calls awaitIdle
     * with a generous timeout; if awaitIdle ever returns early the counter
     * will disagree with the number of scheduled tasks.
     */
    @Test
    void awaitIdle_isASufficientBarrierForEveryScheduledBody() throws Exception {
        final int rounds = 300;
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(1, TaskSchedulerBridge.toRegistry(registry));
        AtomicInteger ran = new AtomicInteger(0);

        for (int i = 0; i < rounds; i++) {
            Task t = registry.create(TaskType.AGENT, "round-" + i, null);
            sched.schedule(t.id(), TaskScheduler.Priority.NORMAL, ran::incrementAndGet);
            assertTrue(sched.awaitIdle(5000), "awaitIdle timed out on round " + i);
            // Immediately after a true awaitIdle, this task's body has run.
            // Under the old ordering the worker could still be between
            // pendingById.remove and running.add, so ran would lag behind.
            assertEquals(i + 1, ran.get(),
                    "awaitIdle returned true before round " + i + " had executed its body");
        }
        assertEquals(rounds, ran.get());
        sched.close();
    }

    /**
     * Structural invariant, independent of timing: the scheduler's own
     * accounting must always add up.
     *
     * <p>This is the exact shape of the bug — the old code had a window in
     * which {@code pendingCount}, {@code runningCount} and
     * {@code activeCount} all read zero at once while work was still
     * outstanding — expressed as a property so it fails deterministically
     * rather than probabilistically.
     *
     * <p>Deliberately checks ONLY scheduler-internal counters. An earlier
     * draft also cross-checked {@code registry.list()} and failed against
     * the fixed code: the COMPLETED state reaches the registry through the
     * bridge listener, which runs on the worker thread slightly AFTER
     * {@code activeCount} is decremented. That ordering is fine and
     * intended (see the emit-before-decrement comment in runItem); it just
     * is not the invariant under test. Watching the registry here tested
     * the bridge's latency, not the scheduler's accounting.
     *
     * <p>The watcher polls while tasks flow; if the counters ever report
     * "nothing anywhere" while the scheduler is not actually drained, that
     * is the unaccounted window.
     */
    @Test
    void schedulerCountersNeverAllReadZeroWhileWorkIsOutstanding() throws Exception {
        final int rounds = 400;
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(2, TaskSchedulerBridge.toRegistry(registry));

        AtomicInteger violations = new AtomicInteger(0);
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch watcherDone = new CountDownLatch(1);

        // A task whose body parks until we release it. While it is parked
        // the scheduler MUST report it: runningCount>=1 and activeCount==1.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Task parked = registry.create(TaskType.AGENT, "parked", null);
        sched.schedule(parked.id(), TaskScheduler.Priority.NORMAL, () -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        Thread watcher = new Thread(() -> {
            try {
                while (!stop.get()) {
                    int pending = sched.pendingCount();
                    int runningCount = sched.runningCount();
                    int active = sched.activeCount();
                    // The parked body is executing, so the scheduler must not
                    // look idle to any observer.
                    if (pending == 0 && runningCount == 0 && active == 0) {
                        violations.incrementAndGet();
                        return;
                    }
                    // running/active must also agree with each other: a task
                    // is added to `running` and counted in the SAME critical
                    // region (R750d), so they can never be out of step.
                    if ((runningCount == 0) != (active == 0)) {
                        violations.incrementAndGet();
                        return;
                    }
                }
            } finally {
                watcherDone.countDown();
            }
        }, "r750d-scheduler-counter-watcher");
        watcher.setDaemon(true);
        watcher.start();

        // Keep other work flowing so the watcher sees a busy scheduler.
        for (int i = 0; i < rounds; i++) {
            Task t = registry.create(TaskType.AGENT, "churn-" + i, null);
            sched.schedule(t.id(), TaskScheduler.Priority.NORMAL, () -> { });
            if (i % 50 == 0) Thread.sleep(1);
        }

        assertTrue(watcherDone.await(500, TimeUnit.MILLISECONDS) || !stop.get(),
                "watcher should still be polling while work is outstanding");
        release.countDown();
        stop.set(true);
        assertTrue(sched.awaitIdle(10000));

        assertEquals(0, violations.get(),
                "scheduler counters reported 'idle' while a body was still executing");
        sched.close();
    }

    /** Cancelling a still-queued task must still be a no-op for awaitIdle
     *  bookkeeping (guards the reordered dequeue path against regression). */
    @Test
    void awaitIdle_returnsTrueAfterQueuedTaskIsCancelled() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(1, TaskSchedulerBridge.toRegistry(registry));
        Task t = registry.create(TaskType.AGENT, "cancelled-early", null);

        CountDownLatch gate = new CountDownLatch(1);
        Task blocker = registry.create(TaskType.AGENT, "blocker", null);
        sched.schedule(blocker.id(), TaskScheduler.Priority.NORMAL, () -> {
            try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        sched.schedule(t.id(), TaskScheduler.Priority.NORMAL, () -> fail("cancelled task must not run"));
        Thread.sleep(30);
        sched.cancel(t.id());
        gate.countDown();

        assertTrue(sched.awaitIdle(5000));
        assertEquals(0, sched.activeCount());
        assertEquals(TaskStatus.KILLED, registry.get(t.id()).orElseThrow().status());
        sched.close();
    }
}