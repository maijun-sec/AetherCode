package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): result for {@code task/setLimits}.
 *
 * <p>Wire format:
 * <pre>
 *   { "id": "...", "ok": true,
 *     "limits": { wallClockMs, tokens, calls,
 *                 fileWrites, network, idleMs } }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskSetLimitsResult(
        @JsonProperty("id") String id,
        @JsonProperty("ok") boolean ok,
        @JsonProperty("limits") Map<String, Object> limits
) {}
