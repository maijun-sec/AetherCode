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
