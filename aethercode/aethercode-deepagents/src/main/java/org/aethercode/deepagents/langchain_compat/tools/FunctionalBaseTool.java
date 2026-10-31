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
