package org.aethercode.runtime.tool;

import org.aethercode.runtime.config.Config;
import org.aethercode.runtime.llm.LLMProvider;
import org.aethercode.runtime.state.StateSnapshot;
import org.aethercode.runtime.store.Store;

import java.util.Map;

/**
 * Snapshot of the runtime handed to a tool's invoke function.
 *
 * <p>Java-native equivalent of langchain's <code>ToolRuntime</code>: the
 * tool can read state, call the model, hit the store, and inspect the
 * config without depending on a thread-local.</p>
 */
public record ToolRuntime(
        StateSnapshot state,
        LLMProvider llm,
        Store store,
        Config config,
        Map<String, Object> metadata
) {
    public ToolRuntime {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolRuntime of(
            StateSnapshot state,
            LLMProvider llm,
            Store store,
            Config config) {
        return new ToolRuntime(state, llm, store, config, Map.of());
    }

    public ToolRuntime withMetadata(Map<String, Object> additional) {
        return new ToolRuntime(state, llm, store, config, additional);
    }
}
