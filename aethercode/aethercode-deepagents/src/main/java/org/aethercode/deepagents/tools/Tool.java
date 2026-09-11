package org.aethercode.deepagents.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A single tool the agent can invoke.
 *
 * <p>Java-native port of LangChain's {@code BaseTool}. The interface is
 * minimal so a tool can be backed by a method reference, a closure, or a
 * JSON-schema-validated handler.</p>
 *
 * <p>Tools are immutable; {@link #withDescription(String)} returns a new
 * tool with the description replaced.</p>
 */
public interface Tool {

    /** Stable identifier the model uses to call this tool. */
    String name();

    /** Human-readable description; surfaced in the model prompt. */
    String description();

    /**
     * JSON schema describing the tool's arguments, or {@code null} when the
     * tool takes no arguments. The model uses this to validate inputs.
     */
    Object argsSchema();

    /**
     * Invoke the tool with the given (already-validated) arguments.
     *
     * @param arguments Argument map keyed by argument name.
     * @return Tool result. Strings are surfaced verbatim; structured results
     *         may be any object the tool implementation chooses.
     * @throws Exception on tool failure; the runtime wraps this in a tool error message.
     */
    Object invoke(Map<String, Object> arguments) throws Exception;

    /** Async variant of {@link #invoke(Map)}. Default delegates to {@code invoke}. */
    default CompletableFuture<Object> ainvoke(Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return invoke(arguments);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Return a copy of this tool with the description replaced.
     * Implementations should preserve {@link #name()} and {@link #argsSchema()}.
     */
    Tool withDescription(String newDescription);

    /**
     * Convenience: a tool that wraps a single {@link java.util.function.BiFunction}.
     * Used to expose plain callables as agent tools without writing a class.
     */
    static Tool of(String name,
                   String description,
                   java.util.function.BiFunction<Map<String, Object>, ToolContext, Object> fn) {
        return new FunctionalTool(name, description, null, fn);
    }

    /** Per-invocation context passed to functional tools. */
    record ToolContext(List<String> recentMessages) {}
}
