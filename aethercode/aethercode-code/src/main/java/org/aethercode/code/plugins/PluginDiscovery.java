package org.aethercode.code.plugins;

import java.nio.file.Path;
import java.util.List;

/**
 * Plugin discovery (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.plugins.discovery}
 * module. The Java port returns an empty result; the full discovery
 * logic lands with the deepagents-code.plugins subdirectory port.</p>
 */
public final class PluginDiscovery {
    private PluginDiscovery() {}

    /** Discover installed plugins. */
    public static PluginModels.PluginDiscoveryResult discover() {
        return new PluginModels.PluginDiscoveryResult(List.of(), List.of());
    }

    /** Backwards-compatible alias used by some adapter code. */
    public static PluginModels.PluginDiscoveryResult plugins() {
        return discover();
    }

    /** Find a plugin's manifest path. Returns {@code null} for the stub. */
    public static Path findManifestPath(Path pluginRoot) {
        if (pluginRoot == null) return null;
        return pluginRoot.resolve("plugin.json");
    }
}
