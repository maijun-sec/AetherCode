package org.aethercode.deepagents.langchain_compat.tools;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Functional implementation of {@link BaseTool}.
 *
 * <p>Java-native port of LangChain's
 * {@code StructuredTool.from_function(...)} factory. Wraps a
 * two-arg function ({@code args}, {@code ctx}) as a tool.</p>
 */
final class FunctionalBaseTool implements BaseTool {
    private final String name;
    private final String description;
    private final Object argsSchema;
    private final BiFunction<Map<String, Object>, ToolContext, Object> fn;

    FunctionalBaseTool(String name,
                         String description,
                         Object argsSchema,
                         BiFunction<Map<String, Object>, ToolContext, Object> fn) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.argsSchema = argsSchema;
        this.fn = Objects.requireNonNull(fn, "fn");
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return description; }

    @Override
    public Object argsSchema() { return argsSchema; }

    @Override
    public Object invoke(Map<String, Object> arguments) {
        return fn.apply(arguments, ToolContext.empty());
    }
}
