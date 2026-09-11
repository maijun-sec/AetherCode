package org.aethercode.core.agent;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * a minimal task-dispatcher interface so SubagentPool and
 * SchedulerBackedSubagentPool (and any future dispatcher) can be
 * swapped behind a common API. The interface intentionally has
 * only the operations used by the TUI/REPL — full submit/cancel
 * semantics differ between implementations.
 */
public interface TaskDispatcher {

    enum Priority { HIGH, NORMAL, LOW }

    /**
     * Submit a task. Returns a unique id. The id is opaque to
     * callers — use {@link #cancel(String)} to cancel.
     */
    String submit(String description, Callable<?> task);

    /** submit with a priority. Higher priority is
     *  dequeued first when the pool is busy. */
    String submit(String description, Priority priority, Callable<?> task);

    /** Cancel a pending or running task. Returns true if the
     *  task was found and cancelled; false otherwise. */
    boolean cancel(String taskId);

    /** Number of tasks currently waiting in the queue. */
    int pendingCount();

    /** Number of tasks currently running. */
    int runningCount();

    /** Block until the dispatcher is idle (no pending, no
     *  running) or the timeout expires. Returns true on idle. */
    boolean awaitIdle(long timeoutMs) throws InterruptedException;

    /** Recent task ids, oldest first. Useful for the
     *  {@code /jobs} command. */
    List<String> recentTaskIds();
}
