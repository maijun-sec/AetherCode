package org.aethercode.tasks;

import java.util.function.Consumer;

/**
 * a small bridge that connects {@link TaskScheduler}'s
 * {@link TaskScheduler.StateChange} events to {@link TaskRegistry}'s
 * {@link TaskRegistry#updateStatus} method.
 *
 * <p>The mapping is:
 * <ul>
 *   <li>{@code QUEUED}    → (no-op — task was created as PENDING)</li>
 *   <li>{@code RUNNING}   → {@link TaskStatus#RUNNING}</li>
 *   <li>{@code COMPLETED} → {@link TaskStatus#COMPLETED}</li>
 *   <li>{@code FAILED}    → {@link TaskStatus#FAILED}</li>
 *   <li>{@code CANCELLED} → {@link TaskStatus#KILLED}</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * TaskRegistry registry = TaskRegistry.instance();
 * TaskScheduler sched = new TaskScheduler(4, TaskSchedulerBridge.toRegistry(registry));
 *
 * Task t = registry.create(TaskType.AGENT, "summarize file", parentId);
 * sched.schedule(t.id(), TaskScheduler.Priority.NORMAL, () -> doWork(t));
 * // the registry automatically transitions PENDING → RUNNING → COMPLETED
 * }</pre>
 */
public final class TaskSchedulerBridge {

    private TaskSchedulerBridge() {}

    /**
     * Build a {@link Consumer} that updates the given {@link TaskRegistry}
     * on each scheduler event. Returns a Consumer that swallows registry
     * errors (the registry throws on unknown taskId; the bridge logs
     * instead of bubbling up).
     */
    public static Consumer<TaskScheduler.StateChange> toRegistry(TaskRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        return ev -> {
            TaskStatus next = switch (ev.state()) {
                case QUEUED    -> null;          // already PENDING
                case RUNNING   -> TaskStatus.RUNNING;
                case COMPLETED -> TaskStatus.COMPLETED;
                case FAILED    -> TaskStatus.FAILED;
                case CANCELLED -> TaskStatus.KILLED;
            };
            if (next == null) return;
            try {
                registry.updateStatus(ev.taskId(), next);
            } catch (Exception ignore) {
                // taskId may not exist in registry; the bridge is
                // best-effort. The scheduler is the source of truth.
            }
        };
    }
}
