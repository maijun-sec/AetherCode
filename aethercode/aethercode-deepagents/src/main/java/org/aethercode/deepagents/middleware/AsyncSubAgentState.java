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
