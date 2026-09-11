package org.aethercode.deepagents.tools;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Public factory helpers for building {@link Tool} instances.
 *
 * <p>Java-native companion to the package-private
 * {@link FunctionalTool}. Adds overloads that accept an
 * {@code argsSchema} payload so consumers (e.g.
 * {@link org.aethercode.deepagents.middleware.FilesystemToolset}) can attach
 * a structured JSON-schema description to each tool. The
 * {@link Tool#argsSchema()} getter exposes the schema to runtime
 * adapters and to tests that need to validate argument
 * descriptions.</p>
 */
public final class Tools {
    private Tools() {}

    /** Build a {@link Tool} with an explicit args schema. */
    public static Tool of(String name,
                          String description,
                          Object argsSchema,
                          BiFunction<Map<String, Object>, Tool.ToolContext, Object> fn) {
        return new FunctionalTool(name, description, argsSchema, fn);
    }
}
