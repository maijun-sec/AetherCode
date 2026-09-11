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
