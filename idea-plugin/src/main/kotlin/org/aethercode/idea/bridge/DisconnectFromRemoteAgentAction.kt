package org.aethercode.idea.bridge

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * R6: companion to {@link ConnectToRemoteAgentAction} — drops the bridge connection
 * and flips the project back to local-engine mode.
 */
class DisconnectFromRemoteAgentAction : AnAction("Disconnect from Remote AetherCode Agent"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = RemoteAgentConfig.getInstance(project)
        cfg.setEnabled(false)
        RemoteAgentService.getInstance(project).disconnect()
    }
}
