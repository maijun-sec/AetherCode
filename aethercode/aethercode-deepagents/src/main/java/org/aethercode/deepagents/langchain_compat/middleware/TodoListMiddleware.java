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
