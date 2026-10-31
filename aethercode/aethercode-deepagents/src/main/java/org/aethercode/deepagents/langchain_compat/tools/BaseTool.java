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
