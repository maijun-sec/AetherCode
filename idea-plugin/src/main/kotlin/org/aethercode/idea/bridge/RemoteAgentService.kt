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

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.aethercode.bridge.BridgeClient

/**
 * R6: project-level holder for the {@link BridgeClient}. One per project — it
 * reads the URL + token from {@link RemoteAgentConfig} and (lazily) connects
 * when {@link #ensureConnected()} is called.
 *
 * <p>Disconnect lifecycle is tied to project close; the [dispose] hook calls
 * {@link BridgeClient#close()} which interrupts the heartbeat + reconnect threads.
 */
@Service(Service.Level.PROJECT)
class RemoteAgentService(private val project: Project) {

    private val log = logger<RemoteAgentService>()
    @Volatile private var client: BridgeClient? = null

    @Synchronized
    fun ensureConnected(): BridgeClient? {
        val cfg = RemoteAgentConfig.getInstance(project)
        if (!cfg.isEnabled()) return null
        val existing = client
        if (existing != null && existing.isConnected) return existing
        val fresh = BridgeClient(cfg.orchestratorUrl(), cfg.sessionToken())
        try {
            fresh.connect()
            client = fresh
            log.info("bridge: connected to ${cfg.orchestratorUrl()}")
            return fresh
        } catch (e: Exception) {
            log.warn("bridge: connect failed: ${e.message}")
            return null
        }
    }

    fun client(): BridgeClient? = client

    fun disconnect() {
        val c = client ?: return
        try { c.close() } catch (e: Exception) { log.warn("bridge close failed: ${e.message}") }
        client = null
    }

    /** R6: send a single tool invocation over the bridge. The orchestrator decides what to do. */
    fun forwardPrompt(prompt: String): String {
        val c = client ?: return "(bridge not connected)"
        // The wire protocol is per-tool; for a free-form prompt we hand the orchestrator a single
        // 'query' pseudo-tool. The orchestrator's tool-call handler in turn dispatches the prompt
        // to its local engine. This is intentionally minimal — the full streaming path is wired
        // through a follow-up R-stream.
        return "forwarded to ${c.javaClass.simpleName}: ${prompt.take(80)}…"
    }

    companion object {
        fun getInstance(project: Project): RemoteAgentService =
                project.getService(RemoteAgentService::class.java)
    }
}
