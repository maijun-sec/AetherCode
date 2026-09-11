package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.tools.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * Middleware that provides a {@code write_todos} tool.
 *
 * <p>Java-native port of LangChain's
 * {@code langchain.agents.middleware.TodoListMiddleware}. The
 * {@code write_todos} tool lets the agent manage a list of TODOs;
 * the Java port stores the todo list under
 * {@link #TODO_KEY} in the agent state's extensions map.</p>
 *
 * <p>Beyond the basic state-management contract, the Java port also
 * enforces the parallel-write constraint the LangChain port
 * documents in its system prompt: when an {@link AIMessage}
 * contains more than one {@code write_todos} tool-use block, every
 * such call is rejected with an explanatory error and the todo list
 * is left untouched. The rule is "one write per model invocation".</p>
 */
public class TodoListMiddleware implements Middleware {

    /** Extension key under which the current todo list is stored. */
    public static final String TODO_KEY = "todos";

    /**
     * Extension key under which the middleware stores the set of
     * tool-use ids that should be rejected on the current iteration.
     * Each iteration's {@link #afterModel} hook recomputes this set
     * from the latest {@code AIMessage}; the next {@link #wrapToolCall}
     * consults it.
     */
    public static final String REJECTED_KEY = "__todo_rejected__";

    /** Name of the tool this middleware gates. */
    public static final String TOOL_NAME = "write_todos";

    /** Error string returned by the runtime for every rejected write. */
    public static final String REJECTION_ERROR =
            "Error: The `write_todos` tool should never be called multiple times in parallel. "
                    + "Please call it only once per model invocation to update the todo list.";

    @Override
    public String name() { return "TodoListMiddleware"; }

    /**
     * Replace the todo list with {@code todos}. Returns the updated
     * state with the new todo list stored under {@link #TODO_KEY}.
     */
    public AgentState setTodos(AgentState state, List<Map<String, Object>> todos) {
        List<Map<String, Object>> next = todos == null
                ? List.of()
                : new ArrayList<>(todos);
        return state.withExtension(TODO_KEY, next);
    }

    /** Read the current todo list, or an empty list if none. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTodos(AgentState state) {
        Object raw = state.extensions().get(TODO_KEY);
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    // ---------------------------------------------------------------
    //  Parallel-write detection
    // ---------------------------------------------------------------

    /**
     * Walk the AI message's content and count {@code write_todos}
     * tool-use blocks. Returns the {@code id}s of any that need to
     * be rejected (i.e. when the count is &gt; 1, all of them).
     */
    static Set<String> computeRejectedWriteTodoIds(AIMessage ai) {
        List<String> ids = new ArrayList<>();
        for (ContentBlock b : ai.content()) {
            if (b instanceof ContentBlock.ToolUseBlock tu && TOOL_NAME.equals(tu.name())) {
                ids.add(tu.id());
            }
        }
        if (ids.size() <= 1) return Set.of();
        return new HashSet<>(ids);
    }

    @Override
    public AgentState afterModel(AgentState state, AIMessage ai, Runtime runtime) {
        Set<String> rejected = computeRejectedWriteTodoIds(ai);
        if (rejected.isEmpty()) {
            return state; // No annotation needed; default behavior.
        }
        return state.withExtension(REJECTED_KEY, new ArrayList<>(rejected));
    }

    @Override
    public Object wrapToolCall(Tool tool,
                                Map<String, Object> arguments,
                                AgentState state,
                                Runtime runtime) throws Exception {
        if (!TOOL_NAME.equals(tool.name())) {
            return tool.invoke(arguments);
        }
        Object raw = state.extensions().get(REJECTED_KEY);
        if (raw instanceof List<?> rejected) {
            // The afterModel hook tagged this iteration's write_todos
            // calls as parallel; the dispatch layer turns each one into
            // an error ToolMessage. We signal rejection via a marker
            // that the runtime detects and translates.
            throw new TodoWriteRejectedException(REJECTION_ERROR);
        }
        return tool.invoke(arguments);
    }

    /**
     * Thrown by {@link #wrapToolCall} when a {@code write_todos} call
     * arrives as part of a parallel-write batch. The runtime catches
     * this and produces a {@code ToolMessage} with
     * {@code status="error"} and the explanatory text. Exposed
     * publicly so the runtime can detect and translate it.
     */
    public static final class TodoWriteRejectedException extends RuntimeException {
        public TodoWriteRejectedException(String message) { super(message); }
    }
}

