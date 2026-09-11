package org.aethercode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP OAuth flow. Modelled on the TS {@code src/services/mcp/auth.ts} OAuth client.
 *
 * <p>Implements the standard OAuth 2.0 authorisation-code-with-PKCE dance:
 *
 * <ol>
 *   <li>Generate a code verifier + challenge</li>
 *   <li>Open the auth URL in a browser (the host launches it)</li>
 *   <li>Receive the auth code via a local listener</li>
 *   <li>Exchange the code for an access token</li>
 *   <li>Cache the token; on 401, refresh and retry once</li>
 * </ol>
 *
 * <p>prior round ships the shape; the local-listener and browser-launch glue is up to the host
 * (CLI prints the URL, IDEA uses {@code BrowserUtil.browse}).
 */
public class McpOAuthFlow {

    private static final Logger LOG = LoggerFactory.getLogger(McpOAuthFlow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String authEndpoint;
    private final String tokenEndpoint;
    private final String clientId;
    private final String redirectUri;
    private final String scope;

    public McpOAuthFlow(String authEndpoint, String tokenEndpoint, String clientId,
                        String redirectUri, String scope) {
        this.authEndpoint = authEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.scope = scope;
    }

    public String authEndpoint() { return authEndpoint; }
    public String tokenEndpoint() { return tokenEndpoint; }
    public String clientId() { return clientId; }
    public String redirectUri() { return redirectUri; }
    public String scope() { return scope; }

    /** Generate a code verifier + S256 challenge. */
    public static class Pkce {
        public final String verifier;
        public final String challenge;
        public Pkce(String v, String c) { verifier = v; challenge = c; }
    }

    public static Pkce generatePkce() {
        byte[] v = new byte[32];
        new java.security.SecureRandom().nextBytes(v);
        String verifier = base64Url(v);
        try {
            byte[] sha = java.security.MessageDigest.getInstance("SHA-256").digest(v);
            return new Pkce(verifier, base64Url(sha));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String buildAuthUrl(Pkce pkce, String state) {
        return authEndpoint
                + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(scope == null ? "" : scope)
                + "&code_challenge=" + pkce.challenge
                + "&code_challenge_method=S256"
                + "&state=" + enc(state);
    }

    /** Exchange the auth code for an access token. */
    public Token exchange(String code, Pkce pkce) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("grant_type", "authorization_code");
            body.put("code", code);
            body.put("redirect_uri", redirectUri);
            body.put("client_id", clientId);
            body.put("code_verifier", pkce.verifier);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("token exchange failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            String access = root.path("access_token").asText();
            String refresh = root.path("refresh_token").asText(null);
            long expires = System.currentTimeMillis() + root.path("expires_in").asLong(3600) * 1000;
            return new Token(access, refresh, expires);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("oauth exchange failed: " + e.getMessage(), e);
        }
    }

    /** Refresh an expired token. */
    public Token refresh(Token old) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", old.refreshToken);
            body.put("client_id", clientId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req,
                    BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("refresh failed: HTTP " + resp.statusCode());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            return new Token(
                    root.path("access_token").asText(),
                    root.path("refresh_token").asText(old.refreshToken),
                    System.currentTimeMillis() + root.path("expires_in").asLong(3600) * 1000
            );
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("oauth refresh failed: " + e.getMessage(), e);
        }
    }

    public record Token(String accessToken, String refreshToken, long expiresAtMs) {
        public boolean isExpired() { return System.currentTimeMillis() >= expiresAtMs - 60_000; }
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
