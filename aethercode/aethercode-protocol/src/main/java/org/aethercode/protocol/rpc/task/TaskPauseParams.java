package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): params for {@code task/pause}
 * (refined).
 *
 * <p>Wire format:
 * <pre>
 *   { id, reason? }
 * </pre>
 *
 * <p>The optional {@code reason} is written to the child's
 * {@code error} column when the transition is forced (e.g. on
 * a limit hit). It surfaces in the session's events log so a
 * user can see why a session paused.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskPauseParams(
        @JsonProperty("id") String id,
        @JsonProperty("reason") String reason
) {
    public TaskPauseParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
