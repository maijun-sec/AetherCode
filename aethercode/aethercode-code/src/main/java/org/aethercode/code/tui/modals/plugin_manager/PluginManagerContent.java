package org.aethercode.code.tui.modals.plugin_manager;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure plugin manager content builders.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.modals.plugin_manager.content} module.
 * The Java port produces data records; rendering is the host's
 * job.</p>
 */
public final class PluginManagerContent {
    private PluginManagerContent() {}

    /** A single list option. */
    public record Option(String text, String id, boolean disabled) {
        public static Option enabled(String text, String id) {
            return new Option(text, id, false);
        }
        public static Option disabled(String text, String id) {
            return new Option(text, id, true);
        }
    }

    /** A status line. */
    public record StatusLine(String text, String style) {}

    /** A plugin detail view payload. */
    public record PluginDetail(PluginTab.PluginRow row, List<StatusLine> status,
                                List<String> components, String description) {}

    /**
     * Build the list of options for a plugin list.
     */
    public static List<Option> pluginOptions(List<PluginTab.PluginRow> rows, String actionId) {
        List<Option> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PluginTab.PluginRow row = rows.get(i);
            if (i > 0) out.add(Option.disabled(" ", "spacer:" + i));
            out.add(Option.enabled(row.label(), actionId + ":" + row.pluginId()));
        }
        return out;
    }

    /**
     * Build the "install details" options.
     */
    public static List<Option> installDetailsOptions() {
        return List.of(
                Option.enabled("Install", "action:install"),
                Option.enabled("Back to plugin list", "details-back"));
    }

    /**
     * Build the "installed details" options.
     */
    public static List<Option> installedDetailsOptions(PluginTab.PluginRow row) {
        return List.of(
                Option.enabled(
                        row.enabled() ? "Disable plugin" : "Enable plugin",
                        "action:toggle-enabled"),
                Option.enabled("Uninstall", "action:uninstall"),
                Option.enabled("Back to plugin list", "details-back"));
    }

    /**
     * Build the status lines for a plugin.
     */
    public static List<StatusLine> statusLines(PluginTab.PluginRow row) {
        List<StatusLine> out = new ArrayList<>();
        switch (row.loadState()) {
            case ERROR -> {
                String detail = row.loadError() != null ? row.loadError() : "Plugin failed to load.";
                out.add(new StatusLine("Status: Error — " + detail, "dim"));
                out.add(new StatusLine("Fix the error, then run /reload.", "dim"));
            }
            case DISABLED -> {
                out.add(new StatusLine("Status: Disabled", "dim"));
                out.add(new StatusLine("Enable the plugin, then run /reload to load it.", "dim"));
            }
            case PENDING_RELOAD -> {
                if (row.enabled()) {
                    out.add(new StatusLine("Status: Installed · pending /reload", "dim"));
                    out.add(new StatusLine(
                            "Run /reload to load this plugin into the current session.", "dim"));
                } else {
                    out.add(new StatusLine("Status: Disabled · pending /reload", "dim"));
                    out.add(new StatusLine(
                            "Run /reload to unload this plugin from the current session.", "dim"));
                }
            }
            case ENABLED -> {
                out.add(new StatusLine("Status: ✓ Enabled", "success"));
                if (Boolean.FALSE.equals(row.mcpConnected())) {
                    out.add(new StatusLine(
                            "Run /reload to rebuild the agent with this plugin's MCP tools.",
                            "dim"));
                }
            }
        }
        return out;
    }

    /**
     * Build the component summary lines for a plugin detail.
     */
    public static List<String> componentSummary(PluginTab.PluginRow row) {
        List<String> lines = new ArrayList<>();
        if (!row.skillNames().isEmpty()) {
            lines.add("Skills: " + String.join(", ", row.skillNames()));
        } else if (row.skillCount() != null && row.skillCount() > 0) {
            lines.add("Skills: " + row.skillCount());
        }
        if (!row.mcpServerNames().isEmpty()) {
            lines.add("MCP: " + String.join(", ", row.mcpServerNames()));
        }
        if (!row.hookEvents().isEmpty()) {
            lines.add("Hooks: " + String.join(", ", row.hookEvents()));
        }
        if (!row.unsupportedComponents().isEmpty()) {
            String names = String.join(", ",
                    row.unsupportedComponents().stream().map(s -> s + "/").toList());
            lines.add("Unsupported (not loaded): " + names);
        }
        return lines;
    }

    /**
     * Build the full detail payload for a plugin row.
     */
    public static PluginDetail buildPluginDetail(PluginTab.PluginRow row) {
        return new PluginDetail(
                row,
                statusLines(row),
                componentSummary(row),
                row.description() != null ? row.description() : "No description provided.");
    }
}
