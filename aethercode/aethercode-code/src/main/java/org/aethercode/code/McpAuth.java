package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * MCP OAuth surface (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.mcp_auth}
 * module. The Java port exposes the public surface (token storage,
 * device-flow, loopback callback) without the full RFC 8252 dance;
 * the full implementation lands with the MCP runtime port.</p>
 */
public final class McpAuth {
    private McpAuth() {}

    private static final Logger LOG = LoggerFactory.getLogger(McpAuth.class);

    /** Device-code response. */
    public record DeviceCodeResponse(
            String deviceCode,
            String userCode,
            String verificationUri,
            int expiresIn,
            int interval) {
    }

    /** Stored OAuth token. */
    public record TokenSet(
            String accessToken,
            String refreshToken,
            long expiresAtMs) {
    }

    /** Path under the private state dir for the token store. */
    public static Path tokensDir() {
        return ModelConfig.DEFAULT_STATE_DIR.resolve("mcp-tokens");
    }

    /** Start the device flow against a provider. */
    public static CompletionStage<DeviceCodeResponse> startDeviceFlow(String providerUrl) {
        return CompletableFuture.completedFuture(new DeviceCodeResponse(
                "stub-device-code", "STUB-1234", providerUrl, 600, 5));
    }

    /** Exchange a device code for an access token. */
    public static CompletionStage<TokenSet> exchangeDeviceCode(String providerUrl, String deviceCode) {
        return CompletableFuture.completedFuture(new TokenSet(
                "stub-access-token", "stub-refresh-token",
                System.currentTimeMillis() + Duration.ofHours(1).toMillis()));
    }

    /** Run the loopback callback flow. */
    public static CompletionStage<TokenSet> runLoopback(String providerUrl) {
        return CompletableFuture.completedFuture(new TokenSet(
                "stub-access-token", null,
                System.currentTimeMillis() + Duration.ofHours(1).toMillis()));
    }
}
