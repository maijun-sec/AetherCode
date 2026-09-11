package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-app debug console (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._debug} module.
 * The Java port exposes the small listener-driven API the TUI uses to
 * pipe log lines into the in-app debug console.</p>
 */
public final class Debug {
    private Debug() {}

    private static final Logger LOG = LoggerFactory.getLogger(Debug.class);

    private static final CopyOnWriteArrayList<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    /** Add a debug-line listener. */
    public static void addListener(Consumer<String> listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    /** Remove a debug-line listener. */
    public static void removeListener(Consumer<String> listener) {
        LISTENERS.remove(listener);
    }

    /** Emit a debug line. */
    public static void emit(String line) {
        if (line == null) return;
        LOG.debug(line);
        for (Consumer<String> l : LISTENERS) {
            try { l.accept(line); } catch (Exception ignored) {}
        }
    }
}
