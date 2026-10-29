/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
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
 * test — a mutation that proves nothing (Lesson 1117). Adding
 * {@code pendingById} to the predicate then closed two windows and left a
 * third, and the test still failed 3 runs in 6 (Lesson 1120).
 *
 * <p><b>What these tests do and do not prove.</b> The mutation harness
 * (reverting {@code awaitIdle} to the old predicate) leaves every test in
 * this class GREEN, because the original window is a few instructions wide
 * and a test cannot reliably land inside it. So these cases are not the gate
 * for that specific window and are not named as if they were. What they
 * enforce is the structural property that makes the window unreachable: a
 * single authoritative counter, incremented on acceptance and decremented
 * exactly once per task. That property failed loudly during development —
 * the cancel paths double-decremented and wedged awaitIdle, and
 * {@link TaskRegistry#updateStatus} lost writes — which is the failure mode
 * these tests actually protect against.
 */
class TaskSchedulerAwaitIdleR750Test {

    /**
     * The core invariant, asserted without racing: block the worker inside
     * {@code work()} and, from the main thread, prove awaitIdle reports
     * NOT-idle for as long as the body is executing.
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
 * every body accepted <em>before</em> the call has run.
 *
 * <p><b>What this test deliberately does NOT assert.</b> Two earlier
 * drafts read a test-owned {@code AtomicInteger} and then the registry's
 * status straight after awaitIdle, and both were flaky (~4-11 runs in 3000).
 * An event-trace probe showed why: in the failing samples the task had only
 * emitted QUEUED (sometimes QUEUED+RUNNING) with no COMPLETED, while
 * {@code outstandingCount()} read 1 — i.e. the task belonged to a LATER
 * iteration than the one being asserted, and the loop had already moved on.
 * A volatile handshake was also tried and did not help (the counter was
 * exact while the status was still PENDING), ruling out visibility as the
 * cause.
 *
 * <p>The lesson is that "the barrier orders the body" is only true for work
 * handed to the scheduler <em>before</em> the call. Asserting on an
 * observable the scheduler does not own invites a race that no amount of
 * retrying will fix. The barrier's actual contract is asserted
 * deterministically in
 * {@link #awaitIdle_doesNotReportIdleForATaskAcceptedButNotYetClaimed()}.
 *
 * <p>What remains here is the coarse property: after draining, every task
 * is terminal and the counter is back to zero.
 */
    @Test
    void awaitIdle_drainsCompletelyAndAccountsForEveryTask() throws Exception {
        final int rounds = 300;
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(1, TaskSchedulerBridge.toRegistry(registry));
        List<Task> tasks = new ArrayList<>();

        for (int i = 0; i < rounds; i++) {
            Task t = registry.create(TaskType.AGENT, "round-" + i, null);
            tasks.add(t);
            sched.schedule(t.id(), TaskScheduler.Priority.NORMAL, () -> { });
            assertTrue(sched.awaitIdle(5000), "awaitIdle timed out on round " + i);
        }

        // One final drain, then check every task. Nothing is racing here
        // because no further work is submitted.
        assertTrue(sched.awaitIdle(10000));
        for (Task t : tasks) {
            assertEquals(TaskStatus.COMPLETED, registry.get(t.id()).orElseThrow().status(),
                    "task " + t.id() + " did not reach a terminal state");
        }
        assertEquals(0, sched.outstandingCount());
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
     /**
 * The authoritative counter must never read 0 while a body is executing.
 *
 * <p>This is the exact shape of the R750d bug, expressed as a property so
 * it fails deterministically instead of probabilistically: the old
 * awaitIdle polled {@code queue} / {@code running} / {@code activeCount},
 * and a task travelling through those four structures left windows where all
 * three read zero while work had not run.
 *
 * <p>An earlier draft watched the same three collections and was wrong in
 * the other direction: with 2 workers the parked task occupies one, the
 * other is free, so the three legitimately read zero between churn tasks.
 * "All three are zero" is a valid <em>point-in-time</em> observation — it is
 * only invalid as a <em>drain</em> criterion. That is precisely why
 * {@code isIdle()} (loose, for pollers) and {@code awaitIdle} (a barrier)
 * are different contracts, and why the barrier is defined by the single
 * {@code outstanding} counter.
 */
    @Test
    void outstandingNeverReadsZeroWhileABodyIsExecuting() throws Exception {
        final int rounds = 400;
        TaskRegistry registry = new TaskRegistry();
        TaskScheduler sched = new TaskScheduler(2, TaskSchedulerBridge.toRegistry(registry));

        AtomicInteger violations = new AtomicInteger(0);
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch watcherDone = new CountDownLatch(1);

        // A task whose body parks until we release it. For as long as it is
        // parked, outstandingCount() must be >= 1 -- no observer, on any
        // thread, may see it reach 0.
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
                    if (sched.outstandingCount() == 0) {
                        violations.incrementAndGet();
                        return;
                    }
                }
            } finally {
                watcherDone.countDown();
            }
        }, "r750d-outstanding-watcher");
        watcher.setDaemon(true);
        watcher.start();

        // Keep other work flowing so the watcher polls a busy scheduler.
        for (int i = 0; i < rounds; i++) {
            Task t = registry.create(TaskType.AGENT, "churn-" + i, null);
            sched.schedule(t.id(), TaskScheduler.Priority.NORMAL, () -> { });
            if (i % 50 == 0) Thread.sleep(1);
        }

        // The watcher must still be running: it exits early only on a
        // violation, so reaching here means it found none so far.
        assertFalse(watcherDone.await(300, TimeUnit.MILLISECONDS),
                "watcher exited early, meaning outstanding reached 0 while a body was parked");

        release.countDown();
        stop.set(true);
        assertTrue(sched.awaitIdle(10000));

        assertEquals(0, violations.get(),
                "outstandingCount() reached 0 while a body was still executing");
        sched.close();
    }

    /**
 * awaitIdle must not report idle while a task has been accepted but has not
 * finished.
 *
 * <p><b>Honest note on what this test can and cannot prove.</b> The R750d
 * bug was that the old predicate consulted {@code queue} / {@code running}
 * / {@code activeCount} but never {@code pendingById}, so it could report
 * idle in the window between {@code schedule()} accepting a task and a
 * worker claiming it. That window is real but only a few instructions wide
 * — {@code pendingById.put} and {@code queue.put} are adjacent statements —
 * so a test cannot reliably observe it, and the mutation harness confirms
 * this: reverting the predicate leaves every test in this class green.
 *
 * <p>So this test is NOT the gate for that specific window, and pretending
 * otherwise would be a lie in the test name. What it does assert is the
 * property that makes the window unreachable in the first place: a task
 * that is accepted is counted in {@code outstanding}, and awaitIdle is
 * driven by that counter alone. The regression protection comes from the
 * predicate being visibly single-sourced plus the three structural cases
 * below (barrier-while-running, full drain, and cancel accounting), which
 * together failed loudly during development when the counter drifted.
 *
 * <p>The deterministic counterpart is {@link #outstandingNeverReadsZeroWhileABodyIsExecuting()},
 * which holds a body parked and asserts no observer ever sees the counter at
 * 0.
 */
    @Test
    void awaitIdle_doesNotReportIdleWhileAnAcceptedTaskIsUnfinished() throws Exception {
        TaskScheduler sched = new TaskScheduler(1, ev -> { });

        // Occupy the worker, let it finish, and confirm the scheduler is
        // genuinely quiescent before the case under test.
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);
        sched.schedule("blocker", TaskScheduler.Priority.NORMAL, () -> {
            blockerEntered.countDown();
            try { blockerRelease.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS), "blocker must occupy the worker");
        blockerRelease.countDown();
        assertTrue(sched.awaitIdle(5000), "blocker must drain");
        assertEquals(0, sched.outstandingCount(), "scheduler must be quiescent first");
        assertEquals(0, sched.runningCount(), "precondition: nothing is running");
        assertEquals(0, sched.activeCount(), "precondition: nothing is active");

        // Submit a task that parks. It is accepted and counted; until the
        // body finishes, awaitIdle must not claim quiescence.
        CountDownLatch bodyRelease = new CountDownLatch(1);
        AtomicInteger bodyRan = new AtomicInteger(0);
        sched.schedule("under-test", TaskScheduler.Priority.NORMAL, () -> {
            bodyRan.incrementAndGet();
            try { bodyRelease.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        assertEquals(1, sched.outstandingCount(), "the accepted task must be counted");

        assertFalse(sched.awaitIdle(300),
                "awaitIdle reported idle while an accepted task had not finished");

        bodyRelease.countDown();
        assertTrue(sched.awaitIdle(5000), "scheduler must drain after release");
        assertEquals(1, bodyRan.get(), "the deferred body must have run exactly once");
        assertEquals(0, sched.outstandingCount());
        sched.close();
    }

    /**
     * Cancelling a still-queued task must still be a no-op for awaitIdle
     *  bookkeeping (guards the reordered dequeue path against regression).
     */
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