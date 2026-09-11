package org.aethercode.deepagents.langchain_compat.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LangChain-compatible BaseTool interface.
 *
 * <p>Java-native port of
 * {@code langchain_core.tools.BaseTool}. The interface is the
 * public surface that middleware and chat models can program
 * against; the {@link org.aethercode.deepagents.tools.Tool} interface in
 * the runtime package is a structural equivalent. The Java
 * port keeps both interfaces and lets adapters bridge between
 * them via {@link #of(String, String, java.util.function.BiFunction)}.</p>
 */
public interface BaseTool {

    String name();

    String description();

    /** JSON schema for the tool's arguments, or {@code null}. */
    Object argsSchema();

    /** Synchronously invoke the tool with the given arguments. */
    Object invoke(Map<String, Object> arguments) throws Exception;

    /** Async variant. */
    default CompletableFuture<Object> ainvoke(Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return invoke(arguments);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Wrap a function as a {@code BaseTool}. */
    static BaseTool of(String name,
                        String description,
                        java.util.function.BiFunction<Map<String, Object>, ToolContext, Object> fn) {
        return new FunctionalBaseTool(name, description, null, fn);
    }
}
