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

import org.aethercode.core.app.AppState;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-task execution scope. Bound to a {@link Task} ID (NOT to an Agent —
 * the same Agent can run many tasks). The TaskContext carries:
 *
 * <ul>
 *   <li>an {@link AtomicBoolean} abort signal (cancels the task's work);</li>
 *   <li>the shared {@link AppState} (so the agent loop can read / write
 *       the session-wide transcript, tool pool, attributes, todo list);</li>
 *   <li>a task-scoped memory layer ({@link #taskMemoryRoot()}) — the
 *       agent can persist per-task notes that vanish when the task ends;</li>
 *   <li>parent / child relationships (so a subagent can find its
 *       originating task and any siblings).</li>
 * </ul>
 *
 * <p>Subagents get a fresh {@code TaskContext} per invocation of
 * AgentTool, so concurrent sub-tasks do not share an abort signal.
 */
public final class TaskContext {

    private final Task task;
    private final AppState appState;
    private final java.nio.file.Path taskMemoryRoot;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final TaskRegistry registry;
    private final TaskContext parent;

    public TaskContext(Task task,
                       AppState appState,
                       java.nio.file.Path taskMemoryRoot,
                       TaskRegistry registry,
                       TaskContext parent) {
        this.task = Objects.requireNonNull(task, "task");
        this.appState = Objects.requireNonNull(appState, "appState");
        this.taskMemoryRoot = Objects.requireNonNull(taskMemoryRoot, "taskMemoryRoot");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.parent = parent;
    }

    public Task task() { return task; }
    public AppState appState() { return appState; }
    public java.nio.file.Path taskMemoryRoot() { return taskMemoryRoot; }
    public TaskRegistry registry() { return registry; }
    public TaskContext parent() { return parent; }

    public boolean isAborted() { return aborted.get(); }
    public void abort() { aborted.set(true); }

    /** Sibling / child task list at the time of this call. */
    public java.util.List<Task> children() {
        return registry.listChildren(task.id());
    }
}
