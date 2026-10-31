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

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * LangChain-compatible agent middleware abstract class.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.AgentMiddleware}.
 * Mirrors the Python port's hook surface: each hook is a default
 * no-op that subclasses can override.</p>
 *
 * <p>Type parameters mirror Python generics:
 * <ul>
 *   <li>{@code StateT} &mdash; the state schema (defaults to
 *       {@link AgentState})</li>
 *   <li>{@code ContextT} &mdash; the runtime context type</li>
 *   <li>{@code ResponseT} &mdash; the response shape (defaults to
 *       {@link ModelResponse})</li>
 * </ul>
 */
public abstract class AgentMiddleware<StateT extends AgentState,
                                      ContextT,
                                      ResponseT extends ModelResponse> {

    /** Stable identifier used by HarnessProfile filtering. */
    public abstract String name();

    /** Optional human-readable description. */
    public String description() { return ""; }

    /**
     * Hook called before each model invocation. Return the
     * (possibly modified) state.
     */
    public StateT beforeModel(StateT state, ContextT runtime) {
        return state;
    }

    /**
     * Hook called after each model invocation. Receives the AI
     * message emitted by the chat model; may replace or augment
     * it.
     */
    public StateT afterModel(StateT state, Object aiMessage, ContextT runtime) {
        return state;
    }

    /**
     * Hook called to wrap the model call itself. The default
     * delegates to {@code modelCall}; middleware that needs
     * request rewriting, retries, or model selection overrides
     * this.
     */
    public ResponseT wrapModelCall(BiFunction<List<?>, ContextT, ResponseT> modelCall,
                                    List<?> messages,
                                    StateT state,
                                    ContextT runtime) {
        return modelCall.apply(messages, runtime);
    }

    /**
     * Hook called to wrap a single tool call. The default
     * delegates straight to {@code tool.invoke(arguments)};
     * middleware that needs retries, permission checks, or
     * result rewriting overrides this.
     */
    public Object wrapToolCall(Object tool,
                                 Map<String, Object> arguments,
                                 StateT state,
                                 ContextT runtime) throws Exception {
        return tool.getClass().getMethod("invoke", Map.class).invoke(tool, arguments);
    }
}
