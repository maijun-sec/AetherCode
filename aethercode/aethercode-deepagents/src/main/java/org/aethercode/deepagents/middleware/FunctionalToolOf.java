package org.aethercode.deepagents.middleware;

import org.aethercode.deepagents.tools.Tool;
import org.aethercode.deepagents.tools.Tools;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Tiny adapter that exposes the package-private
 * {@code org.aethercode.deepagents.tools.FunctionalTool} factory to other
 * packages. We can't make {@code FunctionalTool} itself public
 * without churning the existing {@code org.aethercode.core.tools}
 * surface, so this class sits in the {@code middleware} package
 * and forwards {@code Tool.of(name, desc, fn)} calls through.
 */
final class FunctionalToolOf {
    private FunctionalToolOf() {}

    static Tool of(String name, String description,
                   BiFunction<Map<String, Object>, Tool.ToolContext, Object> fn) {
        return Tool.of(name, description, fn);
    }

    /**
     * Overload that also accepts an args schema. The {@code schema}
     * argument is exposed via {@link Tool#argsSchema()} for tests
     * and runtime consumers that need a structured argument
     * description (the Python port's Pydantic auto-generated
     * JSON schema).
     */
    static Tool of(String name, String description, Object argsSchema,
                   BiFunction<Map<String, Object>, Tool.ToolContext, Object> fn) {
        return Tools.of(name, description, argsSchema, fn);
    }
}

