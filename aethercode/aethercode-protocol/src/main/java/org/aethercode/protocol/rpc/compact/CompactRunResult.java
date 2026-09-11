package org.aethercode.protocol.rpc.compact;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-17 / design.md §3.1): result for {@code compact/run}.
 *
 * <p>Wire format:
 * <pre>
 *   { "sessionId": "...",
 *     "ok": true, "skipped": false,
 *     "layer": "summary"|"raw"|null,
 *     "tokensBefore": N, "tokensAfter": M,
 *     "elapsedMs": K }
 * </pre>
 *
 * <p>{@code skipped} is true when the compactor refused the run
 * (no compactor wired, or autoCompactDisabled on this session).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactRunResult(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("ok") boolean ok,
        @JsonProperty("skipped") boolean skipped,
        @JsonProperty("layer") String layer,
        @JsonProperty("tokensBefore") Long tokensBefore,
        @JsonProperty("tokensAfter") Long tokensAfter,
        @JsonProperty("elapsedMs") long elapsedMs
) {}
