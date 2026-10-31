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
