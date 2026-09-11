package org.aethercode.core.runtime.llm;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * In-process registry for the active {@link ChatModelFactory}.
 *
 * <p>Mirrors the {@code init_chat_model} plug-point used by the
 * Python port. The runtime sets a factory once at boot time; the
 * resolver reads the current factory on every call. Tests can swap
 * the factory in and out via {@link #setCurrent(ChatModelFactory)}.</p>
 *
 * <p>The default factory is {@code null}, which makes
 * {@link ModelResolver#resolveModel(Object)} return the string
 * spec verbatim &mdash; a useful no-op for the Java port's
 * chat-model-free runtime.</p>
 */
public final class ChatModelFactoryRegistry {
    private static final Logger LOGGER = Logger.getLogger(ChatModelFactoryRegistry.class.getName());

    private static final AtomicReference<ChatModelFactory> CURRENT = new AtomicReference<>();

    private ChatModelFactoryRegistry() {}

    /** Set the active factory. Pass {@code null} to clear. */
    public static void setCurrent(ChatModelFactory factory) {
        CURRENT.set(factory);
    }

    /** Return the active factory, or {@code null} if none is set. */
    public static ChatModelFactory current() {
        return CURRENT.get();
    }

    /**
     * Convenience: install {@code factory} as the active factory
     * and return a {@link Runnable} that restores the previous
     * factory when run. Useful for tests.
     */
    public static Runnable installForTest(ChatModelFactory factory) {
        ChatModelFactory previous = CURRENT.getAndSet(factory);
        return () -> {
            if (!CURRENT.compareAndSet(factory, previous)) {
                LOGGER.warning("installForTest: factory was replaced before rollback");
            }
        };
    }
}
