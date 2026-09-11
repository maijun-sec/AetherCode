package org.aethercode.code.tui.modals.plugin_manager;

/**
 * Clickable tab labels for the plugin manager header.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.modals.plugin_manager.tabs} module.
 * The Java port exposes the data needed to render tabs; the
 * Textual-style {@code Static} subclass lives in the runtime
 * widget tree and is not ported.</p>
 */
public final class PluginManagerTabs {
    private PluginManagerTabs() {}

    /** A click event produced by a tab label. */
    public record Click(PluginTab.Tab tab) {}

    /**
     * A label descriptor for a tab.
     */
    public record TabLabel(PluginTab.Tab tab, String label, boolean active) {
        public String displayText() {
            return active ? "> " + label + " <" : "  " + label + "  ";
        }
    }

    /**
     * Build a label with the active marker.
     */
    public static TabLabel label(PluginTab.Tab tab, boolean active) {
        return new TabLabel(tab, PluginTab.tabLabels().get(tab), active);
    }
}
