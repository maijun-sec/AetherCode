package org.aethercode.core.agent;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * a bounded pool of subagents. Each submitted {@code task}
 * is dispatched to an available slot; excess tasks queue up. Tracks
 * running/queued/completed counts and emits {@link Event}s for the
 * TUI.
 */
public class SubagentPool {

    public enum State { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    /** priority for the submission queue. Higher priority
     *  dispatches first when the pool is at capacity. */
    public enum Priority { HIGH(0), NORMAL(1), LOW(2);
        private final int weight;
        Priority(int w) { this.weight = w; }
        public int weight() { return weight; }
    }

    public record Event(String agentId, State newState, long timestampMs) {}

    public record Submission<V>(
            String agentId,
            String description,
            java.util.concurrent.Callable<V> task
    ) {}

    public record Result<V>(String agentId, State state, V value, Throwable error) {
        public boolean isSuccess() { return state == State.COMPLETED; }
    }

    private final int maxConcurrent;
    private final ExecutorService executor;
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final Map<String, CallableHolder> taskHolders = new ConcurrentHashMap<>();
    private final int eventLimit = 200;

    private record CallableHolder(java.util.concurrent.Callable<?> callable) {}

    public SubagentPool(int maxConcurrent) {
        this(maxConcurrent, defaultExecutor(maxConcurrent));
    }

    public SubagentPool(int maxConcurrent, ExecutorService executor) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be >= 1");
        if (executor == null) throw new IllegalArgumentException("executor is null");
        this.maxConcurrent = maxConcurrent;
        this.executor = executor;
    }

    private static ExecutorService defaultExecutor(int n) {
        return Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "subagent-pool");
            t.setDaemon(true);
            return t;
        });
    }

    /** submit a task. Returns the agent id; the result is available via {@link #waitFor}. */
    public <V> String submit(String description, java.util.concurrent.Callable<V> task) {
        return submit(description, Priority.NORMAL, task);
    }

    /** submit a task with a priority. Higher priority
     *  dispatches first when the pool is at capacity. Backed by
     *  an internal priority queue; the executor pulls in order. */
    public synchronized <V> String submit(String description, Priority priority, java.util.concurrent.Callable<V> task) {
        Objects.requireNonNull(task, "task");
        if (priority == null) priority = Priority.NORMAL;
        String id = "agent-" + System.nanoTime() + "-" + completed.get();
        states.put(id, State.PENDING);
        recordEvent(id, State.PENDING);
        taskHolders.put(id, new CallableHolder(task));
        pendingPriorities.put(id, priority.weight());
        pendingQueue.add(new PriorityEntry(id, priority.weight(), sequenceCounter.incrementAndGet()));
        drainQueue();
        return id;
    }

    private final java.util.PriorityQueue<PriorityEntry> pendingQueue =
            new java.util.PriorityQueue<>(Comparator
                    .comparingInt((PriorityEntry e) -> e.priority())
                    .thenComparingLong(e -> e.sequence()));
    private final ConcurrentHashMap<String, Integer> pendingPriorities = new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    private record PriorityEntry(String id, int priority, long sequence) {}

    /** drain the priority queue into the executor, up to
     *  the concurrency limit. */
    private void drainQueue() {
        while (activeCount.get() < maxConcurrent) {
            PriorityEntry entry = pendingQueue.poll();
            if (entry == null) return;
            pendingPriorities.remove(entry.id);
            // Increment active count BEFORE dispatching so that
            // the next drainQueue() call (from another thread) sees
            // the updated count.
            activeCount.incrementAndGet();
            dispatch(entry.id);
        }
    }

    @SuppressWarnings("unchecked")
    private <V> void dispatch(String agentId) {
        // Look up the task from the original submit() call. The
        // task is captured in a per-agent record. We use a small
        // map to keep the association.
        CallableHolder holder = taskHolders.get(agentId);
        if (holder == null) return; // race with cancel
        Future<V> f = executor.submit(() -> {
            try {
                states.put(agentId, State.RUNNING);
                recordEvent(agentId, State.RUNNING);
                V result = (V) holder.callable.call();
                states.put(agentId, State.COMPLETED);
                recordEvent(agentId, State.COMPLETED);
                completed.incrementAndGet();
                return result;
            } catch (Exception e) {
                states.put(agentId, State.FAILED);
                recordEvent(agentId, State.FAILED);
                failed.incrementAndGet();
                throw new java.util.concurrent.CompletionException(e);
            } finally {
                activeCount.decrementAndGet();
                taskHolders.remove(agentId);
                drainQueue();
            }
        });
        futures.put(agentId, f);
    }

    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    public boolean cancel(String agentId) {
        // if still pending in the priority queue, remove it
        // before dispatch. If running, cancel the future.
        Future<?> f = futures.remove(agentId);
        if (f != null) {
            boolean cancelled = f.cancel(true);
            if (cancelled) {
                states.put(agentId, State.CANCELLED);
                recordEvent(agentId, State.CANCELLED);
            }
            return cancelled;
        }
        // Try to remove from priority queue.
        boolean wasPending = pendingQueue.removeIf(e -> e.id().equals(agentId));
        if (wasPending) {
            pendingPriorities.remove(agentId);
            taskHolders.remove(agentId);
            states.put(agentId, State.CANCELLED);
            recordEvent(agentId, State.CANCELLED);
            return true;
        }
        return false;
    }

    public State stateOf(String agentId) {
        return states.get(agentId);
    }

    public boolean isRunning(String agentId) {
        State s = states.get(agentId);
        return s == State.RUNNING;
    }

    public <V> Optional<V> waitFor(String agentId, long timeoutMs) {
        Future<?> f = futures.get(agentId);
        if (f == null) return Optional.empty();
        try {
            @SuppressWarnings("unchecked")
            V v = (V) f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(v);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<Event> events() { return List.copyOf(events); }
    public int eventCount() { return events.size(); }

    public long completedCount() { return completed.get(); }
    public long failedCount() { return failed.get(); }
    public int pendingCount() { return pendingQueue.size(); }
    public int activeCount() { return activeCount.get(); }

    public int maxConcurrent() { return maxConcurrent; }

    public Map<String, State> states() { return new LinkedHashMap<>(states); }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void recordEvent(String agentId, State newState) {
        events.add(new Event(agentId, newState, System.currentTimeMillis()));
        while (events.size() > eventLimit) events.remove(0);
    }
}
