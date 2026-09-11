package org.aethercode.idea

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import org.aethercode.core.stream.StreamEvent
import org.aethercode.idea.backend.BackendManager
import org.aethercode.idea.context.ContextBus
import org.aethercode.idea.context.OpenFilesContext
import org.aethercode.idea.context.ThemeManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The chat panel that lives inside the AetherCode ToolWindow. Three sub-areas:
 *
 * <ol>
 *   <li>Top — the message transcript (read-only, scrollable)</li>
 *   <li>Middle — the input box (multi-line, Enter sends, Shift+Enter inserts newline)</li>
 *   <li>Bottom — the Send / Stop button row</li>
 * </ol>
 *
 * <p>R7 changes:
 * <ul>
 *   <li>Backend comes from {@link BackendManager} (daemon-first, in-process fallback).</li>
 *   <li>Colors come from {@link ThemeManager}; LaF switches re-paint automatically.</li>
 *   <li>Sending a prompt auto-injects {@code @<activeFile>:<cursorLine>} +
 *       enclosing-method hint via {@link OpenFilesContext}. The user can
 *       still type a fully manual prompt — auto-inject only fires when
 *       there's an active file and the user typed a non-command message.</li>
 * </ul>
 */
class AetherCodeChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val theme = ThemeManager.getInstance()
    private val files = OpenFilesContext.getInstance(project)

    private val transcript = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = theme.chatFont()
        background = com.intellij.ui.JBColor.background()
        border = JBUI.Borders.empty(8)
    }
    private val input = JBTextArea(4, 80).apply {
        lineWrap = true
        wrapStyleWord = true
        font = theme.inputFont()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(com.intellij.ui.JBColor.border()),
            JBUI.Borders.empty(6)
        )
    }
    private val sendButton = JButton("Send").apply { isEnabled = true }
    private val stopButton = JButton("Stop").apply { isEnabled = false }

    @Volatile private var running = false
    @Volatile private var abort: java.util.concurrent.CompletableFuture<Void>? = null
    private val themeConn: MessageBusConnection =
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect()
    private val filesConn: MessageBusConnection = project.messageBus.connect()

    init {
        preferredSize = Dimension(420, 600)
        add(JBScrollPane(transcript), BorderLayout.CENTER)
        add(input, BorderLayout.NORTH)
        val buttons = JPanel().apply {
            add(sendButton)
            add(stopButton)
        }
        add(buttons, BorderLayout.SOUTH)

        sendButton.addActionListener { sendCurrentInput() }
        stopButton.addActionListener { BackendManager.current(project).abort() }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendCurrentInput()
                }
            }
        })

        // Repaint on LaF switch — fonts and the cached border need re-layout.
        themeConn.subscribe(ContextBus.THEME_CHANGED, object : ContextBus.ThemeListener {
            override fun themeChanged(lafName: String) {
                SwingUtilities.invokeLater {
                    transcript.font = theme.chatFont()
                    input.font = theme.inputFont()
                    repaint()
                }
            }
        })
        // Status line tracks the active editor.
        filesConn.subscribe(ContextBus.OPEN_FILES_CHANGED, object : ContextBus.OpenFilesListener {
            override fun openFilesChanged(snapshot: OpenFilesContext.Snapshot) {
                SwingUtilities.invokeLater { appendLine("• ${describe(snapshot)}", theme.hint()) }
            }
        })

        appendLine("AetherCode ready. cwd: ${project.basePath}", theme.header())
        appendLine("Type your prompt, press Enter to send, Shift+Enter for newline.", theme.hint())
        appendLine("• ${describe(files.current())}", theme.hint())
    }

    private fun describe(s: OpenFilesContext.Snapshot): String {
        if (s.activeFile == null) return "no active file"
        val pos = "${s.activeFile.fileName}:${s.cursorLine}:${s.cursorColumn}"
        val enc = s.enclosingName?.let { " ($it)" } ?: ""
        return "$pos$enc"
    }

    private fun sendCurrentInput() {
        if (running) return
        val raw = input.text.trim()
        if (raw.isEmpty()) return
        input.text = ""
        val (prompt, preamble) = buildPrompt(raw)
        if (preamble.isNotEmpty()) appendLine("· ctx: $preamble", theme.hint())
        appendLine("\n❯ $raw", theme.user())
        running = true
        sendButton.isEnabled = false
        stopButton.isEnabled = true
        val backend = BackendManager.current(project)
        val done = java.util.concurrent.CompletableFuture.supplyAsync {
            try {
                backend.query(prompt).forEach { ev -> SwingUtilities.invokeLater { render(ev) } }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater { appendLine("error: ${ex.message}", theme.error()) }
            }
            null
        }
        abort = java.util.concurrent.CompletableFuture()
        done.whenComplete { _, _ ->
            running = false
            sendButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    /**
     * Build the final prompt and a one-line description of any
     * auto-injected context. Auto-injection only happens when:
     *
     * <ol>
     *   <li>There is an active file, and</li>
     *   <li>The user did not start the line with {@code /} (a
     *       slash command), and</li>
     *   <li>The user did not already include an {@code @} reference.</li>
     * </ol>
     */
    private fun buildPrompt(raw: String): Pair<String, String> {
        val s = files.current()
        if (s.activeFile == null) return raw to ""
        if (raw.startsWith("/")) return raw to ""
        if (raw.contains("@")) return raw to ""
        val ref = "@${s.activeFile}:${s.cursorLine}-${s.cursorLine}"
        val enc = s.enclosingName?.let { "  # context: $it" } ?: ""
        val withContext = "$ref\n$raw$enc"
        return withContext to (s.activeFile.fileName.toString() + enc)
    }

    private fun render(ev: StreamEvent) {
        when (ev) {
            is StreamEvent.TextDelta -> transcript.append(ev.text())
            is StreamEvent.ToolUseStart -> appendLine("\n  ⚙ ${ev.name()}(${ev.input()})", theme.tool())
            is StreamEvent.ToolOutputDelta -> transcript.append(ev.text())
            is StreamEvent.ToolResult -> {
                val sign = if (ev.isError()) "✗" else "✓"
                val color = if (ev.isError()) theme.error() else theme.ok()
                appendLine("  $sign ${ev.content()}", color)
            }
            is StreamEvent.RunEnd -> {
                appendLine("\n— end (${ev.stopReason()}) —", theme.hint())
                // R12 will wire DiffPreview attachment extraction through the
                // message sink; for R7 we just close the run and let the
                // user inspect the final blocks in the transcript.
            }
            is StreamEvent.RunStart -> appendLine("▸ run ${ev.runId().take(8)} on ${ev.model()}", theme.hint())
            is StreamEvent.SideNote -> appendLine("ℹ [${ev.kind()}] ${ev.message()}", theme.hint())
            is StreamEvent.Usage -> { /* hook for status bar (R8) */ }
            is StreamEvent.AwaitUserDecision -> appendLine("⚠ ${ev.summary()} (${ev.currentTodoSteps()} steps)", theme.hint())
            is StreamEvent.SubTaskStart -> { /* R8 will render a sub-task card */ }
            is StreamEvent.SubTaskEnd -> { /* R8 will close the matching card */ }
        }
        transcript.caretPosition = transcript.document.length
    }

    private fun appendLine(text: String, color: java.awt.Color) {
        transcript.append(text + "\n")
        transcript.setForeground(color)
    }
}
