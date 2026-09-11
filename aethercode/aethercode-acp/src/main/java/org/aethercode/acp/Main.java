package org.aethercode.acp;

import org.aethercode.acp.schema.PermissionOption;
import org.aethercode.acp.schema.SessionUpdate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point for running the ACP server as a module.
 *
 * <p>Mirror of the Python {@code deepagents_acp.__main__}
 * module. The Java port boots a default test agent (the same
 * filesystem-backed agent the Python port's
 * {@code _serve_test_agent} builds) and runs it through the
 * in-process ACP transport. Without a JSON-RPC transport the
 * default main wires a no-op client; the
 * {@code deepagents-examples} module demonstrates the real
 * transport.</p>
 */
public final class Main {

    private Main() {}

    /**
     * Build a default test agent and run it against a
     * stub client. Mirrors {@code python -m deepagents_acp}.
     */
    public static void main(String[] args) {
        // The Python port reads .env via `dotenv`. The Java
        // port intentionally omits that step: there is no
        // dotenv client in the standard library and
        // application-level env loading belongs to the
        // application, not the transport. Users who want
        // dotenv behaviour should add
        // {@code io.github.cdimascio:dotenv-java} to their
        // own project.
        AgentServerACP agent = TestAgentFactory.createDefault();
        // The default transport is a no-op; transport
        // implementations (stdio, websocket, ...) are
        // out of scope for this module and live in
        // {@code deepagents-examples}.
        runAcpAgent(agent);
    }

    /**
     * Run an agent against a client. Mirrors the Python
     * {@code acp.run_agent(...)} entry point. The Java
     * port currently provides a no-op default (no
     * JSON-RPC transport is bundled); real transports
     * (stdio, websocket, IDE frontends, ...) live in
     * the host application.
     */
    public static void runAcpAgent(AcpAgent agent) {
        if (agent == null) return;
        agent.run(new NoopClient());
    }

    /**
     * No-op client used by the default {@link #main(String[])}
     * entry point. Real clients (the IDE / frontend) live in
     * the host application.
     */
    private static final class NoopClient implements Client {
        @Override
        public void sessionUpdate(String sessionId, SessionUpdate update, String source) {
            // No-op: the default entry point does not have a
            // JSON-RPC transport. Hosts (deepagents-examples,
            // IDEs) supply a real client.
        }

        @Override
        public PermissionOutcome requestPermission(
                String sessionId,
                ToolCallView toolCall,
                List<PermissionOption> options) {
            // Default to "approve" so the in-process demo
            // can run unattended.
            return PermissionOutcome.selected("approve");
        }
    }
}
