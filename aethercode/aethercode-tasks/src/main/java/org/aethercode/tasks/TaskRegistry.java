package org.aethercode.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Process-singleton registry of all live and recently-completed tasks.
 * Modelled after Claude Code's {@code utils/tasks.ts}: signal-emitter +
 * file-backed persistence.
 *
 * <p>Listeners (TUI panels, audit log) subscribe via {@link #onChange} and
 * are notified after every create / status change. Updates are best-effort:
 * a listener that throws does not roll back the registry mutation.
 */
public final class TaskRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TaskRegistry.class);
    private static final AtomicReference<TaskRegistry> INSTANCE = new AtomicReference<>();

    public static TaskRegistry instance() {
        return INSTANCE.updateAndGet(reg -> reg == null ? new TaskRegistry() : reg);
    }

    /** Test helper. */
    public static void resetForTests() {
        INSTANCE.set(new TaskRegistry());
    }

    /** package-private constructor for {@link PersistentTaskRegistry}
     *  and tests. The instance() singleton uses the private constructor
     *  via updateAndGet above; this constructor is exposed to the package
     *  only so the persistent wrapper can build its own registry. */
    TaskRegistry() {}

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final List<Consumer<Task>> listeners = new CopyOnWriteArrayList<>();

    /** Create a new task and return it. */
    public Task create(TaskType type, String description, String parentTaskId) {
        long now = System.currentTimeMillis();
        Task t = new Task(
                Task.generateId(type, java.util.random.RandomGenerator.getDefault()),
                type, TaskStatus.PENDING, description, parentTaskId, now, 0L);
        tasks.put(t.id(), t);
        notify(t);
        return t;
    }

    /** Transition a task to a new status. Idempotent for terminal states. */
    public Task updateStatus(String taskId, TaskStatus next) {
        Task prev = tasks.get(taskId);
        if (prev == null) {
            throw new IllegalArgumentException("unknown task: " + taskId);
        }
        if (prev.status().isTerminal()) {
            LOG.debug("task {} already terminal ({}), ignoring transition to {}",
                    taskId, prev.status(), next);
            return prev;
        }
        long now = System.currentTimeMillis();
        Task next2 = new Task(prev.id(), prev.type(), next, prev.description(),
                prev.parentTaskId(), prev.createdAtMs(),
                next.isTerminal() ? now : prev.endedAtMs());
        tasks.put(taskId, next2);
        notify(next2);
        return next2;
    }

    public Optional<Task> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** All live tasks ordered by createdAtMs ascending. */
    public List<Task> list() {
        return tasks.values().stream()
                .sorted((a, b) -> Long.compare(a.createdAtMs(), b.createdAtMs()))
                .toList();
    }

    /** Children of a parent task, ordered by createdAtMs. */
    public List<Task> listChildren(String parentId) {
        return tasks.values().stream()
                .filter(t -> parentId == null
                        ? t.isRoot()
                        : parentId.equals(t.parentTaskId()))
                .sorted((a, b) -> Long.compare(a.createdAtMs(), b.createdAtMs()))
                .toList();
    }

    /** Subscribe to task lifecycle changes. Returns the consumer for later
     *  detachment if needed. */
    public Consumer<Task> onChange(Consumer<Task> listener) {
        listeners.add(listener);
        return listener;
    }

    private void notify(Task t) {
        for (var l : listeners) {
            try { l.accept(t); } catch (Exception e) {
                LOG.warn("task listener threw: {}", e.getMessage());
            }
        }
    }

    // -- prior round: package-private hooks for the persistent wrapper --

    /** package-private. Put a task into the map without
     *  firing listeners. Used by {@link PersistentTaskRegistry}
     *  during restore so the listener can be fired once at the end
     *  with the final task state, not once per intermediate. */
    void tasksPut(Task t) {
        tasks.put(t.id(), t);
    }

    /** package-private. Look up a task by id. */
    Task tasksGet(String id) {
        return tasks.get(id);
    }

    /** package-private. Fire all listeners with the given
     *  task (without recording a state change). Used by
     *  {@link PersistentTaskRegistry#restore} to broadcast
     *  restored state. */
    void notifyDirect(Task t) {
        notify(t);
    }
}
