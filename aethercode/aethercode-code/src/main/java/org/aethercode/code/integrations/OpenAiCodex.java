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

package org.aethercode.code.integrations;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI Codex integration helpers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.integrations.openai_codex} module. Provides
 * request shape and authentication parameter handling for the OpenAI
 * Codex login flow.</p>
 */
public final class OpenAiCodex {
    private OpenAiCodex() {}

    /** Codex-specific authentication parameters. */
    public record AuthParams(String clientId, List<String> scopes, String redirectUri) {
        public AuthParams {
            Objects.requireNonNull(clientId, "clientId");
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    /**
     * Build the OpenAI Codex login URL.
     */
    public static String buildLoginUrl(AuthParams params) {
        StringBuilder sb = new StringBuilder("https://auth.openai.com/authorize");
        sb.append("?client_id=").append(params.clientId());
        sb.append("&response_type=code");
        sb.append("&redirect_uri=").append(params.redirectUri());
        if (!params.scopes().isEmpty()) {
            sb.append("&scope=").append(String.join("+", params.scopes()));
        }
        return sb.toString();
    }

    /**
     * Build the standard Codex request headers.
     */
    public static Map<String, String> buildHeaders(String accessToken) {
        Objects.requireNonNull(accessToken, "accessToken");
        return Map.of(
                "Authorization", "Bearer " + accessToken,
                "Content-Type", "application/json",
                "Accept", "application/json");
    }
}
