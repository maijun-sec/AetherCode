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
