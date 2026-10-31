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

package org.aethercode.partner.quickjs.js;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;

/**
 * A dedicated OS thread that owns a single {@link JsRuntime}.
 *
 * <p>1:1 port of the <code>quickjs_rs.ThreadWorker</code> used by
 * <code>_repl.py</code>. All {@link JsRuntime} / {@link JsContext}
 * operations are dispatched onto the worker's thread because the
 * underlying engine is not safe to use from arbitrary threads.</p>
 *
 * <p>Public methods are safe to call from any thread / event loop; the
 * worker transparently hops to its own thread for the duration of the
 * task and returns the result back to the caller.</p>
 */
public interface JsWorker extends AutoCloseable {

    /** Stable name (used in thread dumps and logs). */
    String name();

    /**
     * Run {@code task} synchronously on the worker's thread and return
     * its result. Propagates checked and unchecked exceptions thrown
     * by the task to the caller.
     */
    <T> T runSync(Callable<T> task);

    /**
     * Run {@code task} asynchronously on the worker's thread and return
     * a future that completes with the task's result. Exceptions
     * surface via {@link CompletableFuture#completeExceptionally}.
     */
    <T> CompletableFuture<T> runAsync(Callable<T> task);

    /**
     * Stop the worker, close the owned runtime, and release the
     * underlying OS thread. Idempotent. After this call, every other
     * method throws {@link IllegalStateException}.
     */
    @Override
    void close();
}
