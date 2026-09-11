package org.aethercode.idea.lsp

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.aethercode.tools.lsp.LspTool
import org.aethercode.tools.lsp.StdioLspSession
import org.aethercode.tools.lsp.StdioLspTransport
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.services.LanguageClient

/**
 * R4: LSP auto-connect. Listens for editor openings and starts a configured language
 * server (e.g. jdtls for .java files, gopls for .go, pylsp for .py). The session is
 * kept in a project-scoped map; diagnostics are forwarded to {@link LspTool}'s cache.
 */
class LspAutoConnectListener(private val project: Project) : FileEditorManagerListener {

    private val log = logger<LspAutoConnectListener>()
    private val sessions: MutableMap<String, StdioLspSession> = java.util.concurrent.ConcurrentHashMap()

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val ext = file.extension ?: return
        val config = pickConfig(ext) ?: return
        if (sessions.containsKey(config.id)) return
        try {
            val transport = StdioLspTransport(config.command, config.args, config.env)
            val client = createClient(config.id, file.path)
            val session = transport.start(client)
            session.initialize(source.project.basePath ?: "")
            session.didOpen(
                file.toNioPath(),
                config.languageId,
                String(file.contentsToByteArray(), Charsets.UTF_8)
            )
            sessions[config.id] = session
            log.info("started LSP server ${config.id} for $ext (${config.command})")
        } catch (e: Throwable) {
            log.warn("failed to start LSP for $ext: ${e.message}")
        }
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        val ext = file.extension ?: return
        val config = pickConfig(ext) ?: return
        sessions[config.id]?.didClose(file.toNioPath())
    }

    private fun pickConfig(ext: String): LspConfig? {
        // R4: read AETHERCODE_LSP_SERVERS env var; format: "id:cmd:ext:langId,..."
        val raw = System.getenv("AETHERCODE_LSP_SERVERS") ?: return null
        for (entry in raw.split(",")) {
            val parts = entry.split(":")
            if (parts.size < 4) continue
            val id = parts[0]
            val cmd = parts[1]
            val matchExt = parts[2]
            val langId = parts[3]
            val args = if (parts.size > 4) parts.drop(4) else emptyList()
            if (matchExt == ext) {
                return LspConfig(id, cmd, args, emptyMap(), langId)
            }
        }
        return null
    }

    private fun createClient(serverId: String, filePath: String): LanguageClient = object : LspTool.InMemoryLanguageClient() {
        override fun publishDiagnostics(params: org.eclipse.lsp4j.PublishDiagnosticsParams?) {
            if (params == null) return
            LspTool.recordDiagnostics(params.uri, params)
        }
    }

    private data class LspConfig(
        val id: String,
        val command: String,
        val args: List<String>,
        val env: Map<String, String>,
        val languageId: String
    )
}
