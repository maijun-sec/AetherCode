package org.aethercode.idea.engine

import com.intellij.openapi.project.Project
import org.aethercode.core.permission.PermissionMode
import org.aethercode.llm.ChatClientFactory
import org.aethercode.permission.SettingsPermissions
import org.aethercode.sdk.AetherCodeEngine
import java.nio.file.Paths

/**
 * Project-scoped holder for the {@link AetherCodeEngine}. One engine per project, built
 * lazily on first access.
 *
 * <p>Provider selection: the holder consults {@link AetherCodeSettings} (a
 * {@code PersistentStateComponent}) for the user's chosen chat provider (anthropic |
 * minimax) and the API key. The env-var fallbacks are:
 * <ul>
 *   <li>anthropic: {@code ANTHROPIC_API_KEY}</li>
 *   <li>minimax:   {@code MINIMAX_API_KEY}</li>
 * </ul>
 *
 * <p>The engine is rebuilt if the provider or apiKey changes between calls — useful for
 * the Settings panel where the user can switch providers without reloading the IDE.
 */
object AetherCodeEngineHolder {

    @Volatile private var cached: AetherCodeEngine? = null
    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedProvider: String? = null

    /** Drop the cached engine — the next {@link #getEngine} call will rebuild it. */
    fun invalidate() {
        synchronized(this) {
            cached?.let { /* best-effort cleanup; the engine has no resources to release */ }
            cached = null
        }
    }

    fun getEngine(project: Project): AetherCodeEngine {
        val settings = AetherCodeSettings.getInstance(project)
        val provider = settings.provider
        val apiKey = settings.apiKey
        cached?.let { existing ->
            if (cachedProvider == provider && cachedKey == apiKey) return existing
        }
        synchronized(this) {
            cached?.let { existing ->
                if (cachedProvider == provider && cachedKey == apiKey) return existing
            }
            val cwd = project.basePath?.let { Paths.get(it) } ?: Paths.get("").toAbsolutePath()
            val perms = SettingsPermissions.loadFrom(cwd.resolve(".aethercode").resolve("settings.json"))
            val engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .provider(provider)
                .model(defaultModelFor(provider))
                .apiKey(apiKey)
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .permissions(perms)
                // No prompter wired in the IDE yet — read-only tools auto-allow, edits ask.
                .prompter(null)
                .build()
            cached = engine
            cachedProvider = provider
            cachedKey = apiKey
            return engine
        }
    }

    /** Provider-specific default model. Keep in sync with {@code ChatClientFactory} usage in the CLI. */
    private fun defaultModelFor(provider: String): String = when (provider.lowercase()) {
        ChatClientFactory.PROVIDER_MINIMAX -> "MiniMax-M3"
        else -> "claude-sonnet-4-5"
    }
}
