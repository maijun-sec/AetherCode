/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
