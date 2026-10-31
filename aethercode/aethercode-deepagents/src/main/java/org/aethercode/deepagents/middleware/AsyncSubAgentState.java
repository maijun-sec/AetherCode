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

package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State extension for async subagent task tracking.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.async_subagents.AsyncSubAgentState}.
 * The Python port extends the {@code AgentState} TypedDict with
 * an {@code async_tasks} field; the Java port uses a wrapper
 * around {@link AgentState} so consumers can read/write the
 * {@code async_tasks} map without losing the parent state's
 * messages / files / extensions.</p>
 *
 * <p>The class also serves as a marker for
 * {@link AsyncSubAgentMiddleware#stateSchema()} so the runtime
 * can attach the right state type when the middleware is
 * wired into a graph.</p>
 */
public final class AsyncSubAgentState {

    /** State key the middleware reads / writes for the task map. */
    public static final String ASYNC_TASKS_KEY = "async_tasks";

    private final AgentState base;

    public AsyncSubAgentState(AgentState base) {
        this.base = base == null ? AgentState.empty() : base;
    }

    public AsyncSubAgentState() {
        this(AgentState.empty());
    }

    /** Underlying agent state. */
    public AgentState base() { return base; }

    public java.util.List<Message> messages() { return base.messages(); }
    @SuppressWarnings("unchecked")
    public Map<String, org.aethercode.core.fs.backend.FileData> files() {
        return (Map<String, org.aethercode.core.fs.backend.FileData>) (Map<?, ?>) base.files();
    }
    public Map<String, Object> extensions() { return base.extensions(); }

    /** Read the async-tasks map (never null, may be empty). */
    @SuppressWarnings("unchecked")
    public Map<String, AsyncTask> asyncTasks() {
        Object raw = base.extensions().get(ASYNC_TASKS_KEY);
        if (raw instanceof Map<?, ?> m) {
            Map<String, AsyncTask> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof AsyncTask t) {
                    out.put(k, t);
                }
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    /** Merge a partial update into the async-tasks map (last-write-wins). */
    public AsyncSubAgentState mergeTasks(Map<String, AsyncTask> update) {
        if (update == null || update.isEmpty()) return this;
        Map<String, Object> next = new LinkedHashMap<>(base.extensions());
        Map<String, AsyncTask> current = new LinkedHashMap<>(asyncTasks());
        current.putAll(update);
        next.put(ASYNC_TASKS_KEY, current);
        return new AsyncSubAgentState(base.withExtensions(next));
    }
}
