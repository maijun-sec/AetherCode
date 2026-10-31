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
