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

package org.aethercode.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * a priority-queue task scheduler. Tasks are submitted with a
 * {@link Priority}; an internal fixed-size worker pool dequeues in
 * FIFO-within-priority order and runs each task synchronously on a
 * worker thread.
 *
 * <p>Design notes:
 * <ul>
 *   <li>The scheduler owns its own thread pool. Callers must NOT share
 *       the pool with other components — the worker thread blocks on
 *       {@code queue.take()} and would starve if it had to do other
 *       work.</li>
 *   <li>Tasks are run synchronously on the worker thread. The
 *       scheduler tracks only an "active" count, not per-task futures,
 *       so {@link #cancel} can only stop a task before it starts.</li>
 *   <li>{@link #awaitIdle} is the primary sync point — useful for
 *       tests and for graceful shutdown.</li>
 * </ul>
 */
public final class TaskScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TaskScheduler.class);

    public enum Priority { HIGH(0), NORMAL(1), LOW(2);
        private final int weight;
        Priority(int weight) { this.weight = weight; }
        public int weight() { return weight; }
    }

    public record ScheduledItem(
            String taskId,
            Priority priority,
            long submittedAtMs,
            int sequence,
            Runnable work) {}

    public enum SchedulerState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    public record StateChange(String taskId, SchedulerState state, Throwable error) {
        public StateChange(String taskId, SchedulerState state) { this(taskId, state, null); }
    }

    public interface ScheduleHandle {
        String taskId();
        Priority priority();
        boolean isCancelled();
        long submittedAtMs();
    }

    private record HandleImpl(String taskId, Priority priority, long submittedAtMs,
                              AtomicBoolean cancelledFlag) implements ScheduleHandle {
        @Override public boolean isCancelled() { return cancelledFlag.get(); }
    }

    private static final Comparator<ScheduledItem> ITEM_ORDER =
            Comparator.comparingInt((ScheduledItem it) -> it.priority().weight())
                    .thenComparingLong(ScheduledItem::submittedAtMs)
                    .thenComparingInt(ScheduledItem::sequence);

    /** the queue's ordering comparator, exposed for tests and
     *  for callers that want to sort/compare items externally. */
    public static Comparator<ScheduledItem> itemOrder() { return ITEM_ORDER; }

    private final PriorityBlockingQueue<ScheduledItem> queue = new PriorityBlockingQueue<>(64, ITEM_ORDER);
    private final ConcurrentHashMap<String, ScheduledItem> pendingById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<String, Boolean> cancelled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap.KeySetView<String, Boolean> running = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService workers;
    private final int maxConcurrent;
    private final Consumer<StateChange> onStateChange;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    /**
     * R750d: tasks accepted but whose body has not finished. Incremented in
     * {@code schedule()} (caller's thread) and decremented exactly once
     * per accepted task, on whichever path ends it. This is what
     * {@link #awaitIdle(long)} waits on — see the note there for why the
     * four collections cannot be polled consistently instead.
     */
    private final AtomicInteger outstanding = new AtomicInteger(0);
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TaskScheduler(int maxConcurrent, Consumer<StateChange> onStateChange) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be >= 1");
        if (onStateChange == null) throw new IllegalArgumentException("onStateChange must not be null");
        this.maxConcurrent = maxConcurrent;
        this.onStateChange = onStateChange;
        this.workers = Executors.newFixedThreadPool(maxConcurrent, namedFactory("aethercode-sched-"));
        for (int i = 0; i < maxConcurrent; i++) {
            workers.execute(this::workerLoop);
        }
    }

    public ScheduleHandle schedule(String taskId, Priority priority, Runnable work) {
        if (taskId == null || taskId.isBlank())
            throw new IllegalArgumentException("taskId must not be blank");
        if (priority == null) priority = Priority.NORMAL;
        if (work == null) throw new IllegalArgumentException("work must not be null");
        if (closed.get()) throw new RejectedExecutionException("TaskScheduler is closed");
        if (pendingById.containsKey(taskId) || running.contains(taskId)) {
            throw new IllegalStateException("task " + taskId + " already scheduled or running");
        }
        long now = System.currentTimeMillis();
        int seq = sequenceCounter.incrementAndGet();
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        HandleImpl handle = new HandleImpl(taskId, priority, now, cancelFlag);
        ScheduledItem item = new ScheduledItem(taskId, priority, now, seq, work);
        // R750d: claim the task as outstanding BEFORE it becomes visible in
        // any collection, so a concurrent awaitIdle() can never observe a
        // moment where this task is accepted but uncounted. Doing it first
        // means awaitIdle may briefly over-report (sees work that is about
        // to be cancelled) — an over-report just costs a park iteration,
        // whereas an under-report breaks the barrier.
        outstanding.incrementAndGet();
        pendingById.put(taskId, item);
        queue.put(item);
        emit(new StateChange(taskId, SchedulerState.QUEUED));
        return handle;
    }

    public boolean cancel(String taskId) {
        if (taskId == null) return false;
        if (cancelled.add(taskId)) {
            // Retire the outstanding count only if we are the ones who took
            // the task out of `pendingById`. A cancel that loses the race
            // with the worker finds the entry already gone, and the worker
            // owns the decrement. Exactly one of the two paths must fire or
            // the counter drifts and awaitIdle() never returns.
            if (pendingById.remove(taskId) != null) {
                outstanding.decrementAndGet();
            }
            emit(new StateChange(taskId, SchedulerState.CANCELLED));
            return true;
        }
        return false;
    }

    /**
     * Block until the scheduler has no outstanding work, or the timeout
     * elapses.
     *
     * <p><b>R750d — "idle" is one counter, not four collections.</b> The old
     * predicate was {@code queue.isEmpty() && running.isEmpty() &&
     * activeCount == 0}, read one structure at a time. That is not merely
     * imprecise, it is <em>unobservable-consistent</em>: a task moves
     * through four structures ({@code queue} → {@code pendingById} →
     * {@code running} → {@code activeCount}), and any interleaving of the
     * reader with a worker can see all four empty while a body has not run.
     * Adding {@code pendingById} to the conjunction closed the two widest
     * windows but left the {@code pendingById.remove} → {@code running.add}
     * handoff, and awaitIdle still returned early about one round in three.
     *
     * <p>The fix is {@link #outstanding}: incremented on the caller's thread
     * the moment a task is accepted, decremented on the worker only after
     * the body has run and the terminal state has been emitted. A single
     * {@code AtomicInteger} read is atomic, so awaitIdle has exactly one
     * field to be consistent against and no interleaving can make it lie.
     *
     * <p>Same lesson as R743/R750: when N places each re-derive "is this
     * an error / is this done", they will eventually disagree. Ask ONE owner.
     *
     * @return {@code true} if the scheduler drained within the timeout
     */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) throw new IllegalArgumentException("timeoutMs must be >= 0");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (outstanding.get() == 0) return true;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        return outstanding.get() == 0;
    }

    /**
     * Number of tasks accepted but whose body has not yet finished.
     * The single source of truth for {@link #awaitIdle(long)}.
     */
    public int outstandingCount() { return outstanding.get(); }

    public int pendingCount() { return pendingById.size(); }
    public int runningCount() { return running.size(); }
    public int activeCount() { return activeCount.get(); }
    public int maxConcurrent() { return maxConcurrent; }
    /**
     * A loose, point-in-time "is nothing running right now?" answer.
     *
     * <p><b>Not a barrier.</b> Unlike {@link #awaitIdle(long)} this makes
     * no ordering guarantee: a task that has been accepted but not yet
     * claimed by a worker may not be visible here. Use {@code awaitIdle}
     * when you need "everything I scheduled has finished"; use this only
     * for opportunistic polling / telemetry.
     */
    public boolean isIdle() { return pendingCount() == 0 && runningCount() == 0; }

    public List<String> pendingTaskIds() { return new ArrayList<>(pendingById.keySet()); }
    public List<String> runningTaskIds() { return new ArrayList<>(running); }

    public Priority priorityOf(String taskId) {
        ScheduledItem it = pendingById.get(taskId);
        return it != null ? it.priority() : null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (String id : new ArrayList<>(pendingById.keySet())) {
            cancel(id);
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void workerLoop() {
        try {
            while (!closed.get()) {
                ScheduledItem item;
                try {
                    item = queue.take();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // R750d: ownership transfer. `pendingById.remove` hands the
                // task from "queued" to "the worker's problem" — and the
                // outstanding count is the one field that stays true across
                // the whole journey, so awaitIdle() never sees a gap.
                //
                // The earlier R750d attempt instead relied on `running` +
                // `activeCount` and added `pendingById` to the awaitIdle
                // predicate. That is not sufficient: four collections read
                // one at a time are not an observeable-consistent snapshot,
                // so the remove→add handoff still let awaitIdle return early
                // (~1 run in 3 under load). See the note on awaitIdle.
                //
                // `wasPending` decides who retires the count. A cancelled
                // task may have already been pulled out of `pendingById`
                // by cancel(), in which case cancel() already decremented
                // and we must not decrement again — the item still travels
                // through the queue, so an unconditional decrement here
                // would double-count and wedge awaitIdle forever.
                boolean wasPending = pendingById.remove(item.taskId()) != null;
                if (cancelled.contains(item.taskId())) {
                    if (wasPending) outstanding.decrementAndGet();
                    continue;
                }
                running.add(item.taskId());
                activeCount.incrementAndGet();
                runItem(item);
            }
        } catch (Throwable t) {
            if (!closed.get()) LOG.warn("worker loop terminated: {}", t.getMessage());
        }
    }

    private void runItem(ScheduledItem item) {
        // R750d: the whole body, including the leading emit, must be inside
        // a try/finally that retires the outstanding count. If the RUNNING
        // emit (or anything else) threw, workerLoop's catch would swallow it
        // and the task would stay counted forever, so every later awaitIdle
        // would burn its full timeout and return false — a wedged scheduler
        // with no diagnostic.
        Throwable err = null;
        try {
            emit(new StateChange(item.taskId(), SchedulerState.RUNNING));
            try {
                item.work().run();
            } catch (Throwable t) {
                err = t;
                LOG.warn("task {} threw: {}", item.taskId(), t.getMessage());
            }
        } finally {
            // Emit the terminal state BEFORE decrementing counters
            // so awaitIdle observers see the FAILED event before
            // seeing the empty running/active set. Otherwise the
            // listener and awaitIdle can race.
            if (cancelled.contains(item.taskId())) {
                emit(new StateChange(item.taskId(), SchedulerState.CANCELLED, err));
            } else if (err != null) {
                emit(new StateChange(item.taskId(), SchedulerState.FAILED, err));
            } else {
                emit(new StateChange(item.taskId(), SchedulerState.COMPLETED));
            }
            running.remove(item.taskId());
            activeCount.decrementAndGet();
            // R750d: LAST, after the terminal event has been emitted. An
            // awaitIdle() that returns before this must never precede the
            // COMPLETED/FAILED/CANCELLED notification, or a caller that
            // reads task state straight after awaiting sees a stale value.
            outstanding.decrementAndGet();
        }
    }

    private void emit(StateChange ev) {
        try {
            onStateChange.accept(ev);
        } catch (Exception e) {
            LOG.warn("onStateChange listener threw: {}", e.getMessage());
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger ctr = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + ctr.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
