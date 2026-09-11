package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): params for {@code session/restore}.
 *
 * <p>Wire format:
 * <pre>
 *   { id }
 * </pre>
 *
 * <p>Moves a session out of the trash back to the active list.
 * Returns {@code NOT_FOUND} if the id is unknown, or
 * {@code INVALID_PARAMS} if the session is not currently in
 * the trash (already active sessions don't need a restore).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionRestoreParams(
        @JsonProperty("id") String id
) {
    public SessionRestoreParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
