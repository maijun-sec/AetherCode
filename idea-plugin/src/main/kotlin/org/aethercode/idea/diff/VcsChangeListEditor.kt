package org.aethercode.idea.diff

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.aethercode.core.tool.Tool

/**
 * R5: real VCS LocalChangeList integration. When the agent edits a file, we:
 *
 * <ol>
 *   <li>Read the file's current bytes (the "after" — the agent has already saved)</li>
 *   <li>Read the file's VCS-base revision (the "before")</li>
 *   <li>Stage the change into a new {@link LocalChangeList} named "AetherCode"</li>
 *   <li>Open the standard VCS diff viewer for the change</li>
 * </ol>
 *
 * <p>The R4 {@code VcsChangeListBridge} only touched the change list without showing
 * a diff. R5 goes the full distance so the user gets the same review experience
 * they have for human edits.
 */
object VcsChangeListEditor {

    /**
     * Stage a {@link Tool.Attachment.DiffPreview} into a new local change list and
     * open the diff viewer. Returns the {@code change list} on success, or null if
     * the file is not under VCS.
     */
    fun stageAndShow(project: Project, preview: Tool.Attachment.DiffPreview): LocalChangeList? {
        val vf: VirtualFile = LocalFileSystem.getInstance().findFileByPath(preview.path) ?: return null
        val clm = ChangeListManager.getInstance(project)
        if (!clm.isUnderVcs(vf)) return null

        // Ensure any pending document changes are committed.
        FileDocumentManager.getInstance().saveAllDocuments()

        val list = clm.addChangeList("AetherCode", "Agent edits", null)
        // R6: make the AetherCode change list the project's default so the
        // user lands here when they hit ⌘K / Ctrl+K to commit.
        try {
            WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
                clm.setDefaultChangeList(list)
            }
        } catch (e: Throwable) {
            // best-effort — the list is created even if the default switch fails
        }

        // Find the change for the file and move it to the new list.
        val change = clm.getChange(vf) ?: return list
        clm.moveChangesTo(list, listOf(change))

        // Open the standard diff viewer.
        try {
            com.intellij.diff.DiffManager.getInstance().showDiff(
                project,
                com.intellij.diff.requests.SimpleDiffRequest(
                    "AetherCode edit — ${preview.path}",
                    com.intellij.diff.DiffContentFactory.getInstance().create(preview.before),
                    com.intellij.diff.DiffContentFactory.getInstance().create(preview.after),
                    "before",
                    "after"
                )
            )
        } catch (e: Throwable) {
            // best-effort — the change is still staged even if the diff viewer can't open
        }
        return list
    }
}
