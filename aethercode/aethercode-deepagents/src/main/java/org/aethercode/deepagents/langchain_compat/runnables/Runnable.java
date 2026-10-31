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

package org.aethercode.deepagents.langchain_compat.runnables;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * LangChain-compatible Runnable interface.
 *
 * <p>Java-native port of
 * {@code langchain_core.runnables.Runnable}. The
 * single-method interface models a pipeline of operations
 * that can be composed with {@code |}, mapped, and bound to a
 * configuration. The Java port is a typed {@link Function}
 * facade; composition is provided by static helpers.</p>
 *
 * @param <I> input type
 * @param <O> output type
 */
@FunctionalInterface
public interface Runnable<I, O> {
    O invoke(I input, RunnableConfig config);

    default O invoke(I input) {
        return invoke(input, RunnableConfig.empty());
    }

    default CompletableFuture<O> ainvoke(I input, RunnableConfig config) {
        return CompletableFuture.supplyAsync(() -> invoke(input, config));
    }

    default CompletableFuture<O> ainvoke(I input) {
        return ainvoke(input, RunnableConfig.empty());
    }

    /** Sequential composition: {@code a.then(b)} is {@code b(a(x))}. */
    default <V> Runnable<I, V> then(Runnable<? super O, ? extends V> next) {
        return (input, cfg) -> next.invoke(invoke(input, cfg), cfg);
    }

    /** Map the output. */
    default <V> Runnable<I, V> map(Function<? super O, ? extends V> mapper) {
        return (input, cfg) -> mapper.apply(invoke(input, cfg));
    }

    /** Bind a default config. */
    default Runnable<I, O> withConfig(RunnableConfig cfg) {
        return (input, ignored) -> invoke(input, cfg);
    }

    /** Static {@code Runnable.of} factory. */
    static <I, O> Runnable<I, O> of(Function<I, O> fn) {
        return (input, cfg) -> fn.apply(input);
    }
}
