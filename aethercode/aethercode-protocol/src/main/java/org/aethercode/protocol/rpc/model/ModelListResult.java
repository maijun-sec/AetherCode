package org.aethercode.protocol.rpc.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Phase 1.2 (T-1-19 / design.md §3.1): result for {@code model/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { "models": [ {name, provider, tier, contextWindow,
 *                   maxOutputTokens, capabilities: [..],
 *                   inputUsdPerMtok, outputUsdPerMtok,
 *                   cachedUsdPerMtok, lastUsed?}, ... ] }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelListResult(
        @JsonProperty("models") List<Map<String, Object>> models
) {}
