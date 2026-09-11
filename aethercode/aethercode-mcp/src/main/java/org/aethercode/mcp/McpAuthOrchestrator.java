package org.aethercode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aethercode.mcp.auth.OAuthCallbackServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * end-to-end MCP OAuth orchestrator. Wires {@link McpOAuthFlow},
 * {@link OAuthCallbackServer}, and the on-disk token store together. The CLI
 * {@code aethercode mcp auth <server>} subcommand is a thin wrapper around this.
 *
 * <p>The orchestrator is deliberately split from the CLI:
 * <ul>
 *   <li>Config loading is in {@link #loadAuthConfig(String)} — pure I/O, easy to test</li>
 *   <li>The OAuth dance is in {@link #run(String, McpOAuthFlow, McpOAuthFlow.Pkce, long, TimeUnit)}
 *       — it accepts an injected {@link OAuthCallbackServer} factory so tests can
 *       drive a callback without binding a TCP port</li>
 *   <li>Token persistence is in {@link #persistToken(String, McpOAuthFlow.Token)}</li>
 * </ul>
 */
public final class McpAuthOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(McpAuthOrchestrator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path mcpConfig;
    private final Path tokenStore;
    private final int defaultPort;
    /** second-layer stampede defense — refuses re-attempts within the cooldown. */
    private final AuthRateLimiter rateLimiter;

    public McpAuthOrchestrator(Path mcpConfig, Path tokenStore, int defaultPort) {
        this(mcpConfig, tokenStore, defaultPort, new AuthRateLimiter());
    }

    public McpAuthOrchestrator(Path mcpConfig, Path tokenStore, int defaultPort, AuthRateLimiter limiter) {
        this.mcpConfig = mcpConfig;
        this.tokenStore = tokenStore;
        this.defaultPort = defaultPort;
        this.rateLimiter = limiter;
    }

    public McpAuthOrchestrator(Path mcpConfig, Path tokenStore) {
        this(mcpConfig, tokenStore, pickFreePort(), new AuthRateLimiter());
    }

    /** @return true when the caller may proceed with an auth attempt; false if the cooldown is active. */
    public boolean tryAcquireAuthSlot(String serverId) { return rateLimiter.tryAcquire(serverId); }

    public AuthRateLimiter rateLimiter() { return rateLimiter; }

    /** OAuth-related fields in mcp.json. */
    public record ServerAuth(
            String authEndpoint,
            String tokenEndpoint,
            String clientId,
            String scope,
            String redirectUri,
            int callbackPort
    ) {}

    /** Read mcp.json and return the auth descriptor for {@code serverName}, or null if missing. */
    @SuppressWarnings("unchecked")
    public ServerAuth loadAuthConfig(String serverName) throws IOException {
        if (!Files.exists(mcpConfig)) return null;
        Map<String, Object> root = MAPPER.readValue(Files.readString(mcpConfig), Map.class);
        Map<String, Object> servers = (Map<String, Object>) root.get("mcpServers");
        if (servers == null) return null;
        Object v = servers.get(serverName);
        if (!(v instanceof Map<?, ?>)) return null;
        Map<String, Object> cfg = (Map<String, Object>) v;
        Object authObj = cfg.get("auth");
        if (!(authObj instanceof Map<?, ?>)) return null;
        Map<String, Object> auth = (Map<String, Object>) authObj;
        String authEp = str(auth.get("authEndpoint"));
        String tokEp = str(auth.get("tokenEndpoint"));
        String cid = str(auth.get("clientId"));
        if (authEp == null || tokEp == null || cid == null) return null;
        String redirect = str(auth.getOrDefault("redirectUri", "http://127.0.0.1:" + defaultPort + "/callback"));
        int explicitPort = intOr(auth.get("callbackPort"), parsePort(redirect));
        return new ServerAuth(
                authEp,
                tokEp,
                cid,
                str(auth.getOrDefault("scope", "")),
                redirect,
                explicitPort
        );
    }

    private static int parsePort(String url) {
        try {
            int at = url.indexOf("://");
            int colon = url.indexOf(':', at + 3);
            int slash = url.indexOf('/', colon + 1);
            return Integer.parseInt(url.substring(colon + 1, slash < 0 ? url.length() : slash));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Run the OAuth dance: start a callback server (via {@code serverFactory}),
     * wait for the auth code, exchange it, persist the token, return the token.
     *
     * @param serverName the MCP server name (used as the token key)
     * @param flow       the OAuth flow
     * @param pkce       the PKCE pair
     * @param timeout    how long to wait for the browser to redirect
     * @param unit       the timeout unit
     * @param serverFactory creates a started {@link OAuthCallbackServer}; tests inject a fake
     */
    public McpOAuthFlow.Token run(String serverName,
                                  McpOAuthFlow flow,
                                  McpOAuthFlow.Pkce pkce,
                                  long timeout,
                                  TimeUnit unit,
                                  Function<Integer, OAuthCallbackServer> serverFactory) throws Exception {
        int port = flowRedirectPort(flow);
        OAuthCallbackServer server = serverFactory.apply(port);
        try {
            String code = server.await(timeout, unit);
            McpOAuthFlow.Token token = flow.exchange(code, pkce);
            persistToken(serverName, token);
            return token;
        } finally {
            server.close();
        }
    }

    /** Persist a token to {@link #tokenStore} keyed by server name. */
    public synchronized void persistToken(String serverName, McpOAuthFlow.Token token) throws IOException {
        Files.createDirectories(tokenStore.getParent());
        ObjectNode root;
        if (Files.exists(tokenStore)) {
            try {
                root = (ObjectNode) MAPPER.readTree(Files.readString(tokenStore));
            } catch (Exception e) {
                root = MAPPER.createObjectNode();
            }
        } else {
            root = MAPPER.createObjectNode();
        }
        ObjectNode entry = root.putObject(serverName);
        entry.put("access_token", token.accessToken());
        if (token.refreshToken() != null) entry.put("refresh_token", token.refreshToken());
        entry.put("expires_at_ms", token.expiresAtMs());
        Files.writeString(tokenStore, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.info("persisted token for '{}' (expires at {})", serverName, token.expiresAtMs());
    }

    private static int flowRedirectPort(McpOAuthFlow flow) {
        String uri = flow.redirectUri();
        try {
            int at = uri.indexOf("://");
            int colon = uri.indexOf(':', at + 3);
            int slash = uri.indexOf('/', colon + 1);
            return Integer.parseInt(uri.substring(colon + 1, slash < 0 ? uri.length() : slash));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static int intOr(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }

    private static int pickFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return 8765;
        }
    }
}
