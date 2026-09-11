package org.aethercode.partner.quickjs.js;

import java.util.concurrent.CompletableFuture;

/**
 * A live reference to a JS value held by a {@link JsContext}.
 *
 * <p>1:1 port of <code>quickjs_rs.Handle</code>. Handles own native
 * memory and must be {@link #dispose() disposed} to avoid leaks. The
 * REPL disposes handles in a {@code finally} block after
 * {@link #toJava()}.</p>
 */
public interface JsHandle extends AutoCloseable {

    /** The JS {@code typeof} of the underlying value. */
    String typeOf();

    /** Whether this handle wraps a JS Promise. */
    boolean isPromise();

    /**
     * If {@link #isPromise()} is true, await the promise (with an
     * optional timeout) and return the resolved handle. Otherwise
     * return this handle.
     */
    CompletableFuture<JsHandle> awaitPromise(double timeout);

    /**
     * Marshal the handle's value into a Java object: native scalars
     * (string, number, boolean, null), lists as {@code List<Object>},
     * objects as {@code Map<String, Object>}, and {@code undefined} as
     * {@link JsUndefined#INSTANCE}. Throws {@link JsMarshalException}
     * for non-marshalable values (functions, symbols, circular refs).
     */
    Object toJava();

    /** Read a named property (for {@code [Function]} formatting). */
    JsHandle get(String name);

    /** Free the underlying native value. Idempotent. */
    void dispose();
}
