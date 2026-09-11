package org.aethercode.deepagents.middleware;

import org.aethercode.deepagents.langchain_compat.langgraph.Command;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * Result of a {@code wrapModelCallWithEvents} invocation. Carries
 * the model response alongside an optional
 * {@link org.aethercode.deepagents.langchain_compat.langgraph.Command} that the
 * {@code DeepAgent} runtime merges into the agent state.
 *
 * <p>Mirrors the Python port's
 * {@code ModelResponse | ExtendedModelResponse} return type &mdash;
 * the {@code Command} field is {@code null} when the middleware has
 * no state updates to emit (a plain {@code ModelResponse}).</p>
 */
public record WrapModelCallResult(AIMessage aiMessage, Command command) {

    public WrapModelCallResult {
        if (aiMessage == null) {
            throw new IllegalArgumentException("aiMessage must not be null");
        }
    }

    /** Build a result with no state-update command. */
    public static WrapModelCallResult passthrough(AIMessage aiMessage) {
        return new WrapModelCallResult(aiMessage, null);
    }

    /** Build a result with both a model response and a state-update command. */
    public static WrapModelCallResult of(AIMessage aiMessage, Command command) {
        return new WrapModelCallResult(aiMessage, command);
    }

    /** Whether a state-update command is present. */
    public boolean hasCommand() { return command != null; }
}
