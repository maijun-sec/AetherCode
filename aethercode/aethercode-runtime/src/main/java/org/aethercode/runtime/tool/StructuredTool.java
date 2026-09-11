package org.aethercode.runtime.tool;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A schema-driven tool whose arguments are a JSON object.
 *
 * <p>Java-native equivalent of langchain's <code>StructuredTool</code>.
 * The agent validates the LLM's argument payload against {@link #schema()}
 * before invoking {@link #invoke(ToolCallRequest, ToolRuntime)}.</p>
 */
public record StructuredTool(
        String name,
        String description,
        Map<String, Object> schema,                  // JSON-Schema
        BiFunction<ToolCallRequest, ToolRuntime, Object> invoke
) implements Tool {

    public StructuredTool {
        Objects.requireNonNull(name, "StructuredTool.name");
        Objects.requireNonNull(description, "StructuredTool.description");
        Objects.requireNonNull(schema, "StructuredTool.schema");
        Objects.requireNonNull(invoke, "StructuredTool.invoke");
        schema = Map.copyOf(schema);
    }

    public static StructuredTool of(
            String name,
            String description,
            Map<String, Object> schema,
            BiFunction<ToolCallRequest, ToolRuntime, Object> invoke) {
        return new StructuredTool(name, description, schema, invoke);
    }

    @Override
    public Map<String, Object> toJsonSchema() {
        return Map.of(
                "name", name,
                "description", description,
                "input_schema", schema);
    }
}
