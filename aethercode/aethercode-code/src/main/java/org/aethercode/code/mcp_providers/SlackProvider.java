package org.aethercode.code.mcp_providers;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Slack-hosted MCP OAuth provider.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.mcp_providers.slack} module. Slack's hosted
 * MCP endpoint uses the Authorization Code flow with a hardcoded
 * public client ID and a fixed pre-registered loopback redirect URI
 * ({@code http://localhost:3118/callback}).</p>
 */
public final class SlackProvider extends OAuthProvider {

    /** Public OAuth client ID registered with Slack for the hosted MCP endpoint. */
    public static final String SLACK_MCP_CLIENT_ID = "4518649543379.10944517634130";
    /** Fixed TCP port the local callback server binds to for Slack OAuth. */
    public static final int SLACK_LOOPBACK_PORT = 3118;
    /** Pre-registered loopback redirect URI for the Slack MCP OAuth app. */
    public static final String SLACK_REDIRECT_URI =
            "http://localhost:" + SLACK_LOOPBACK_PORT + "/callback";

    /** Return {@code true} when {@code url} points at a Slack-hosted MCP endpoint. */
    public static boolean isSlackMcpUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            return "slack.com".equals(host) || host.endsWith(".slack.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean matches(String serverUrl) {
        return isSlackMcpUrl(serverUrl);
    }

    @Override
    public Integer loopbackPort() {
        return SLACK_LOOPBACK_PORT;
    }

    @Override
    public OAuthClientMetadata clientMetadata(URI redirectUri) {
        return OAuthClientMetadata.builder()
                .redirectUris(List.of(URI.create(SLACK_REDIRECT_URI)))
                .tokenEndpointAuthMethod("none")
                .build();
    }

    @Override
    public CompletableFuture<LoginResult> runLogin(LoginRequest request) {
        // The Python module queries a prompt_slack_team_id capability
        // off the OAuthInteraction. The Java port passes through; the
        // concrete CLI surface extends OAuthInteraction with the team
        // prompt method.
        return CompletableFuture.completedFuture(new LoginResult(false, Map.of()));
    }
}
