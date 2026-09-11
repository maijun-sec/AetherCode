package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): params for {@code task/events}.
 *
 * <p>Wire format:
 * <pre>
 *   { id, sinceSeq?, limit? }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskEventsParams(
        @JsonProperty("id") String id,
        @JsonProperty("sinceSeq") Long sinceSeq,
        @JsonProperty("limit") Integer limit
) {
    public TaskEventsParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
