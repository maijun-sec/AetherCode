package org.aethercode.deepagents.langchain_compat.tools;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * LangChain-compatible StructuredTool.
 *
 * <p>Java-native port of
 * {@code langchain_core.tools.StructuredTool}. Built on top of
 * {@link BaseTool}; adds an {@code argsSchema} class that
 * defines the tool's argument structure for LLM structured
 * output. The Java port uses a {@link Class<?>} for the schema
 * when present, falling back to a {@code Map<String, Object>}
 * for JSON-schema style specs.</p>
 */
public final class StructuredTool implements BaseTool {
    private final String name;
    private final String description;
    private final Object argsSchema;
    private final BiFunction<Map<String, Object>, ToolContext, Object> fn;
    private final Function<Map<String, Object>, Map<String, Object>> validator;

    public StructuredTool(String name,
                          String description,
                          Object argsSchema,
                          BiFunction<Map<String, Object>, ToolContext, Object> fn,
                          Function<Map<String, Object>, Map<String, Object>> validator) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.argsSchema = argsSchema;
        this.fn = Objects.requireNonNull(fn, "fn");
        this.validator = validator;
    }

    public static StructuredTool fromFunction(String name,
                                                String description,
                                                Object argsSchema,
                                                BiFunction<Map<String, Object>, ToolContext, Object> fn) {
        return new StructuredTool(name, description, argsSchema, fn, null);
    }

    public static StructuredTool fromFunction(String name,
                                                String description,
                                                BiFunction<Map<String, Object>, ToolContext, Object> fn) {
        return fromFunction(name, description, null, fn);
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return description; }

    @Override
    public Object argsSchema() { return argsSchema; }

    @Override
    public Object invoke(Map<String, Object> arguments) {
        Map<String, Object> validated = validator != null
                ? validator.apply(arguments == null ? Map.of() : arguments)
                : (arguments == null ? Map.of() : arguments);
        return fn.apply(validated, ToolContext.empty());
    }

    /** Build from a {@link BaseTool}, optionally setting a schema. */
    public static StructuredTool fromBaseTool(BaseTool base, Object schema) {
        return new StructuredTool(
                base.name(),
                base.description(),
                schema == null ? base.argsSchema() : schema,
                (args, ctx) -> {
                    try {
                        return base.invoke(args);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                null);
    }
}
