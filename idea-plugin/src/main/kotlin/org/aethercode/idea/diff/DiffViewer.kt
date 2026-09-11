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
