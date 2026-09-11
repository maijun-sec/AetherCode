package org.aethercode.protocol.rpc.grants;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): params for {@code grants/revoke}.
 *
 * <p>Wire format:
 * <pre>
 *   { id }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrantsRevokeParams(
        @JsonProperty("id") String id
) {
    public GrantsRevokeParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
