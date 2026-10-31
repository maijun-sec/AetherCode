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
