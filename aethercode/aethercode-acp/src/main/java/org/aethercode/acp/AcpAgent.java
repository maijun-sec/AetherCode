package org.aethercode.acp;

import org.aethercode.acp.schema.Capabilities;
import org.aethercode.acp.schema.ContentBlock;
import org.aethercode.acp.schema.McpServers.McpServer;
import org.aethercode.acp.schema.Responses;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for an ACP server-side agent.
 *
 * <p>Mirrors {@code acp.Agent}. The Java port exposes
 * {@code onConnect(Client)}, the lifecycle hooks
 * ({@code initialize}, {@code newSession}, {@code loadSession},
 * {@code setSessionMode}, {@code setConfigOption},
 * {@code cancel}, {@code prompt}), and a {@link #run(Client)}
 * entry point that drives the JSON-RPC handshake when a
 * concrete transport is wired in.</p>
 */
public abstract class AcpAgent {

    private Client connection;

    /**
     * Store the client connection. Mirrors
     * {@code acp.Agent.on_connect}. Subclasses may override
     * to react to the connection event, but the default
     * implementation only stores the reference.
     */
    public void onConnect(Client connection) {
        this.connection = connection;
    }

    /** The client connection, or {@code null} before
     *  {@link #onConnect} runs. */
    protected Client connection() {
        return connection;
    }

    /** Reply to the {@code initialize} request. */
    public abstract CompletableFuture<Responses.InitializeResponse> initialize(
            int protocolVersion,
            Capabilities.ClientCapabilities clientCapabilities,
            Capabilities.Implementation clientInfo,
            Map<String, Object> kwargs);

    /** Handle {@code session/new}. */
    public abstract CompletableFuture<Responses.NewSessionResponse> newSession(
            String cwd,
            List<String> additionalDirectories,
            List<McpServer> mcpServers,
            Map<String, Object> kwargs);

    /** Handle {@code session/load}. */
    public abstract CompletableFuture<Responses.LoadSessionResponse> loadSession(
            String cwd,
            String sessionId,
            List<String> additionalDirectories,
            List<McpServer> mcpServers,
            Map<String, Object> kwargs);

    /** Handle {@code session/set_mode}. */
    public abstract CompletableFuture<Responses.SetSessionModeResponse> setSessionMode(
            String modeId, String sessionId, Map<String, Object> kwargs);

    /** Handle {@code session/set_config_option}. */
    public abstract CompletableFuture<Responses.SetSessionConfigOptionResponse> setConfigOption(
            String configId, String sessionId, Object value, Map<String, Object> kwargs);

    /** Handle {@code session/cancel}. */
    public abstract CompletableFuture<Void> cancel(
            String sessionId, Map<String, Object> kwargs);

    /** Handle {@code session/prompt}. */
    public abstract CompletableFuture<Responses.PromptResponse> prompt(
            List<ContentBlock> prompt,
            String sessionId,
            String messageId,
            Map<String, Object> kwargs);

    /**
     * Run the agent against a {@link Client} until the client
     * closes. The default implementation simply calls
     * {@link #onConnect(Client)} and returns. Subclasses
     * (typically the {@code RunAcpAgent} transport) override
     * this to drive the JSON-RPC handshake.
     */
    public void run(Client client) {
        onConnect(client);
    }
}
