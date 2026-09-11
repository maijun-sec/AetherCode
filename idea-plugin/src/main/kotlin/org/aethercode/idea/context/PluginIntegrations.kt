package org.aethercode.idea.context

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

/**
 * R7: discovers which JetBrains plugins are installed and
 * advertises their capabilities to the rest of AetherCode. R7
 * only <em>detects</em>; the actual integration (e.g. "if Git
 * is installed, route file edits through ChangeListManager")
 * ships in R12.
 *
 * <p>The capability list is intentionally narrow. Each entry is
 * a stable id the plugin can branch on without hard-coding
 * plugin class names:
 *
 * <ul>
 *   <li>{@code git} — built-in Git integration.</li>
 *   <li>{@code platform.lsp} — IntelliJ's LSP framework
 *       (intellij.platform.lsp). When present, the plugin
 *       delegates to {@code LspServerManager} instead of
 *       spawning stdio servers itself (R12).</li>
 *   <li>{@code terminal} — built-in terminal tool window.</li>
 *   <li>{@code markdown} — built-in Markdown plugin.</li>
 *   <li>{@code database} — JetBrains Database Tools (paid
 *       bundles; DataGrip plugin id).</li>
 *   <li>{@code docker} — JetBrains Docker plugin.</li>
 *   <li>{@code http-client} — built-in .http client.</li>
 *   <li>{@code ai.assistant} — JetBrains AI Assistant (we
 *       coexist, not compete).</li>
 * </ul>
 *
 * <p>The full list of "plugin id → capability" is a sealed
 * function so adding a new entry is a one-line change. Future
 * rounds can extend to include the plugin's version, the
 * settings keys it exposes, and the action ids the integration
 * needs to call.
 */
@Service(Service.Level.APP)
class PluginIntegrations {

    private val log = logger<PluginIntegrations>()

    private val idToCapability: Map<String, Capability> = listOf(
        "Git4Idea" to Capability.GIT,
        "org.jetbrains.plugins.terminal" to Capability.TERMINAL,
        "org.intellij.plugins.markdown" to Capability.MARKDOWN,
        "com.intellij.database" to Capability.DATABASE,
        "com.intellij.docker" to Capability.DOCKER,
        "com.intellij.httpClient" to Capability.HTTP_CLIENT,
        "com.intellij.ai" to Capability.AI_ASSISTANT,
        "com.intellij.lsp" to Capability.LSP_FRAMEWORK
    ).toMap()

    /** Cached snapshot, refreshed on first access and via {@link #refresh()}. */
    @Volatile private var snapshot: Map<Capability, IdeaPluginDescriptor> = compute()

    fun capabilities(): Map<Capability, IdeaPluginDescriptor> = snapshot

    fun has(c: Capability): Boolean = snapshot.containsKey(c)

    fun descriptorFor(c: Capability): IdeaPluginDescriptor? = snapshot[c]

    /** Re-scan installed plugins. Cheap; safe to call on startup. */
    fun refresh() {
        snapshot = compute()
        ApplicationBusHelper.broadcast(snapshot)
    }

    private fun compute(): Map<Capability, IdeaPluginDescriptor> {
        val out = mutableMapOf<Capability, IdeaPluginDescriptor>()
        for ((pluginId, cap) in idToCapability) {
            val id = runCatching { PluginId.findId(pluginId) }.getOrNull() ?: continue
            val desc = PluginManagerCore.getPlugin(id) ?: continue
            if (PluginManagerCore.isPluginEnabled(desc.pluginId)) {
                out[cap] = desc
            }
        }
        log.info("plugin integrations: ${out.keys}")
        return out
    }

    /**
     * Stable set of capabilities the plugin knows about. The
     * matching id table lives in {@link #idToCapability}; this
     * enum is the public surface other code can branch on.
     */
    enum class Capability {
        GIT,
        TERMINAL,
        MARKDOWN,
        DATABASE,
        DOCKER,
        HTTP_CLIENT,
        AI_ASSISTANT,
        LSP_FRAMEWORK
    }
}

/** Tiny helper: project-scope broadcasts go through the app bus. */
private object ApplicationBusHelper {
    fun broadcast(snapshot: Map<PluginIntegrations.Capability, IdeaPluginDescriptor>) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        // The bus call is fire-and-forget; no listener is required.
        runCatching {
            app.messageBus.syncPublisher(ContextBus.PLUGIN_INTEGRATIONS_CHANGED)
                .pluginIntegrationsChanged(snapshot.keys)
        }
    }
}
