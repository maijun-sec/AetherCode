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
 * A free-form callable tool that accepts a raw string and returns a string.
 *
 * <p>Java-native equivalent of langchain's <code>Tool.from_function</code>
 * (i.e. the "dynamic" tool variant). Use this for tools whose argument
 * model is not expressible as JSON-Schema, or for ad-hoc one-liners.</p>
 */
public record DynamicTool(
        String name,
        String description,
        BiFunction<String, ToolRuntime, String> invoke
) implements Tool {

    public DynamicTool {
        Objects.requireNonNull(name, "DynamicTool.name");
        Objects.requireNonNull(description, "DynamicTool.description");
        Objects.requireNonNull(invoke, "DynamicTool.invoke");
    }

    public static DynamicTool of(
            String name,
            String description,
            BiFunction<String, ToolRuntime, String> invoke) {
        return new DynamicTool(name, description, invoke);
    }

    @Override
    public Map<String, Object> toJsonSchema() {
        return Map.of(
                "name", name,
                "description", description,
                "input_schema", Map.of("type", "string"));
    }
}
