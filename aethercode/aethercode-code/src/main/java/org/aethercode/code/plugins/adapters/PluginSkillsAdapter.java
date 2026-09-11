package org.aethercode.code.plugins.adapters;

import org.aethercode.code.plugins.PluginModels;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter from discovered plugins to skill source tuples.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.plugins.adapters.skills} module.</p>
 */
public final class PluginSkillsAdapter {
    private static final Logger LOGGER = Logger.getLogger(PluginSkillsAdapter.class.getName());

    /** Source tuple carrying a path, label, and plugin namespace. */
    public record PluginSkillSource(String path, String label, String namespace) {}

    /** Result of plugin skill discovery. */
    public record PluginSkillState(
            List<PathSource> sources,
            List<Path> roots,
            java.util.Set<String> pluginIds) {
        public record PathSource(Path path, String namespace) {}
    }

    private PluginSkillsAdapter() {}

    /** Qualify a skill name under its plugin namespace. */
    public static String namespacedSkillName(String namespace, String name, List<String> subfolders) {
        List<String> parts = new ArrayList<>();
        parts.add(namespace);
        if (subfolders != null) parts.addAll(subfolders);
        parts.add(name);
        return String.join(":", parts).toLowerCase(java.util.Locale.ROOT);
    }

    /** Return skill source tuples for plugin skills. */
    public static List<PluginSkillSource> pluginSkillSources(
            List<PluginModels.PluginInstance> plugins) {
        List<PluginSkillSource> out = new ArrayList<>();
        for (PluginModels.PluginInstance plugin : plugins) {
            for (Path path : plugin.inventory().skills()) {
                Path sourcePath = path.getFileName().toString().equals("SKILL.md")
                        ? path.getParent() : path;
                try {
                    if (!Files.exists(sourcePath)) continue;
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not inspect plugin skill path " + sourcePath);
                    continue;
                }
                out.add(new PluginSkillSource(
                        sourcePath.toString(),
                        "Plugin: " + plugin.pluginId(),
                        plugin.pluginId()));
            }
        }
        return out;
    }

    /** Return plugin skill roots for skill-content containment checks. */
    public static List<Path> pluginSkillRoots(List<PluginModels.PluginInstance> plugins) {
        List<Path> out = new ArrayList<>();
        for (PluginModels.PluginInstance plugin : plugins) {
            for (Path path : plugin.inventory().skills()) {
                out.add(path.getFileName().toString().equals("SKILL.md")
                        ? path.getParent() : path);
            }
        }
        return out;
    }

    /** Discover plugin skill sources, containment roots, and loaded ids. */
    public static PluginSkillState discoverPluginSkillState() {
        try {
            List<PluginModels.PluginInstance> plugins =
                    org.aethercode.code.plugins.PluginLifecycle.discoverPlugins().plugins();
            List<PluginSkillState.PathSource> sources = new ArrayList<>();
            for (PluginSkillSource s : pluginSkillSources(plugins)) {
                sources.add(new PluginSkillState.PathSource(Path.of(s.path()), s.namespace()));
            }
            List<Path> roots = pluginSkillRoots(plugins);
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (PluginModels.PluginInstance plugin : plugins) ids.add(plugin.pluginId());
            return new PluginSkillState(sources, roots, ids);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not discover plugin skills", e);
            return new PluginSkillState(List.of(), List.of(), java.util.Set.of());
        }
    }
}
