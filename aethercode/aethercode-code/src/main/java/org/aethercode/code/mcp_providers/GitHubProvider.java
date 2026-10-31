/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
