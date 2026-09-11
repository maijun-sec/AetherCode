package org.aethercode.code.plugins;

import java.io.IOException;
import java.util.List;

/**
 * High-level plugin operations façade.
 *
 * <p>Java 21 port of the Python {@code deepagents_code.plugins.__init__}
 * public surface. Concrete implementations delegate to
 * {@link PluginDiscovery}, {@link PluginStore}, and
 * {@link PluginMarketplaceLoader}.</p>
 */
public final class PluginLifecycle {
    private PluginLifecycle() {}

    /** A triple of {@code (id, description, enabled)}. */
    public record PluginsTuple(String id, String description, boolean enabled) {}

    /**
     * Run plugin discovery and return the discovered plugins.
     */
    public static PluginModels.PluginDiscoveryResult discoverPlugins() {
        return PluginDiscovery.discover();
    }

    /**
     * List all available plugins as {@code (id, description, enabled)} tuples.
     */
    public static List<PluginsTuple> listAvailablePlugins() {
        return PluginStore.listAvailable();
    }

    /**
     * Install a plugin by id.
     *
     * @return the installed {@link PluginModels.PluginInstance}
     */
    public static PluginModels.PluginInstance installPlugin(String pluginId) throws IOException {
        return PluginStore.installPlugin(pluginId);
    }

    /** Uninstall a plugin by id. */
    public static void uninstallPlugin(String pluginId) throws IOException {
        PluginStore.uninstallPlugin(pluginId);
    }

    /** Enable or disable an installed plugin. */
    public static void setInstalledPluginEnabled(String pluginId, boolean enabled) throws IOException {
        PluginStore.setInstalledPluginEnabled(pluginId, enabled);
    }

    /** Add a marketplace source (directory, file, github, git, or url). */
    public static PluginModels.PluginMarketplace addMarketplaceSource(String source) throws IOException {
        return PluginMarketplaceLoader.addSource(source);
    }

    /** Remove a configured marketplace and uninstall its plugins. */
    public static boolean removeMarketplace(String name) throws IOException {
        return PluginStore.removeMarketplace(name);
    }
}
