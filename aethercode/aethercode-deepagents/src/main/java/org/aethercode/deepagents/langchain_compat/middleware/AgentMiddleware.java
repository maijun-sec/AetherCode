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
