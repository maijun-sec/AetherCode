package org.aethercode.code.tui.modals.plugin_manager;

import org.aethercode.code.plugins.PluginLifecycle;
import org.aethercode.code.plugins.PluginMarketplaceLoader;
import org.aethercode.code.plugins.PluginModels;
import org.aethercode.code.plugins.PluginStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plugin manager state loading.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.modals.plugin_manager.state} module.
 * The Java port reduces the data into the
 * {@link PluginTab.ManagerState} record consumed by the
 * content builder.</p>
 */
public final class PluginManagerState {
    private static final Logger LOGGER = Logger.getLogger(PluginManagerState.class.getName());

    private PluginManagerState() {}

    /**
     * Load the manager state.
     */
    public static PluginTab.ManagerState load(Set<String> loadedPluginIds) {
        List<String> errors = new ArrayList<>();
        List<PluginTab.PluginRow> available = new ArrayList<>();
        List<PluginTab.PluginRow> installed = new ArrayList<>();
        List<PluginTab.MarketplaceRow> marketplaces = new ArrayList<>();
        Map<String, PluginModels.MarketplaceRecord> records;
        Set<String> enabled;
        Set<String> installedIds;
        try {
            records = PluginStore.loadMarketplaceRecords();
            enabled = PluginStore.loadEnabledPluginIds();
            installedIds = PluginStore.loadInstalledPlugins().keySet();
        } catch (IOException e) {
            errors.add("Could not read plugin store: " + e.getMessage());
            return new PluginTab.ManagerState(available, installed, marketplaces, errors);
        }
        for (Map.Entry<String, PluginModels.MarketplaceRecord> e : records.entrySet()) {
            PluginModels.MarketplaceRecord record = e.getValue();
            PluginModels.PluginMarketplace marketplace;
            try {
                marketplace = PluginMarketplaceLoader.loadMarketplaceLocation(
                        java.nio.file.Path.of(record.installLocation()));
            } catch (RuntimeException exc) {
                errors.add(record.name() + ": " + PluginMarketplaceLoader.redactUrlsInText(exc.getMessage()));
                marketplaces.add(new PluginTab.MarketplaceRow(
                        record.name(),
                        PluginMarketplaceLoader.redactMarketplaceSource(record.source()),
                        null, countInstalled(installedIds, record.name()),
                        exc.getMessage()));
                continue;
            }
            marketplaces.add(new PluginTab.MarketplaceRow(
                    marketplace.name(),
                    PluginMarketplaceLoader.redactMarketplaceSource(record.source()),
                    marketplace.plugins().size(),
                    countInstalled(installedIds, marketplace.name()),
                    null));
            for (PluginModels.MarketplacePluginEntry plugin : marketplace.plugins()) {
                String pluginId = plugin.name() + "@" + marketplace.name();
                PluginTab.PluginRow row = buildRow(pluginId, plugin,
                        enabled.contains(pluginId),
                        installedIds.contains(pluginId),
                        loadedPluginIds);
                if (installedIds.contains(pluginId)) installed.add(row);
                else available.add(row);
            }
        }
        return new PluginTab.ManagerState(available, installed, marketplaces, errors);
    }

    private static int countInstalled(Set<String> installedIds, String marketplaceName) {
        int count = 0;
        for (String id : installedIds) {
            if (id.endsWith("@" + marketplaceName)) count++;
        }
        return count;
    }

    private static PluginTab.PluginRow buildRow(String pluginId,
                                                PluginModels.MarketplacePluginEntry entry,
                                                boolean isEnabled,
                                                boolean isInstalled,
                                                Set<String> loadedPluginIds) {
        return new PluginTab.PluginRow(
                pluginId,
                entry.description() != null ? entry.description() : "",
                isEnabled,
                null,
                extractAuthor(entry.author()),
                entry.displayName(),
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                loadedPluginIds.contains(pluginId),
                null);
    }

    private static String extractAuthor(Object author) {
        if (author instanceof String s) return s;
        if (author instanceof Map<?, ?> m) {
            Object name = m.get("name");
            if (name instanceof String s) return s;
        }
        return null;
    }

    /** Helper: build a {@link PluginTab.PluginRow} from a marketplace entry. */
    static PluginTab.PluginRow buildRow$1(
            String pluginId,
            PluginModels.MarketplacePluginEntry entry,
            boolean isEnabled,
            boolean isInstalled,
            Set<String> loadedPluginIds) {
        return new PluginTab.PluginRow(
                pluginId,
                entry.description() != null ? entry.description() : "",
                isEnabled,
                null,
                extractAuthor(entry.author()),
                entry.displayName(),
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                loadedPluginIds.contains(pluginId),
                null);
    }
}
