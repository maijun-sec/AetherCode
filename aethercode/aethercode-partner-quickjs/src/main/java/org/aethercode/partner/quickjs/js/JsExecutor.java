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
import java.util.concurrent.ExecutorService;

/**
 * Factory for the QuickJS JavaScript execution backend.
 *
 * <p>1:1 port of the <code>quickjs_rs</code> surface the
 * <code>langchain_quickjs</code> REPL uses. A concrete implementation
 * wraps a real JavaScript engine (e.g. GraalVM JS, <code>javax.script</code>,
 * or a JNI binding to the Rust <code>quickjs</code> crate). The default
 * {@link JsExecutors#unsupported()} binding throws
 * {@link UnsupportedOperationException} on every operation so the rest
 * of the partner package can be compiled, tested, and wired up
 * before an actual JS engine is selected.</p>
 *
 * <p>The executor creates {@link JsWorker workers}; each worker owns a
 * dedicated thread plus a single {@link JsRuntime}, and is the unit of
 * isolation between LangGraph threads.</p>
 */
public interface JsExecutor {

    /**
     * Create a new worker named {@code name}. The worker owns a single
     * OS thread plus a {@link JsRuntime}; closing the worker disposes
     * the runtime.
     */
    JsWorker createWorker(String name);

    /**
     * Convenience: run {@code task} synchronously on a fresh one-shot
     * worker. Used by the REPL for ops that do not need a persistent
     * runtime (e.g. snapshot diffing).
     */
    default <T> T runIsolated(Callable<T> task) throws Exception {
        try (JsWorker w = createWorker("quickjs-isolated")) {
            return w.runSync(task);
        }
    }

    /**
     * Convenience: run {@code task} asynchronously on a fresh one-shot
     * worker, returning a future tied to the worker's thread.
     */
    default <T> CompletableFuture<T> runIsolatedAsync(Callable<T> task) {
        JsWorker w = createWorker("quickjs-isolated-async");
        CompletableFuture<T> result = w.runAsync(task);
        // Best-effort close when the future completes.
        result.whenComplete((r, e) -> w.close());
        return result;
    }

    /**
     * Optional executor service used to dispatch work to the worker's
     * thread. Implementations that prefer {@code runSync} returning the
     * caller's value can leave this as {@code null}.
     */
    default ExecutorService executorService() {
        return null;
    }
}
