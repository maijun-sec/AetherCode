/**
 * Policy interface and registry for provider-specific MCP OAuth quirks.
 *
 * <p>Java 21 port of the Python {@code deepagents_code.mcp_providers}
 * package. Each concrete provider module subclasses
 * {@link org.aethercode.code.mcp_providers.OAuthProvider} to encode its
 * own URL match rule, client metadata, and any pre-handshake login
 * steps. {@link McpProviderRegistry#resolveProvider(String)} dispatches
 * to the first matching provider.</p>
 */
package org.aethercode.code.mcp_providers;
