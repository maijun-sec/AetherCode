package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): params for {@code task/attach}
 * (refined — same shape as v1).
 *
 * <p>Wire format:
 * <pre>
 *   { id, sinceSeq?, limit? }
 * </pre>
 *
 * <p>The server replies with the child snapshot + the last
 * {@code limit} events since {@code sinceSeq}; the renderer
 * uses this to back-fill its UI on re-attach.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskAttachParams(
        @JsonProperty("id") String id,
        @JsonProperty("sinceSeq") Long sinceSeq,
        @JsonProperty("limit") Integer limit
) {
    public TaskAttachParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
