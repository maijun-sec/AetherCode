// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

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
