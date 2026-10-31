/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
