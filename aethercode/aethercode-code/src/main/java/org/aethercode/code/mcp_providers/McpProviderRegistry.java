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
