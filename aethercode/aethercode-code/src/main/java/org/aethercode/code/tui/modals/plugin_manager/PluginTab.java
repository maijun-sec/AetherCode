package org.aethercode.code.tui.modals.plugin_manager;

import java.util.List;
import java.util.Map;

/**
 * Plugin manager view models.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.modals.plugin_manager.models} module.</p>
 */
public final class PluginTab {
    private PluginTab() {}

    /** Tab id. */
    public enum Tab { DISCOVER, INSTALLED, MARKETPLACES, ERRORS, SETTINGS }

    /** View id. */
    public enum View {
        LIST, ADD_MARKETPLACE, PLUGIN_DETAILS, INSTALLED_DETAILS,
        MARKETPLACE_DETAILS, CONFIRM_REMOVE_MARKETPLACE
    }

    /** Action the manager asks the app to take once it closes. */
    public enum Action { RELOAD, LATER, CHECK_FAILED }

    /** Close-check phase. */
    public enum ClosePhase { BROWSING, CHECKING, RELOAD_PROMPT }

    /** Plugin load state. */
    public enum LoadState { DISABLED, PENDING_RELOAD, ENABLED, ERROR }

    /**
     * A single row in the discover/installed tabs.
     */
    public record PluginRow(
            String pluginId,
            String description,
            boolean enabled,
            String version,
            String author,
            String displayName,
            Integer skillCount,
            List<String> skillNames,
            Boolean mcpConnected,
            List<String> mcpServerNames,
            List<String> mcpLoginServers,
            List<String> hookEvents,
            List<String> unsupportedComponents,
            boolean sessionLoaded,
            String loadError) {

        public LoadState loadState() {
            if (loadError != null) return LoadState.ERROR;
            if (enabled != sessionLoaded) return LoadState.PENDING_RELOAD;
            return enabled ? LoadState.ENABLED : LoadState.DISABLED;
        }

        public boolean hasSupportedComponents() {
            return !skillNames.isEmpty() || !mcpServerNames.isEmpty() || !hookEvents.isEmpty();
        }

        public String label() {
            if (displayName != null && !displayName.isEmpty()) return displayName;
            int at = pluginId.indexOf('@');
            return at < 0 ? pluginId : pluginId.substring(0, at);
        }
    }

    /**
     * A single row in the marketplaces tab.
     */
    public record MarketplaceRow(
            String name,
            String source,
            Integer pluginCount,
            int installedCount,
            String error) {
        public boolean hasError() { return error != null; }
    }

    /**
     * Snapshot of the manager state used by the modal.
     */
    public record ManagerState(
            List<PluginRow> availablePlugins,
            List<PluginRow> installedPlugins,
            List<MarketplaceRow> marketplaces,
            List<String> errors) {
    }

    /** Default tab labels keyed by tab id. */
    public static Map<Tab, String> tabLabels() {
        return Map.of(
                Tab.DISCOVER, "Plugins",
                Tab.INSTALLED, "Installed",
                Tab.MARKETPLACES, "Marketplaces",
                Tab.ERRORS, "Errors",
                Tab.SETTINGS, "Settings");
    }
}
