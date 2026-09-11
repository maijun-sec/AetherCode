package org.aethercode.deepagents.tools;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Backed-by-BiFunction implementation of {@link Tool}.
 *
 * <p>The default factory {@link Tool#of(String, String, BiFunction)} returns
 * one of these. The {@link #withDescription(String)} override returns a
 * fresh copy with the new description; the function reference is shared.</p>
 */
record FunctionalTool(
        String name,
        String description,
        Object argsSchema,
        BiFunction<Map<String, Object>, Tool.ToolContext, Object> fn
) implements Tool {

    FunctionalTool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fn, "fn");
    }

    @Override
    public Object invoke(Map<String, Object> arguments) {
        return fn.apply(arguments, new Tool.ToolContext(java.util.List.of()));
    }

    @Override
    public Tool withDescription(String newDescription) {
        return new FunctionalTool(name, newDescription, argsSchema, fn);
    }
}
