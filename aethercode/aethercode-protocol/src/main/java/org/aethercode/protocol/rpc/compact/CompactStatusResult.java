package org.aethercode.protocol.rpc.compact;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Phase 1.2 (T-1-17 / design.md §3.1): result for {@code compact/status}.
 *
 * <p>Wire format:
 * <pre>
 *   { "sessionId": "...",
 *     "autoCompactDisabled": false,
 *     "lastEvent": { tsMs, tokensBefore, tokensAfter, layer, ok }?,
 *     "tokensUsed": N, "tokensBudget": M, "percent": 0.42 }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactStatusResult(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("autoCompactDisabled") boolean autoCompactDisabled,
        @JsonProperty("lastEvent") Map<String, Object> lastEvent,
        @JsonProperty("tokensUsed") long tokensUsed,
        @JsonProperty("tokensBudget") long tokensBudget,
        @JsonProperty("percent") double percent
) {}
