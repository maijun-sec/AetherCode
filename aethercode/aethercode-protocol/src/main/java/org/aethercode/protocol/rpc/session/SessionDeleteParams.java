package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): params for {@code session/delete}.
 *
 * <p>Wire format:
 * <pre>
 *   { id, hard? }
 * </pre>
 *
 * <p>{@code id} is required. The default is soft-delete (move
 * to trash, 30-day retention). Pass {@code hard=true} to delete
 * permanently — that's what the Trash panel's
 * "Delete forever" button sends.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionDeleteParams(
        @JsonProperty("id") String id,
        @JsonProperty("hard") Boolean hard
) {
    public SessionDeleteParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
