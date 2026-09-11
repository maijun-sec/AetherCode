package org.aethercode.idea.engine

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * R7: persistent settings for the backend selection. Lives next to
 * {@link AetherCodeSettings} so all plugin configuration is in one
 * place. The actual UI control is rendered in
 * {@code BackendConfigurable} (R8) — R7 only stores and exposes
 * the values.
 *
 * <p>Mode values:
 * <ul>
 *   <li>{@code "auto"} (default) — prefer daemon, fall back to in-process.</li>
 *   <li>{@code "daemon"} — require daemon, fail if not reachable.</li>
 *   <li>{@code "in-process"} — always use the embedded engine.</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
@State(name = "AetherCodeBackendSettings", storages = [Storage("AetherCodeBackendSettings.xml")])
class BackendSettings : PersistentStateComponent<BackendSettings.State> {

    data class State(
        var mode: String = MODE_AUTO,
        var daemonLockPath: String = "",
        var model: String = ""
    )

    private var myState: State = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    val mode: String get() = myState.mode
    val daemonLockPath: String get() = myState.daemonLockPath
    val model: String? get() = myState.model.ifBlank { null }

    fun setMode(m: String) { myState = myState.copy(mode = m) }
    fun setDaemonLockPath(p: String) { myState = myState.copy(daemonLockPath = p) }
    fun setModel(m: String) { myState = myState.copy(model = m) }

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_DAEMON = "daemon"
        const val MODE_IN_PROCESS = "in-process"
        fun getInstance(project: Project): BackendSettings =
            project.getService(BackendSettings::class.java)
    }
}
