// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
