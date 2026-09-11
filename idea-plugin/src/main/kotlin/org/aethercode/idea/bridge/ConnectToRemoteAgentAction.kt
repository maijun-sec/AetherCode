package org.aethercode.idea.bridge

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/**
 * R6: toggle action — connect the current project to a remote orchestrator over the bridge.
 *
 * <p>Prompts for the URL + token the first time, then persists them in {@link RemoteAgentConfig}
 * and switches the chat panel into remote mode.
 */
class ConnectToRemoteAgentAction : AnAction("Connect to Remote AetherCode Agent…"), DumbAware {

    private val log = logger<ConnectToRemoteAgentAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = RemoteAgentConfig.getInstance(project)
        val url = Messages.showInputDialog(
                project,
                "Orchestrator WebSocket URL (e.g. ws://host:9876/bridge):",
                "Remote AetherCode Agent",
                Messages.getQuestionIcon(),
                cfg.orchestratorUrl(),
                null
        ) ?: return
        val token = Messages.showInputDialog(
                project,
                "Session token (leave blank to auto-generate):",
                "Remote AetherCode Agent",
                Messages.getQuestionIcon(),
                cfg.sessionToken(),
                null
        ) ?: return
        val finalToken = if (token.isBlank()) java.util.UUID.randomUUID().toString() else token
        cfg.update(url, finalToken)
        cfg.setEnabled(true)
        val svc = RemoteAgentService.getInstance(project)
        val client = svc.ensureConnected()
        if (client == null) {
            Messages.showErrorDialog(project,
                    "Failed to connect to $url. Check the URL and that the orchestrator is running.",
                    "Remote AetherCode Agent")
        } else {
            Messages.showInfoMessage(project,
                    "Connected. Subsequent prompts will be routed to the remote orchestrator.",
                    "Remote AetherCode Agent")
        }
    }
}
