package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Phase 1.2 (T-1-13 / design.md §3.1): result for {@code session/trash}.
 *
 * <p>Wire format depends on the mode:
 * <pre>
 *   { "trashed": [ SessionSummary, ... ], "total": N, "offset": O, "limit": L }   // list
 *   { "removed": N }                                                              // empty
 *   { "restoredId": "..." }                                                       // restore
 *   { "count": N }                                                                // bare count
 * </pre>
 *
 * <p>Only the fields relevant to the invoked mode are set; the
 * others are omitted from the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionTrashResult(
        @JsonProperty("trashed") List<SessionListResult.SessionSummary> trashed,
        @JsonProperty("total") Integer total,
        @JsonProperty("offset") Integer offset,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("removed") Integer removed,
        @JsonProperty("restoredId") String restoredId,
        @JsonProperty("count") Integer count
) {
    public static SessionTrashResult list(List<SessionListResult.SessionSummary> rows,
                                          int total, int offset, int limit) {
        return new SessionTrashResult(rows, total, offset, limit, null, null, null);
    }
    public static SessionTrashResult empty(int removed) {
        return new SessionTrashResult(null, null, null, null, removed, null, null);
    }
    public static SessionTrashResult restored(String id) {
        return new SessionTrashResult(null, null, null, null, null, id, null);
    }
    public static SessionTrashResult count(int n) {
        return new SessionTrashResult(null, null, null, null, null, null, n);
    }
}
