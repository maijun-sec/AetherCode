package org.aethercode.idea

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * "Open AetherCode" menu / shortcut action. Surfaces the ToolWindow so the user can
 * configure a keyboard shortcut to pop the panel up (default Alt+A).
 */
class OpenPanelAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("AetherCode") ?: return
        tw.show()
    }
}
