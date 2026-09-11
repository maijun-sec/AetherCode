package org.aethercode.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Wires the AetherCode chat panel into a ToolWindow. R3 also exposes a static service
 * so actions (e.g. {@link org.aethercode.idea.action.SendSelectionToAetherCodeAction})
 * can inject content into the panel.
 */
class AetherCodeToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AetherCodeChatPanel(project)
        // Stash the panel so other actions can find it.
        PROJECT_PANELS[project] = panel
        val content = ContentFactory.getInstance().createContent(panel, "Chat", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        private val PROJECT_PANELS: MutableMap<Project, AetherCodeChatPanel> = java.util.concurrent.ConcurrentHashMap()

        @JvmStatic
        fun panelFor(project: Project): AetherCodeChatPanel? = PROJECT_PANELS[project]

        @JvmStatic
        fun injectContent(project: Project, reference: String, text: String) {
            val panel = PROJECT_PANELS[project] ?: return
            panel.injectContent(reference, text)
        }
    }
}
