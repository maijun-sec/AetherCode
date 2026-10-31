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

    /**
     * Transition a task to a new status. Idempotent for terminal states.
     *
     * <p><b>R750d — this is a read-modify-write and must be atomic.</b> It
     * used to be {@code get()} then {@code put()}. Two threads
     * transitioning the <em>same</em> task interleave, both read the same
     * {@code prev}, and the second {@code put} overwrites the first — a
     * lost update that resurrects an older status. Observed as 6 of 3000
     * tasks stuck at PENDING after the scheduler had already run them: the
     * scheduler's bridge writes RUNNING/COMPLETED while the task body may
     * write its own transition, and one write vanished.
     *
     * <p><b>Contract deliberately preserved from the pre-R750d version.</b>
     * An earlier draft of this fix gated {@code notify} on "the status
     * actually changed" and returned the wrong Task, which broke three
     * existing suites: {@code TaskRegistryTest.listener_firesOnEveryChange}
     * (listener on every call), {@code PersistentTaskRegistry} (one
     * appended log line per call), and {@code restore_terminalStateIsSticky}
     * (needs the post-transition Task back). An "improvement" that breaks
     * three contracts is not an improvement.
     *
     * <p>{@link java.util.concurrent.ConcurrentHashMap#compute} holds the
     * per-key bin lock across the whole read-decide-write, so transitions
     * are serialised per task. {@code notify} is deliberately called OUTSIDE
     * the compute block: it invokes registered listeners, and holding a bin
     * lock across arbitrary user code is how a map turns into a deadlock.
     */
    public Task updateStatus(String taskId, TaskStatus next) {
        if (taskId == null) throw new IllegalArgumentException("taskId must not be null");
        if (next == null) throw new IllegalArgumentException("next must not be null");
        tasks.compute(taskId, (id, cur) -> {
            if (cur == null) throw new IllegalArgumentException("unknown task: " + id);
            if (cur.status().isTerminal()) {
                LOG.debug("task {} already terminal ({}), ignoring transition to {}",
                        id, cur.status(), next);
                return cur;
            }
            long now = System.currentTimeMillis();
            return new Task(cur.id(), cur.type(), next, cur.description(),
                    cur.parentTaskId(), cur.createdAtMs(),
                    next.isTerminal() ? now : cur.endedAtMs());
        });
        // Re-read for the post-state rather than trusting a captured value: by now
        // another thread may have advanced it again, and callers want the
        // current truth. notify() fires on EVERY call -- including one
        // ignored because the task is already terminal -- because
        // TaskRegistryTest.listener_firesOnEveryChange and
        // PersistentTaskRegistry (one appended log line per call) depend on
        // it. See the R750d contract note in the javadoc.
        Task after = tasks.get(taskId);
        notify(after);
        return after;
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
