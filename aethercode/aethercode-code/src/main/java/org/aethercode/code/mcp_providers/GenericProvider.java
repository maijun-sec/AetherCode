package org.aethercode.code.mcp_providers;

/**
 * Fallback provider for spec-compliant MCP servers with no quirks.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.mcp_providers.base.GenericProvider}. Matches
 * any URL; the registry places it last so spec-compliant servers
 * always resolve to a usable policy.</p>
 */
public final class GenericProvider extends OAuthProvider {
    @Override
    public boolean matches(String serverUrl) {
        return true;
    }
}
