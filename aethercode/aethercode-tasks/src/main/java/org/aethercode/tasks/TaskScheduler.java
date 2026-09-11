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
        pendingById.put(taskId, item);
        queue.put(item);
        emit(new StateChange(taskId, SchedulerState.QUEUED));
        return handle;
    }

    public boolean cancel(String taskId) {
        if (taskId == null) return false;
        if (cancelled.add(taskId)) {
            pendingById.remove(taskId);
            emit(new StateChange(taskId, SchedulerState.CANCELLED));
            return true;
        }
        return false;
    }

    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) throw new IllegalArgumentException("timeoutMs must be >= 0");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty() && running.isEmpty() && activeCount.get() == 0) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        return queue.isEmpty() && running.isEmpty() && activeCount.get() == 0;
    }

    public int pendingCount() { return pendingById.size(); }
    public int runningCount() { return running.size(); }
    public int activeCount() { return activeCount.get(); }
    public int maxConcurrent() { return maxConcurrent; }
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
                if (cancelled.contains(item.taskId())) {
                    pendingById.remove(item.taskId());
                    continue;
                }
                pendingById.remove(item.taskId());
                if (cancelled.contains(item.taskId())) continue;
                runItem(item);
            }
        } catch (Throwable t) {
            if (!closed.get()) LOG.warn("worker loop terminated: {}", t.getMessage());
        }
    }

    private void runItem(ScheduledItem item) {
        running.add(item.taskId());
        activeCount.incrementAndGet();
        emit(new StateChange(item.taskId(), SchedulerState.RUNNING));
        Throwable err = null;
        try {
            item.work().run();
        } catch (Throwable t) {
            err = t;
            LOG.warn("task {} threw: {}", item.taskId(), t.getMessage());
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
