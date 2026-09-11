package org.aethercode.protocol.rpc.compact;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-17 / design.md §3.1): params for {@code compact/run}
 * (refined, per-session).
 *
 * <p>Wire format:
 * <pre>
 *   { sessionId, mode?: "aggressive"|"balanced"|"minimal" }
 * </pre>
 *
 * <p>Default mode is {@code balanced}. The mode is a hint to the
 * engine's compactor pipeline; the implementation is free to
 * ignore it on the supervisor side (which forwards the call to
 * the engine).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactRunParams(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("mode") String mode
) {
    public CompactRunParams {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
    }
    public String effectiveMode() {
        return mode == null || mode.isBlank() ? "balanced" : mode;
    }
}
