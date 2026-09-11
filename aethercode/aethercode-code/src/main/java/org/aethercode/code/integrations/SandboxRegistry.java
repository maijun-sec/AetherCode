package org.aethercode.code.integrations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry of named sandbox providers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.integrations.sandbox_registry} module.</p>
 */
public final class SandboxRegistry {
    private static final Logger LOGGER = Logger.getLogger(SandboxRegistry.class.getName());
    private static final Map<String, SandboxProvider> PROVIDERS = new ConcurrentHashMap<>();

    private SandboxRegistry() {}

    /** Register a provider under a name. */
    public static void register(String name, SandboxProvider provider) {
        PROVIDERS.put(name, provider);
    }

    /** Look up a provider by name. */
    public static SandboxProvider get(String name) {
        return PROVIDERS.get(name);
    }

    /** List registered provider names. */
    public static List<String> names() {
        return List.copyOf(PROVIDERS.keySet());
    }

    /** Resolve and create a sandbox. */
    public static SandboxProvider.SandboxHandle create(String name, SandboxConfig config) {
        SandboxProvider provider = PROVIDERS.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown sandbox provider: " + name);
        }
        try {
            return provider.create(config);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to create sandbox for " + name, e);
            throw new RuntimeException("Failed to create sandbox '" + name + "': " + e.getMessage(), e);
        }
    }
}
