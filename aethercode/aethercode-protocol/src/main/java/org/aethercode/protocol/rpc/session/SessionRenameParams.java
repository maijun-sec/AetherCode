package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): params for {@code session/rename}.
 *
 * <p>Wire format:
 * <pre>
 *   { id, title }
 * </pre>
 *
 * <p>Both fields are required. The title is the user-editable
 * string surfaced in the session list; it is persisted to the
 * session row's {@code title} column and synced to the JSONL
 * transcript on flush.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionRenameParams(
        @JsonProperty("id") String id,
        @JsonProperty("title") String title
) {
    public SessionRenameParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (title == null) {
            throw new IllegalArgumentException("title is required");
        }
    }
}
