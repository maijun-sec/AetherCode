package org.aethercode.protocol.rpc.grants;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): result for {@code grants/clear}.
 *
 * <p>Wire format:
 * <pre>
 *   { "ok": true, "revoked": N, "scope": "..." }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrantsClearResult(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("revoked") int revoked,
        @JsonProperty("scope") String scope
) {}
