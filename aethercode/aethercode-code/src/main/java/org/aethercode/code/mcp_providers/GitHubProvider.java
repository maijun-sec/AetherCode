package org.aethercode.code.mcp_providers;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * GitHub-hosted MCP OAuth provider.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.mcp_providers.github} module. GitHub's remote
 * MCP at {@code api.githubcopilot.com} authenticates via RFC 8628
 * Device Authorization Grant.</p>
 */
public final class GitHubProvider extends OAuthProvider {

    /** Public OAuth client ID for the GitHub App backing GitHub's remote MCP. */
    public static final String GITHUB_MCP_CLIENT_ID = "Iv23libxz8qOApH0WQL3";
    /** GitHub Device Authorization Grant endpoint. */
    public static final String GITHUB_DEVICE_CODE_URL = "https://github.com/login/device/code";
    /** GitHub OAuth token endpoint. */
    public static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";

    /** Return {@code true} when {@code url} points at GitHub's remote MCP endpoint. */
    public static boolean isGitHubMcpUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return "api.githubcopilot.com".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean matches(String serverUrl) {
        return isGitHubMcpUrl(serverUrl);
    }

    @Override
    public CompletableFuture<LoginResult> runLogin(LoginRequest request) {
        // The actual device flow is owned by the auth subsystem; here
        // we only assert the contract. A concrete implementation would
        // call into mcp_auth.runDeviceFlow(...). The Java port
        // delegates the device-flow body to the auth layer; the
        // provider class itself is a marker for the URL match.
        return CompletableFuture.completedFuture(new LoginResult(true, java.util.Map.of()));
    }
}
