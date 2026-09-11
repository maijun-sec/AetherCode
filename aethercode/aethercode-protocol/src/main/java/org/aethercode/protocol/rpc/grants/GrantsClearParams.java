package org.aethercode.protocol.rpc.grants;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): params for {@code grants/clear}.
 *
 * <p>Wire format:
 * <pre>
 *   { scope, sessionId? }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrantsClearParams(
        @JsonProperty("scope") String scope,
        @JsonProperty("sessionId") String sessionId
) {
    public GrantsClearParams {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope is required");
        }
    }
}
