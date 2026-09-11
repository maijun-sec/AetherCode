package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local context surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.local_context}
 * module. The Java port exposes the small in-memory cache the TUI uses
 * to track local-only state (the in-flight prompt, the model picker
 * selection, etc.) without leaking it into the agent's state graph.</p>
 */
public final class LocalContext {
    private LocalContext() {}

    private final Map<String, Object> store = new LinkedHashMap<>();

    /** Put a value. */
    public void put(String key, Object value) {
        store.put(key, value);
    }

    /** Get a value. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) store.get(key);
    }

    /** Remove a value. */
    public void remove(String key) {
        store.remove(key);
    }

    /** Clear all entries. */
    public void clear() {
        store.clear();
    }

    /** Snapshot of the current store. */
    public Map<String, Object> snapshot() {
        return Map.copyOf(store);
    }
}
