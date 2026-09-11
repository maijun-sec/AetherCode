package org.aethercode.code.mcp_providers;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Policy interface for provider-specific MCP OAuth quirks.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.mcp_providers.base} module. Subclasses
 * override {@link #matches(String)} plus whichever of
 * {@link #clientMetadata(URI)} and {@link #runLogin(LoginRequest)} they
 * customize.</p>
 */
public abstract class OAuthProvider {

    /**
     * Outcome of a provider's pre-handshake {@code runLogin} step.
     */
    public record LoginResult(boolean completed, Map<String, String> extraAuthParams) {
        public LoginResult {
            Objects.requireNonNull(extraAuthParams, "extraAuthParams");
        }
        public static LoginResult passThrough() {
            return new LoginResult(false, Map.of());
        }
    }

    /**
     * Standard spec-compliant {@code OAuthClientMetadata} payload.
     *
     * @param clientName   human-readable client name
     * @param redirectUris acceptable redirect URIs
     * @param grantTypes   OAuth grant types
     * @param responseTypes response types
     * @param tokenEndpointAuthMethod token endpoint auth method
     */
    public record OAuthClientMetadata(
            String clientName,
            List<URI> redirectUris,
            List<String> grantTypes,
            List<String> responseTypes,
            String tokenEndpointAuthMethod) {
        public OAuthClientMetadata {
            redirectUris = List.copyOf(redirectUris);
            grantTypes = List.copyOf(grantTypes);
            responseTypes = List.copyOf(responseTypes);
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String clientName = "deepagents-code";
            private List<URI> redirectUris = List.of(URI.create("http://localhost/callback"));
            private List<String> grantTypes = List.of("authorization_code", "refresh_token");
            private List<String> responseTypes = List.of("code");
            private String tokenEndpointAuthMethod;

            public Builder clientName(String v) { this.clientName = v; return this; }
            public Builder redirectUris(List<URI> v) { this.redirectUris = v; return this; }
            public Builder grantTypes(List<String> v) { this.grantTypes = v; return this; }
            public Builder responseTypes(List<String> v) { this.responseTypes = v; return this; }
            public Builder tokenEndpointAuthMethod(String v) {
                this.tokenEndpointAuthMethod = v; return this;
            }
            public OAuthClientMetadata build() {
                return new OAuthClientMetadata(clientName, redirectUris, grantTypes,
                        responseTypes, tokenEndpointAuthMethod);
            }
        }
    }

    /** Client information returned by the OAuth registration step. */
    public record OAuthClientInformationFull(
            String clientId,
            List<URI> redirectUris,
            List<String> grantTypes,
            List<String> responseTypes,
            String tokenEndpointAuthMethod) {
        public OAuthClientInformationFull {
            redirectUris = List.copyOf(redirectUris);
            grantTypes = List.copyOf(grantTypes);
            responseTypes = List.copyOf(responseTypes);
        }
    }

    /**
     * File-backed token storage handle passed to {@link #runLogin(LoginRequest)}.
     */
    public interface FileTokenStorage {
        CompletableFuture<Void> setTokensAndClientInfo(Object token,
                                                       OAuthClientInformationFull clientInfo);
        CompletableFuture<OAuthClientInformationFull> getClientInfo();
        CompletableFuture<Void> setClientInfo(OAuthClientInformationFull clientInfo);
    }

    /**
     * Interaction surface for any provider-specific prompts.
     */
    public interface OAuthInteraction {
        // Marked for future expansion. Concrete prompts live in the
        // CLI / TUI surfaces.
    }

    /** Inputs to a {@code runLogin} call. */
    public record LoginRequest(
            String serverName,
            String serverUrl,
            FileTokenStorage storage,
            OAuthInteraction ui) {}

    /** Return {@code true} when this provider owns {@code serverUrl}. */
    public abstract boolean matches(String serverUrl);

    /**
     * Return whether this provider can use a runtime loopback redirect URI.
     */
    public boolean supportsLoopbackCallback() { return true; }

    /**
     * Return a fixed loopback port, or {@code null} for a random
     * ephemeral port.
     */
    public Integer loopbackPort() { return null; }

    /**
     * Return the {@link OAuthClientMetadata} used to build the auth
     * provider.
     */
    public OAuthClientMetadata clientMetadata(URI redirectUri) {
        URI uri = redirectUri != null ? redirectUri : URI.create("http://localhost/callback");
        return OAuthClientMetadata.builder()
                .redirectUris(List.of(uri))
                .build();
    }

    /**
     * Perform any provider-specific pre-handshake work.
     */
    public CompletableFuture<LoginResult> runLogin(LoginRequest request) {
        return CompletableFuture.completedFuture(LoginResult.passThrough());
    }
}
