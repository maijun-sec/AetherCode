/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.protocol.rpc.compact;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-17 / design.md §3.1): params for {@code compact/status}
 * (refined, per-session).
 *
 * <p>Wire format:
 * <pre>
 *   { sessionId }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactStatusParams(
        @JsonProperty("sessionId") String sessionId
) {
    public CompactStatusParams {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
    }
}
