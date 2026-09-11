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
