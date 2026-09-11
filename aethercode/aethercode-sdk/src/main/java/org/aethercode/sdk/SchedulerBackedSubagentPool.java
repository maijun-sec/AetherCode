package org.aethercode.sdk;

import org.aethercode.tasks.TaskScheduler;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * a {@link SubagentPool}-shaped facade that delegates
 * work to a {@link TaskScheduler}. Same submit/waitFor/cancel
 * surface as SubagentPool, but uses the priority queue and
 * listener wiring from TaskScheduler.
 *
 * <p>Use this when you want SubagentPool's API but with
 * TaskScheduler's priority dispatch and event semantics. For
 * greenfield code, prefer TaskScheduler directly.
 */
public final class SchedulerBackedSubagentPool {

    private final TaskScheduler scheduler;
    private final List<String> activeAgentIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger completed = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private volatile boolean shutdown = false;

    public SchedulerBackedSubagentPool(TaskScheduler scheduler) {
        if (scheduler == null) throw new IllegalArgumentException("scheduler must not be null");
        this.scheduler = scheduler;
    }

    public String submit(String description, Callable<?> task) {
        return submit(description, TaskScheduler.Priority.NORMAL, task);
    }

    public String submit(String description, TaskScheduler.Priority priority, Callable<?> task) {
        if (shutdown) throw new IllegalStateException("pool is shut down");
        if (priority == null) priority = TaskScheduler.Priority.NORMAL;
        String id = "agent-" + System.nanoTime() + "-" + completed.get();
        activeAgentIds.add(id);
        TaskScheduler.ScheduleHandle handle = scheduler.schedule(id, priority, () -> {
            try {
                task.call();
                completed.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        return id;
    }

    public boolean cancel(String agentId) {
        return scheduler.cancel(agentId);
    }

    public long completedCount() { return completed.get(); }
    public long failedCount() { return failed.get(); }
    public int pendingCount() { return scheduler.pendingCount(); }
    public int runningCount() { return scheduler.runningCount(); }
    public TaskScheduler scheduler() { return scheduler; }

    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        return scheduler.awaitIdle(timeoutMs);
    }

    public void shutdown() {
        shutdown = true;
        for (String id : List.copyOf(activeAgentIds)) {
            scheduler.cancel(id);
        }
    }
}
