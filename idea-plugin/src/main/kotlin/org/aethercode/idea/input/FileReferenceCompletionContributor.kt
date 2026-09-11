package org.aethercode.idea.input

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import org.aethercode.tools.file.FileReadTool

/**
 * @file reference completion. When the user types {@code @} in the chat input, we offer
 * file paths from the project as completion candidates. This is the IDE-level equivalent
 * of the TS file-reference chip system.
 *
 * <p>Implementation: a {@link CompletionContributor} that watches all string-typed contexts
 * (the JTextArea inside the chat panel routes through a custom document, but the
 * contributor still kicks in for editor-like usages and for the future "editor" surface).
 */
class FileReferenceCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(),
                object : CompletionProvider<CompletionParameters>() {
                    override fun addCompletions(
                        params: CompletionParameters,
                        context: ProcessingContext,
                        result: CompletionResultSet
                    ) {
                        val element = params.position
                        val text = params.editor.document.text
                        val offset = params.editor.caretModel.offset
                        if (offset <= 0) return
                        // Look for a "@" within the last 32 chars
                        val window = text.substring(maxOf(0, offset - 32), offset)
                        val atIdx = window.lastIndexOf('@')
                        if (atIdx < 0) return
                        val prefix = window.substring(atIdx + 1)
                        val project = params.originalFile.manager.project ?: return
                        addFileCandidates(project, prefix, result)
                    }
                })
    }

    private fun addFileCandidates(project: Project, prefix: String, result: CompletionResultSet) {
        val basePath = project.basePath ?: return
        val base = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(basePath) ?: return
        val wanted = prefix.lowercase()
        base.children
            .asSequence()
            .filter { !it.isDirectory || it.name !in setOf(".git", "node_modules", "build", "target", ".idea") }
            .filter { it.name.lowercase().startsWith(wanted) || wanted.isEmpty() }
            .take(40)
            .forEach { addRecursive(it, prefix, "", result, depth = 0) }
    }

    private fun addRecursive(
        f: VirtualFile,
        prefix: String,
        relPath: String,
        result: CompletionResultSet,
        depth: Int
    ) {
        if (depth > 4) return
        val name = if (relPath.isEmpty()) f.name else "$relPath/${f.name}"
        if (f.isDirectory) {
            f.children.forEach { addRecursive(it, prefix, name, result, depth + 1) }
        } else {
            if (name.lowercase().contains(prefix.lowercase())) {
                result.addElement(
                    LookupElementBuilder.create("@$name")
                        .withIcon(AllIcons.FileTypes.Text)
                        .withTypeText(f.path)
                        .withInsertString("@" + name)
                )
            }
        }
    }
}
