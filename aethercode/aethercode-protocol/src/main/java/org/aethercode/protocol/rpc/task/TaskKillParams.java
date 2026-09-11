package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): params for {@code task/kill}
 * (refined).
 *
 * <p>Wire format:
 * <pre>
 *   { id, reason? }
 * </pre>
 *
 * <p>Triggers a graceful shutdown. The task stops accepting new
 * tool calls, the child row is moved to {@code KILLED}, and
 * the {@code reason} (if any) is appended to the events log
 * for the user to inspect later.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskKillParams(
        @JsonProperty("id") String id,
        @JsonProperty("reason") String reason
) {
    public TaskKillParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
