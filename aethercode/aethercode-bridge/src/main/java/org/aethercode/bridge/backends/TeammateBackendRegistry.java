package org.aethercode.bridge.backends;

import org.aethercode.bridge.SwarmCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * backend registry. Maps a backend name to an implementation. The
 * {@code in-process} backend is the default; the others are loaded on demand.
 */
public class TeammateBackendRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TeammateBackendRegistry.class);
    private static final Map<String, TeammateBackend> BACKENDS = new ConcurrentHashMap<>();

    static {
        // in-process is built into SwarmCoordinator — not registered here.
        register(new TmuxBackend());
        register(new ITerm2Backend());
    }

    public static void register(TeammateBackend b) { BACKENDS.put(b.name(), b); }

    public static TeammateBackend get(String name) {
        return BACKENDS.get(name);
    }

    public static java.util.Set<String> names() { return BACKENDS.keySet(); }
}
