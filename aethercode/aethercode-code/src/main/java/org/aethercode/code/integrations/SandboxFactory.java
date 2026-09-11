package org.aethercode.code.integrations;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Sandbox factory.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.integrations.sandbox_factory} module.
 * Provides a small dispatch layer that routes a
 * {@link SandboxConfig} to a registered {@link SandboxProvider} by
 * provider name.</p>
 */
public final class SandboxFactory {
    private static final Logger LOGGER = Logger.getLogger(SandboxFactory.class.getName());

    private SandboxFactory() {}

    /**
     * Create a sandbox using the provider named in {@code config.provider()}.
     */
    public static SandboxProvider.SandboxHandle create(SandboxConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (config.provider() == null || config.provider().isEmpty()) {
            throw new IllegalArgumentException("config.provider must be set");
        }
        SandboxProvider provider = SandboxRegistry.get(config.provider());
        if (provider == null) {
            throw new IllegalArgumentException("Unknown sandbox provider: " + config.provider());
        }
        try {
            return provider.create(config);
        } catch (Exception e) {
            LOGGER.warning("Sandbox creation failed for " + config.provider() + ": " + e.getMessage());
            throw new RuntimeException(
                    "Failed to create sandbox for provider " + config.provider(), e);
        }
    }

    /** Convenience: create with an options map. */
    public static SandboxProvider.SandboxHandle create(String name, String provider,
                                                       Map<String, Object> options) {
        return create(new SandboxConfig(name, provider, options, java.util.List.of()));
    }
}
