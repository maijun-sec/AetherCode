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

