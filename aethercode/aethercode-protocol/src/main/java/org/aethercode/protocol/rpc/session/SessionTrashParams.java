package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-13 / design.md §3.1): params for {@code session/trash}.
 *
 * <p>Wire format (the three modes share one method):
 * <pre>
 *   { empty: true }                    // empty the trash (irreversible)
 *   { restoreId: "..." }               // restore a single session
 *   { list: true, limit?, offset? }    // list trashed sessions
 * </pre>
 *
 * <p>At most one of {@code empty} / {@code restoreId} / {@code list}
 * may be set. With no flags, the call returns the count of
 * trashed sessions (so the trash tab can show a "12 items"
 * badge without a follow-up list).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionTrashParams(
        @JsonProperty("empty") Boolean empty,
        @JsonProperty("restoreId") String restoreId,
        @JsonProperty("list") Boolean list,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("offset") Integer offset
) {
    public boolean isEmpty() { return Boolean.TRUE.equals(empty); }
    public boolean isList() { return Boolean.TRUE.equals(list); }
    public boolean isRestore() { return restoreId != null && !restoreId.isBlank(); }
}
