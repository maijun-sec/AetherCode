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

package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for modal dialogs ported from Textual's {@code ModalScreen}.
 *
 * <p>Mirrors the Python {@code ModalScreen<T>} contract: the screen is
 * pushed, gathers a user choice, and dismisses with a value of type
 * {@code T} (or {@code null} for cancel/Esc). The Java port uses
 * {@link CompletableFuture} in place of asyncio's
 * {@code Future}/{@code AwaitComplete}.</p>
 *
 * <p>The {@link Widget#dismissFuture()} is parameterized at the subclass
 * level via {@link #dismiss(Object)}; the host casts as needed.</p>
 */
public abstract class ModalScreen<T> extends Widget {

    protected ModalScreen(String id, String classes) {
        super(id, classes);
    }

    /** Dismiss with the chosen value, resolving the dismiss future. */
    @Override
    @SuppressWarnings("unchecked")
    public void dismiss(Object value) {
        super.dismiss(value);
    }

    /** Convenience: dismiss with {@code null} (Esc). */
    public void dismissNone() {
        dismiss(null);
    }

    /**
     * Returns the dismiss future typed to {@code T}. This shadows the
     * parent's {@code CompletableFuture<Object>}-typed future via an
     * unchecked cast. The actual value is whatever was passed to
     * {@link #dismiss(Object)}.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<T> typedDismissFuture() {
        return (CompletableFuture<T>) super.dismissFuture();
    }

    /** Bindings surfaced by the host as help text. Subclasses override. */
    @Override
    public List<KeyBinding> bindings() {
        return List.of();
    }
}
