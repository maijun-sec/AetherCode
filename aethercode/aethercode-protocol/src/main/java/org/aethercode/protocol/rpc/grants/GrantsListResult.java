package org.aethercode.protocol.rpc.grants;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): result for {@code grants/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { "grants": [ {id, scope, scopeId, category, decision,
 *                   reason, createdAtMs, expiresAtMs?}, ... ] }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrantsListResult(
        @JsonProperty("grants") List<Map<String, Object>> grants
) {}
