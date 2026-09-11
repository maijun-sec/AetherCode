package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-14 / design.md §3.1): params for {@code session/events}.
 *
 * <p>Wire format:
 * <pre>
 *   { id, sinceSeq?, limit? }
 * </pre>
 *
 * <p>The response is a stream of JSONL records (one JSON object
 * per line) over the supervisor socket. The first line is a
 * {@code hello} envelope that echoes the params + the most
 * recent sequence id; subsequent lines are the buffered events
 * since the cursor.
 *
 * <p>{@code sinceSeq} is the last sequence number the client
 * already saw; the server starts at {@code sinceSeq + 1}.
 * {@code limit} caps the buffer replay (default 1000).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEventsParams(
        @JsonProperty("id") String id,
        @JsonProperty("sinceSeq") Long sinceSeq,
        @JsonProperty("limit") Integer limit
) {
    public SessionEventsParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
