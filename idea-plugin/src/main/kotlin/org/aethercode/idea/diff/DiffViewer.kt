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

package org.aethercode.idea.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.aethercode.core.tool.Tool

/**
 * Surfaces file edits produced by the agent as IntelliJ diff views. Modelled on the TS
 * {@code components/FileEditToolDiff.tsx}, but routed through the platform's diff framework
 * so the user gets the same split-pane, line-level review they use for human-driven edits.
 */
object DiffViewer {

    /** Show a diff for one {@link Tool.Attachment.DiffPreview} attachment. */
    fun show(project: Project, preview: Tool.Attachment.DiffPreview) {
        val factory = DiffContentFactory.getInstance()
        val before = factory.create(preview.before)
        val after = factory.create(preview.after)
        val title = "AetherCode edit — ${preview.path}"
        val req = SimpleDiffRequest(title, before, after, "before", "after")
        DiffManager.getInstance().showDiff(project, req)
    }

    /** Open the resulting file in the editor so the user can continue from the agent's edit. */
    fun openFile(project: Project, path: String) {
        val vf: VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)
        if (vf != null && !vf.isDirectory) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }
}
