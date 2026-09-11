package org.aethercode.partner.quickjs.js;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A QuickJS execution context: an isolated global object backed by a
 * shared runtime heap.
 *
 * <p>1:1 port of <code>quickjs_rs.Context</code>. Context-level
 * {@code timeout} is the cumulative budget for sync evals; async evals
 * pass a per-call timeout instead so each call gets a fresh budget.</p>
 */
public interface JsContext {

    /** Per-call timeout in seconds. {@code 0} or {@code -1} disables. */
    double timeout();

    /**
     * Evaluate {@code code} synchronously and return the resulting
     * handle. The last expression's value is returned; statements
     * produce {@code undefined}.
     */
    JsHandle eval(String code);

    /**
     * Evaluate {@code code} asynchronously; resolves with the
     * resulting handle.
     */
    CompletableFuture<JsHandle> evalAsync(String code);

    /**
     * Asynchronous variant of {@link #eval(String)} that returns the
     * handle directly so callers can {@code await_promise} on the
     * final expression. The Python port uses this so a bare async
     * IIFE in the script can be awaited.
     */
    CompletableFuture<JsHandle> evalHandleAsync(String code, double timeout);

    /**
     * Register a host (Java) function as a JS global named
     * {@code name}. The host function can be sync or async
     * (<code>isAsync</code>). The returned value is the JS-visible
     * object/primitive the host returns; <code>undefined</code> maps
     * to {@link JsUndefined#INSTANCE}.
     */
    void register(String name, Function<Object[], Object> fn, boolean isAsync);

    /**
     * Decorator-style host function registration; equivalent to
     * {@code register(name, fn, isAsync=false)} but available for
     * sites that prefer the fluent call.
     */
    default void function(String name, Function<Object[], Object> fn) {
        register(name, fn, false);
    }

    /**
     * Capture the current heap + globals as a {@link JsSnapshot}. The
     * snapshot can be stored in the checkpointer and replayed back
     * into a fresh context via {@link JsRuntime#restoreSnapshot}.
     */
    JsSnapshot createSnapshot();

    /** Free the context. After this call every method throws. */
    void close();
}
