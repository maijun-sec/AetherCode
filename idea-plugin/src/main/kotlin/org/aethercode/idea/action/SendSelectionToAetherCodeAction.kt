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
