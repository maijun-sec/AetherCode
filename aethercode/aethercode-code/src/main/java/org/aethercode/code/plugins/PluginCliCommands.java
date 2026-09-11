package org.aethercode.code.plugins;

import java.util.List;
import java.util.Map;

/**
 * Plugin CLI commands (stub).
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.plugins.cli} module. The Java port exposes
 * the surface used by the {@code dcode plugin} subcommand; the full
 * implementation lands with the deepagents-code.plugins subdirectory
 * port.</p>
 */
public final class PluginCliCommands {
    private PluginCliCommands() {}

    /** List installed plugins. */
    public static List<PluginModels.PluginInstance> list() {
        return List.of();
    }

    /** List plugin commands. */
    public static List<PluginModels.PluginCommand> commands(String pluginId) {
        return List.of();
    }

    /** Run a plugin command. */
    public static Map<String, Object> run(String pluginId, String commandName,
                                          Map<String, Object> args) {
        return Map.of("ok", false, "reason", "not implemented");
    }
}
