package org.aethercode.code.plugins.adapters;

import org.aethercode.code.hooks.models.HookDiagnostic;
import org.aethercode.code.hooks.models.HookEvent;
import org.aethercode.code.plugins.PluginDiscovery;
import org.aethercode.code.plugins.PluginModels;
import org.aethercode.code.plugins.PluginSubstitution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter from plugin hook declarations to Hooks v2 configuration
 * sources.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.plugins.adapters.hooks} module.</p>
 */
public final class PluginHooksAdapter {
    private static final Logger LOGGER = Logger.getLogger(PluginHooksAdapter.class.getName());
    private static final Set<String> KNOWN_EVENTS = Set.of(
            "SessionStart", "UserPromptSubmit", "SessionEnd", "PermissionRequest",
            "Notification", "PreToolUse", "PostToolUse", "PostToolUseFailure",
            "PreCompact", "Stop", "SubagentStart", "SubagentStop");

    private PluginHooksAdapter() {}

    /**
     * One sourced hook document plus its location and plugin id.
     */
    public record PluginHookDocument(Path location, String pluginId,
                                      Map<String, String> env,
                                      Object document) {}

    /**
     * Result of {@link #discoverHookSources()}.
     */
    public record PluginHookSources(List<PluginHookDocument> documents,
                                     List<HookDiagnostic> diagnostics) {}

    /** Build hook sources from discovered plugins. */
    public static PluginHookSources discoverHookSources(Path projectDir) {
        return discoverHookSources(projectDir, null);
    }

    /** Build hook sources from already-discovered plugins, or run discovery here. */
    public static PluginHookSources discoverHookSources(Path projectDir,
                                                         List<PluginModels.PluginInstance> plugins) {
        List<HookDiagnostic> diagnostics = new ArrayList<>();
        List<PluginHookDocument> documents = new ArrayList<>();
        if (plugins == null) {
            try {
                var result = org.aethercode.code.plugins.PluginLifecycle.discoverPlugins();
                plugins = new java.util.ArrayList<>(result.plugins());
            } catch (RuntimeException exc) {
                diagnostics.add(diagnostic("Could not discover plugin hooks: " + exc.getMessage()));
                return new PluginHookSources(documents, diagnostics);
            }
        }
        for (PluginModels.PluginInstance plugin : plugins) {
            try {
                List<PluginHookDocument> pluginDocs = collectPluginDocuments(plugin, projectDir, diagnostics);
                documents.addAll(pluginDocs);
            } catch (RuntimeException exc) {
                LOGGER.log(Level.WARNING, "Could not load hooks for plugin " + plugin.pluginId(), exc);
                diagnostics.add(diagnostic("Could not load hooks for plugin " + plugin.pluginId()
                        + ": " + exc.getMessage()));
            }
        }
        return new PluginHookSources(documents, diagnostics);
    }

    /** List the hook events a plugin declares, for display before it loads. */
    public static List<String> pluginHookEventNames(PluginModels.PluginInstance plugin) {
        List<String> events = new ArrayList<>();
        for (var doc : collectAllDocuments(plugin, new ArrayList<>())) {
            if (!(doc.document() instanceof Map<?, ?> document)) continue;
            Object hooks = document.get("hooks");
            if (!(hooks instanceof Map<?, ?> hooksMap)) continue;
            for (Object key : hooksMap.keySet()) {
                if (key instanceof String s && KNOWN_EVENTS.contains(s)) {
                    if (!events.contains(s)) events.add(s);
                }
            }
        }
        return events;
    }

    private static List<PluginHookDocument> collectPluginDocuments(
            PluginModels.PluginInstance plugin, Path projectDir, List<HookDiagnostic> diagnostics) {
        List<PluginHookDocument> documents = new ArrayList<>();
        for (Path path : plugin.inventory().hookFiles()) {
            try {
                String text = java.nio.file.Files.readString(path);
                Object decoded = org.aethercode.code.plugins.PluginMiniJson.parse(text);
                documents.add(new PluginHookDocument(path, plugin.pluginId(),
                        PluginSubstitution.pluginEnvironment(plugin.root(), plugin.dataDir(), projectDir),
                        decoded));
            } catch (java.io.IOException | RuntimeException exc) {
                diagnostics.add(diagnostic("Could not read " + path + ": " + exc.getMessage()));
            }
        }
        if (plugin.manifest() != null && plugin.manifest().inlineHooks() != null
                && !plugin.manifest().inlineHooks().isEmpty()) {
            Path manifestPath = PluginDiscovery.findManifestPath(plugin.root());
            documents.add(new PluginHookDocument(
                    manifestPath != null ? manifestPath : plugin.root(),
                    plugin.pluginId(),
                    PluginSubstitution.pluginEnvironment(plugin.root(), plugin.dataDir(), projectDir),
                    plugin.manifest().inlineHooks()));
        }
        return documents;
    }

    private static List<PluginHookDocument> collectAllDocuments(PluginModels.PluginInstance plugin,
                                                                  List<HookDiagnostic> sink) {
        return collectPluginDocuments(plugin, null, sink);
    }

    private static HookDiagnostic diagnostic(String message) {
        LOGGER.log(Level.WARNING, message);
        return new HookDiagnostic("plugin_hooks_failed", "warning", message, null, null);
    }
}
