// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
