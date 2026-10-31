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
