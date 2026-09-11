package org.aethercode.protocol.rpc.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-19 / design.md §3.1): result for {@code model/set}.
 *
 * <p>Wire format:
 * <pre>
 *   { "sessionId": "...", "name": "...", "ok": true,
 *     "previousName": "..."? }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelSetResult(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("name") String name,
        @JsonProperty("ok") boolean ok,
        @JsonProperty("previousName") String previousName
) {}
