package org.aethercode.idea.engine

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import org.aethercode.llm.ChatClientFactory

/**
 * R14-5: persistent IDE settings for the AetherCode plugin. Stores the chat provider
 * the user has chosen and the API key to use.
 *
 * <p>State is persisted via IntelliJ's {@code PersistentStateComponent} under
 * {@code AetherCodeSettings.xml} in the project's {@code .idea/} folder. The component
 * is project-scoped so different projects can target different providers.
 *
 * <p>The API key, if blank, falls back to the env-var appropriate for the chosen
 * provider (see {@link #effectiveApiKey()}). The chosen provider falls back to
 * {@link ChatClientFactory#DEFAULT_PROVIDER} when unset.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "AetherCodeSettings",
    storages = [Storage("AetherCodeSettings.xml")]
)
class AetherCodeSettings : PersistentStateComponent<AetherCodeSettings.State> {

    data class State(
        var provider: String = ChatClientFactory.DEFAULT_PROVIDER,
        var apiKey: String = "",
        var modelOverride: String = "",
        var customBaseUrl: String = ""
    )

    private var myState: State = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val provider: String
        get() = myState.provider.ifBlank { ChatClientFactory.DEFAULT_PROVIDER }

    val apiKey: String
        get() = myState.apiKey

    val modelOverride: String
        get() = myState.modelOverride

    val customBaseUrl: String
        get() = myState.customBaseUrl

    /**
     * Resolve the API key to use, falling back to the env-var appropriate for the
     * chosen provider. Never returns null — empty string when nothing is set.
     */
    fun effectiveApiKey(): String {
        if (myState.apiKey.isNotBlank()) return myState.apiKey
        val envName = when (provider.lowercase()) {
            ChatClientFactory.PROVIDER_MINIMAX -> "MINIMAX_API_KEY"
            else -> "ANTHROPIC_API_KEY"
        }
        return System.getenv(envName) ?: ""
    }

    fun setProvider(p: String) {
        myState = myState.copy(provider = p.ifBlank { ChatClientFactory.DEFAULT_PROVIDER })
    }

    fun setApiKey(k: String) { myState = myState.copy(apiKey = k) }

    fun setModelOverride(m: String) { myState = myState.copy(modelOverride = m) }

    fun setCustomBaseUrl(u: String) { myState = myState.copy(customBaseUrl = u) }

    /** Reset all settings to defaults and clear the cached engine. */
    fun reset() {
        myState = State()
        AetherCodeEngineHolder.invalidate()
    }

    companion object {
        fun getInstance(project: Project): AetherCodeSettings =
                project.getService(AetherCodeSettings::class.java)
    }
}
