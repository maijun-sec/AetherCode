package org.aethercode.deepagents.langchain_compat.middleware;

import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Map;

/**
 * LangChain-compatible TodoListMiddleware.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.TodoListMiddleware}. Exposes
 * a {@code write_todos} tool the agent can use to track a list
 * of TODOs. The Java port keeps the todo list under
 * {@link #TODO_KEY} on the agent state's extensions map.</p>
 *
 * <p>Behavior matches the Python port:
 * <ul>
 *   <li>{@link #writeTodos(AgentState, List)} replaces the todo
 *       list.</li>
 *   <li>{@link #readTodos(AgentState)} returns the current
 *       list, or an empty list when none.</li>
 * </ul>
 */
public class TodoListMiddleware extends AgentMiddleware<AgentState, Object, ModelResponse> {

    /** Extension key under which the todo list is stored. */
    public static final String TODO_KEY = "todos";

    @Override
    public String name() { return "TodoListMiddleware"; }

    /** Replace the todo list with {@code todos}. */
    public AgentState writeTodos(AgentState state, List<Map<String, Object>> todos) {
        List<Map<String, Object>> next = todos == null ? List.of()
                : new java.util.ArrayList<>(todos);
        return state.withExtension(TODO_KEY, next);
    }

    /** Read the current todo list, or an empty list if none. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readTodos(AgentState state) {
        Object raw = state.extensions().get(TODO_KEY);
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    @Override
    public AgentState beforeModel(AgentState state, Object runtime) {
        return state;
    }

    @Override
    public ModelResponse wrapModelCall(java.util.function.BiFunction<List<?>, Object, ModelResponse> modelCall,
                                       List<?> messages,
                                       AgentState state,
                                       Object runtime) {
        return modelCall.apply(messages, runtime);
    }
}
