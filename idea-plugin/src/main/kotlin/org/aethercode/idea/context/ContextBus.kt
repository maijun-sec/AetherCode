package org.aethercode.idea.context

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import org.aethercode.idea.context.OpenFilesContext.Snapshot as FilesSnapshot
import org.aethercode.idea.context.ProjectContext.Snapshot as ProjectSnapshot

/**
 * R7: centralised IntelliJ MessageBus topics for the four
 * integration services. Each service publishes on one topic;
 * the chat panel and downstream R8+ surfaces subscribe to the
 * topics they care about.
 *
 * <p>Why a single file: every new service used to declare its
 * own topic ad-hoc, which made it impossible to audit "what
 * events exist" without grepping the codebase. R7 puts them
 * all in one place so {@code ContextBus.TOPICS} lists every
 * integration event the plugin fires.
 */
object ContextBus {

    /** Fired when the project snapshot changes (root move, module add, VCS rebind). */
    @Topic.ProjectLevel
    val PROJECT_CHANGED: Topic<ProjectListener> = Topic.create(
        "AetherCode.ProjectChanged",
        ProjectListener::class.java
    )

    /** Fired when the active editor, selection, or open-files set changes. */
    @Topic.ProjectLevel
    val OPEN_FILES_CHANGED: Topic<OpenFilesListener> = Topic.create(
        "AetherCode.OpenFilesChanged",
        OpenFilesListener::class.java
    )

    /** Fired when the IDE's look-and-feel switches. */
    @Topic.AppLevel
    val THEME_CHANGED: Topic<ThemeListener> = Topic.create(
        "AetherCode.ThemeChanged",
        ThemeListener::class.java
    )

    /** Fired when the installed-plugins snapshot changes (install, enable, uninstall). */
    @Topic.AppLevel
    val PLUGIN_INTEGRATIONS_CHANGED: Topic<PluginIntegrationsListener> = Topic.create(
        "AetherCode.PluginIntegrationsChanged",
        PluginIntegrationsListener::class.java
    )

    /** Convenience list for "subscribe to all of them" use cases. */
    val TOPICS: List<Topic<*>> = listOf(
        PROJECT_CHANGED,
        OPEN_FILES_CHANGED,
        THEME_CHANGED,
        PLUGIN_INTEGRATIONS_CHANGED
    )

    // --- listener interfaces ---------------------------------------------

    interface ProjectListener {
        fun projectChanged(snapshot: ProjectSnapshot)
    }

    interface OpenFilesListener {
        fun openFilesChanged(snapshot: FilesSnapshot)
    }

    interface ThemeListener {
        fun themeChanged(lafName: String)
    }

    interface PluginIntegrationsListener {
        fun pluginIntegrationsChanged(capabilities: Set<PluginIntegrations.Capability>)
    }

    /**
     * Subscribe to a topic on the project's bus, or the app bus for
     * app-level topics. The disposable scopes the subscription to
     * the project's lifetime.
     */
    fun <L : Any> subscribe(project: Project, topic: Topic<L>, listener: L) {
        project.messageBus.connect().subscribe(topic, listener)
    }
}
