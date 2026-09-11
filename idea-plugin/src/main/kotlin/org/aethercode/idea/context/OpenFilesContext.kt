package org.aethercode.idea.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import java.nio.file.Path
import java.nio.file.Paths

/**
 * R7: tracks the IDE's open files and the active editor. The
 * chat panel reads {@link #current()} when the user presses
 * Enter to auto-inject {@code @<activeFile>:<cursorLine>} plus
 * the enclosing method/class signature as a context preamble.
 *
 * <p>Three things the chat panel actually consumes:
 * <ol>
 *   <li>The active file's path — used as the {@code @} reference.</li>
 *   <li>The cursor line / column — used to scope the reference.</li>
 *   <li>The enclosing method/function/class — used as a more
 *       informative "what are we looking at" hint for the model.</li>
 * </ol>
 *
 * <p>The PSI traversal for "enclosing method" is intentionally
 * shallow: we walk up from the leaf until we hit a {@link
 * PsiNamedElement} whose name is non-null. For a Java method,
 * that's the method; for a Kotlin function, the function; for a
 * Lua top-level statement, the file. We do not try to recover
 * the call site or import list — that's the engine's job once
 * it sees the prompt.
 */
@Service(Service.Level.PROJECT)
class OpenFilesContext(private val project: Project) {

    private val log = logger<OpenFilesContext>()

    @Volatile private var snapshot: Snapshot = Snapshot.empty()

    init {
        val conn = project.messageBus.connect()
        conn.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) { refresh() }
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) { refresh() }
            override fun selectionChanged(event: FileEditorManagerEvent) { refresh() }
            override fun activeFileChanged(event: FileEditorManagerEvent?) { refresh() }
        })
        // Compute once at startup so the first prompt already has a snapshot.
        refresh()
    }

    fun current(): Snapshot = snapshot

    fun refresh() {
        val fem = FileEditorManager.getInstance(project)
        val activeVf: VirtualFile? = fem.selectedFiles.firstOrNull()
        val editor: Editor? = fem.selectedTextEditor
        val filePath: Path? = activeVf?.let { Paths.get(it.path) }
        val (line, col, selection) = editor?.let { cursorAndSelection(it) } ?: Triple(0, 0, null)
        val enclosing = editor?.let { enclosingNamed(it) }
        val fresh = Snapshot(
            activeFile = filePath,
            language = activeVf?.let { languageId(it) } ?: "unknown",
            cursorLine = line,
            cursorColumn = col,
            selectionText = selection,
            enclosingName = enclosing,
            openFiles = fem.openFiles.map { Paths.get(it.path) }
        )
        val prev = snapshot
        snapshot = fresh
        if (fresh != prev) {
            project.messageBus.syncPublisher(ContextBus.OPEN_FILES_CHANGED).openFilesChanged(fresh)
        }
    }

    private fun cursorAndSelection(e: Editor): Triple<Int, Int, String?> {
        val sm: SelectionModel = e.selectionModel
        val line = e.document.getLineNumber(e.caretModel.offset) + 1
        val col = e.caretModel.offset - e.document.getLineStartOffset(line - 1) + 1
        return Triple(line, col, sm.selectedText)
    }

    private fun enclosingNamed(e: Editor): String? {
        return try {
            val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(e.document) ?: return null
            var leaf: PsiElement? = psiFile.findElementAt(e.caretModel.offset)
            while (leaf != null) {
                if (leaf is PsiNamedElement && leaf.name != null) {
                    val kind = leaf.javaClass.simpleName
                    return "${leaf.name} <$kind>"
                }
                leaf = leaf.parent
            }
            null
        } catch (ex: Exception) {
            log.debug("enclosingNamed failed: ${ex.message}")
            null
        }
    }

    private fun languageId(vf: VirtualFile): String =
        com.intellij.lang.LanguageUtil.getLanguageForVirtualFile(project, vf)
            ?.id ?: vf.extension ?: "text"

    data class Snapshot(
        val activeFile: Path?,
        val language: String,
        val cursorLine: Int,
        val cursorColumn: Int,
        val selectionText: String?,
        val enclosingName: String?,
        val openFiles: List<Path>
    ) {
        companion object { fun empty() = Snapshot(null, "unknown", 0, 0, null, null, emptyList()) }
    }

    companion object {
        fun getInstance(project: Project): OpenFilesContext = project.getService(OpenFilesContext::class.java)
    }
}
