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

import java.util.List;

/**
 * Ordered provider registry for MCP OAuth dispatch.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.mcp_providers._registry} module. The
 * registry is initialized lazily; tests can add or replace providers
 * via {@link #register(OAuthProvider)}.</p>
 */
public final class McpProviderRegistry {
    private static final List<OAuthProvider> DEFAULT_REGISTRY = List.of(
            new SlackProvider(),
            new GitHubProvider(),
            new GenericProvider()
    );

    private static volatile List<OAuthProvider> REGISTRY = DEFAULT_REGISTRY;

    private McpProviderRegistry() {}

    /** Return the first matching {@link OAuthProvider}; fall back to {@link GenericProvider}. */
    public static OAuthProvider resolveProvider(String serverUrl) {
        for (OAuthProvider p : REGISTRY) {
            if (p.matches(serverUrl)) return p;
        }
        throw new IllegalStateException("No MCP OAuth provider matched '" + serverUrl + "'");
    }

    /** Replace the registry (tests only). */
    public static void register(List<OAuthProvider> providers) {
        REGISTRY = List.copyOf(providers);
    }

    /** Reset the registry to its default. */
    public static void reset() {
        REGISTRY = DEFAULT_REGISTRY;
    }
}
