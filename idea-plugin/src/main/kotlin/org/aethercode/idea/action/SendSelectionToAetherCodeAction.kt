package org.aethercode.idea.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.aethercode.idea.AetherCodeToolWindowFactory

/**
 * "Send selection to AetherCode" — R3 wiring. Reads the current editor selection, opens
 * the AetherCode ToolWindow, and routes the selection into the chat input as a
 * {@code @<path>:L1-L2} reference plus the snippet.
 */
class SendSelectionToAetherCodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        val text = selectionModel.selectedText ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val path = virtualFile?.path ?: "untitled"
        val start = editor.document.getLineNumber(selectionModel.selectionStart) + 1
        val end = editor.document.getLineNumber(selectionModel.selectionEnd) + 1
        val reference = "@$path:$start-$end"

        val tw = ToolWindowManager.getInstance(project).getToolWindow("AetherCode") ?: return
        tw.show()

        // Hand the snippet to the chat panel via a project-level service.
        val service = project.getService(AetherCodeToolWindowFactory::class.java)
        service.injectContent(reference, text)
    }
}
