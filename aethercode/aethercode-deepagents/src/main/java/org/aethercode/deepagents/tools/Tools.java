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
