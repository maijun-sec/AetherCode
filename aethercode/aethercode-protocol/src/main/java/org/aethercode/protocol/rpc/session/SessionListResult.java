package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): result for {@code session/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { "sessions": [ {id, title, cwd, lastActiveAtMs, tokenTotal,
 *                     parentSessionId, status, trashedAtMs?,
 *                     promptPreview}, ... ],
 *     "total": N,
 *     "offset": O,
 *     "limit": L }
 * </pre>
 *
 * <p>The {@code total} field is the unpaginated count, so a
 * renderer can show "showing 1-50 of 273" without a second
 * round-trip. The {@code limit} / {@code offset} echo the params
 * so the client can verify the slice it requested.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionListResult(
        @JsonProperty("sessions") List<SessionSummary> sessions,
        @JsonProperty("total") int total,
        @JsonProperty("offset") int offset,
        @JsonProperty("limit") int limit
) {

    /** A single row in {@link SessionListResult#sessions()}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionSummary(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("cwd") String cwd,
            @JsonProperty("status") String status,
            @JsonProperty("parentSessionId") String parentSessionId,
            @JsonProperty("createdAtMs") long createdAtMs,
            @JsonProperty("lastActiveAtMs") Long lastActiveAtMs,
            @JsonProperty("trashedAtMs") Long trashedAtMs,
            @JsonProperty("tokenTotal") long tokenTotal,
            @JsonProperty("model") String model,
            @JsonProperty("promptPreview") String promptPreview
    ) {}
}
