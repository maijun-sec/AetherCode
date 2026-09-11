package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): result for {@code session/spawn}.
 *
 * <p>Wire format:
 * <pre>
 *   { "sessionId": "...", "title": "...", "status": "QUEUED" }
 * </pre>
 *
 * <p>The {@code sessionId} is the child id in the supervisor's
 * table; the same id is what every other {@code session/*} and
 * {@code task/*} method takes. The status is one of the
 * {@code ChildStatus} enum values.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionSpawnResult(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("title") String title,
        @JsonProperty("status") String status,
        @JsonProperty("parentId") String parentId
) {}
