package org.aethercode.runtime.tool;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A free-form callable tool that accepts a raw string and returns a string.
 *
 * <p>Java-native equivalent of langchain's <code>Tool.from_function</code>
 * (i.e. the "dynamic" tool variant). Use this for tools whose argument
 * model is not expressible as JSON-Schema, or for ad-hoc one-liners.</p>
 */
public record DynamicTool(
        String name,
        String description,
        BiFunction<String, ToolRuntime, String> invoke
) implements Tool {

    public DynamicTool {
        Objects.requireNonNull(name, "DynamicTool.name");
        Objects.requireNonNull(description, "DynamicTool.description");
        Objects.requireNonNull(invoke, "DynamicTool.invoke");
    }

    public static DynamicTool of(
            String name,
            String description,
            BiFunction<String, ToolRuntime, String> invoke) {
        return new DynamicTool(name, description, invoke);
    }

    @Override
    public Map<String, Object> toJsonSchema() {
        return Map.of(
                "name", name,
                "description", description,
                "input_schema", Map.of("type", "string"));
    }
}
