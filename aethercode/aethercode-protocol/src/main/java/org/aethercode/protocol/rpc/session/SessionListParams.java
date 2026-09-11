package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): params for {@code session/list}.
 *
 * <p>Wire format (all fields optional; default behaviour with no
 * args is "list every active session"):
 * <pre>
 *   { cwd?, since?, query?, limit?, offset?, trashed? }
 * </pre>
 *
 * <ul>
 *   <li>{@code cwd} — restrict to a single working directory.</li>
 *   <li>{@code since} — millisecond epoch; only sessions with
 *       {@code lastActiveAt >= since} are returned.</li>
 *   <li>{@code query} — case-insensitive substring matched against
 *       the title and the first user prompt preview.</li>
 *   <li>{@code limit} / {@code offset} — page through large lists;
 *       default 100, max 500.</li>
 *   <li>{@code trashed} — when {@code true}, list sessions in the
 *       trash (30-day retention); when {@code false} or absent,
 *       list active sessions.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionListParams(
        @JsonProperty("cwd") String cwd,
        @JsonProperty("since") Long since,
        @JsonProperty("query") String query,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("offset") Integer offset,
        @JsonProperty("trashed") Boolean trashed,
        @JsonProperty("statuses") List<String> statuses
) {
    /** Empty params — list every active session. */
    public static SessionListParams empty() {
        return new SessionListParams(null, null, null, null, null, null, null);
    }
}
