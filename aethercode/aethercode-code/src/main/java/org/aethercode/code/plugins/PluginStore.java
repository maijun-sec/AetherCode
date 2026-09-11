package org.aethercode.code.plugins;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plugin storage (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.plugins.store}
 * module. The Java port returns empty results; the full storage
 * implementation lands with the deepagents-code.plugins subdirectory
 * port.</p>
 */
public final class PluginStore {
    private PluginStore() {}

    /** List available plugins. */
    public static List<PluginLifecycle.PluginsTuple> listAvailable() {
        return List.of();
    }

    /** Install a plugin. */
    public static PluginModels.PluginInstance installPlugin(String pluginId) throws IOException {
        throw new IOException("Plugin install not implemented");
    }

    /** Uninstall a plugin. */
    public static void uninstallPlugin(String pluginId) throws IOException {
        throw new IOException("Plugin uninstall not implemented");
    }

    /** Enable or disable an installed plugin. */
    public static void setInstalledPluginEnabled(String pluginId, boolean enabled) throws IOException {
        throw new IOException("Plugin enable/disable not implemented");
    }

    /** Remove a marketplace. */
    public static boolean removeMarketplace(String name) throws IOException {
        return false;
    }

    /** Load marketplace records. */
    public static Map<String, PluginModels.MarketplaceRecord> loadMarketplaceRecords() throws IOException {
        return new LinkedHashMap<>();
    }

    /** Load the set of enabled plugin ids. */
    public static Set<String> loadEnabledPluginIds() throws IOException {
        return Set.of();
    }

    /** Load installed plugins keyed by id. */
    public static Map<String, PluginModels.PluginInstance> loadInstalledPlugins() throws IOException {
        return new LinkedHashMap<>();
    }
}
