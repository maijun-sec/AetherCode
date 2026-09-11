package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): params for {@code session/show}.
 *
 * <p>Wire format:
 * <pre>
 *   { id }
 * </pre>
 *
 * <p>{@code id} is the session id (i.e. the child id in the
 * supervisor's table). Required; the call returns
 * {@code NOT_FOUND} when no row matches.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionShowParams(
        @JsonProperty("id") String id
) {
    public SessionShowParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
