package org.aethercode.idea.context

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/**
 * R7: theme manager. Wraps the platform's {@link LafManager} so
 * plugin code can:
 *
 * <ol>
 *   <li>Subscribe to look-and-feel changes via the project's
 *       {@link com.intellij.util.messages.MessageBus} (topic
 *       {@link ContextBus#THEME_CHANGED}).</li>
 *   <li>Resolve theme-aware {@link Color}s and {@link Font}s
 *       without hand-rolling light/dark pairs. The intent is
 *       that the chat panel — and every later UI surface in R8+
 *       — calls into this manager instead of writing
 *       {@code JBColor(0x..., 0x...)} literals.</li>
 * </ol>
 *
 * <p>Why this exists: R1–R6 hard-coded JBColor literals inside
 * {@code AetherCodeChatPanel} (e.g. {@code USER_COLOR}, {@code
 * ASSISTANT_COLOR}). When the user switched LaF, the chat panel
 * did not re-evaluate; it just relied on JBColor's two-argument
 * factory. The factory works, but the <em>intent</em> ("this
 * colour is the user-message colour") was lost. R7 pulls the
 * palette into a single named slot table so future theme
 * support (custom IDE themes, AetherCode-branded palette) is a
 * one-place change.
 *
 * <p>Application-scope service: themes are global to the IDE, not
 * per-project. R8+ can extend with project-scoped accents.
 */
@Service(Service.Level.APP)
class ThemeManager : Disposable {

    private val log = logger<ThemeManager>()

    init {
        // Hook the LafManager once at construction. The connection is
        // bound to this service's lifetime; disposing the service
        // detaches the listener. In practice the service lives for the
        // duration of the IDE.
        val conn = ApplicationManager.getApplication().messageBus.connect(this)
        conn.subscribe(LafManagerListener.TOPIC, LafManagerListener { lafChanged() })
    }

    /** Broadcast a theme-change event on the app-level message bus. */
    private fun lafChanged() {
        log.info("LaF changed → broadcasting THEME_CHANGED")
        ApplicationManager.getApplication().messageBus
            .syncPublisher(ContextBus.THEME_CHANGED)
            .themeChanged(currentLafName())
    }

    /** Name of the currently installed look-and-feel. */
    fun currentLafName(): String =
        LafManager.getInstance().currentLookAndFeel?.name ?: "unknown"

    /** True when the current LaF is a dark variant. */
    fun isDark(): Boolean = JBColor.isDark()

    // --- named palette slots ---------------------------------------------

    private val userColor: NamedColor = NamedColor("user", Color(0x1f, 0x6f, 0xb2), Color(0x66, 0x99, 0xcc))
    private val assistantColor: NamedColor = NamedColor("assistant", Color(0x1f, 0x99, 0x66), Color(0x66, 0xcc, 0x99))
    private val toolColor: NamedColor = NamedColor("tool", Color(0xaa, 0x88, 0x00), Color(0xcc, 0xaa, 0x00))
    private val okColor: NamedColor = NamedColor("ok", Color(0x33, 0x99, 0x33), Color(0x66, 0xcc, 0x66))
    private val errorColor: NamedColor = NamedColor("error", Color(0xcc, 0x33, 0x33), Color(0xff, 0x66, 0x66))
    private val hintColor: NamedColor = NamedColor("hint", Color(0x88, 0x88, 0x88), Color(0xaa, 0xaa, 0xaa))
    private val headerColor: NamedColor = NamedColor("header", Color(0x44, 0x44, 0x44), Color(0xcc, 0xcc, 0xcc))

    fun user() = userColor.get()
    fun assistant() = assistantColor.get()
    fun tool() = toolColor.get()
    fun ok() = okColor.get()
    fun error() = errorColor.get()
    fun hint() = hintColor.get()
    fun header() = headerColor.get()

    /** The chat panel's default font. Falls back to JetBrains Mono at 13. */
    fun chatFont(): Font = Font("JetBrains Mono", Font.PLAIN, 13)

    /** A monospace input font, larger. */
    fun inputFont(): Font = Font("JetBrains Mono", Font.PLAIN, 13)

    override fun dispose() {
        // messageBus connection is auto-disposed by parent disposable.
    }

    /** Light/dark pair. Read via {@link #get()} to honour live LaF switches. */
    class NamedColor internal constructor(
        val name: String,
        private val light: Color,
        private val dark: Color
    ) {
        fun get(): JBColor = JBColor(light, dark)
        fun pair(): Pair<Color, Color> = light to dark
    }

    companion object {
        fun getInstance(): ThemeManager = ApplicationManager.getApplication().getService(ThemeManager::class.java)
    }
}
