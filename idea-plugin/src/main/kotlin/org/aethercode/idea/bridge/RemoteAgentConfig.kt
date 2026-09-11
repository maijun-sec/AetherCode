package org.aethercode.idea.bridge

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * R6: persistent configuration for the remote-orchestrator bridge.
 *
 * <p>Stored in {@code .idea/aethercode.xml} so the URL + token persist per-project.
 * The {@code enabled} flag lets the user toggle between local-engine and remote-orchestrator
 * mode without dropping the credentials.
 */
@State(name = "AetherCodeRemoteAgent", storages = [Storage("aethercode.xml")])
class RemoteAgentConfig : PersistentStateComponent<RemoteAgentConfig.State> {

    data class State(
        var enabled: Boolean = false,
        var orchestratorUrl: String = "ws://127.0.0.1:9876/bridge",
        var sessionToken: String = ""
    )

    private var myState: State = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun isEnabled(): Boolean = myState.enabled
    fun setEnabled(value: Boolean) { myState = myState.copy(enabled = value) }
    fun orchestratorUrl(): String = myState.orchestratorUrl
    fun sessionToken(): String = myState.sessionToken
    fun update(url: String, token: String) {
        myState = myState.copy(orchestratorUrl = url, sessionToken = token)
    }

    companion object {
        fun getInstance(project: Project): RemoteAgentConfig =
                project.getService(RemoteAgentConfig::class.java)
    }
}
