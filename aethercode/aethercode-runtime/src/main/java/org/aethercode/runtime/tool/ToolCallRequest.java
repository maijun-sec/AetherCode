package org.aethercode.runtime.tool;

import org.aethercode.runtime.message.ToolCall;
import org.aethercode.runtime.store.Store;
import org.aethercode.runtime.config.Config;
import org.aethercode.runtime.llm.LLMProvider;
import org.aethercode.runtime.state.StateSnapshot;

import java.util.Map;
import java.util.Optional;

/**
 * The data handed to a tool's invoke function.
 *
 * <p>Java-native equivalent of langchain's <code>ToolCallRequest</code>
 * (a tuple of {@link ToolCall} and {@link ToolRuntime}). The runtime
 * exposes the model, current state, store, and config so the tool can
 * reach them without a thread-local.</p>
 */
public record ToolCallRequest(
        ToolCall call,
        StateSnapshot state,
        LLMProvider llm,
        Store store,
        Config config,
        Map<String, Object> metadata
) {
    public ToolCallRequest {
        if (call == null) {
            throw new IllegalArgumentException("ToolCallRequest.call is required");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public ToolCallRequest(ToolCall call, ToolRuntime runtime) {
        this(
                call,
                runtime.state(),
                runtime.llm(),
                runtime.store(),
                runtime.config(),
                runtime.metadata());
    }

    public String toolName()        { return call.name(); }
    public String toolCallId()      { return call.id(); }
    public Map<String, Object> args() { return call.args(); }
}
