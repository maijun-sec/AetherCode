package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): result for {@code session/show}.
 *
 * <p>Wire format:
 * <pre>
 *   { "session": { ...SessionSummary },
 *     "transcript": [ {role, content, tsMs, ...}, ... ],
 *     "config": { ...free-form JSON },
 *     "state": { ...free-form JSON } }
 * </pre>
 *
 * <p>The transcript is the rolling message log; each entry is a
 * free-form map so the daemon can attach tool-call cards, file
 * diffs, and TODO updates without a schema migration. The
 * {@code config} and {@code state} blobs are the same JSON the
 * supervisor stores on the child row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionShowResult(
        @JsonProperty("session") SessionListResult.SessionSummary session,
        @JsonProperty("transcript") List<Map<String, Object>> transcript,
        @JsonProperty("config") Map<String, Object> config,
        @JsonProperty("state") Map<String, Object> state,
        @JsonProperty("events") List<Map<String, Object>> events
) {}
