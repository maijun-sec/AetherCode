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
