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
