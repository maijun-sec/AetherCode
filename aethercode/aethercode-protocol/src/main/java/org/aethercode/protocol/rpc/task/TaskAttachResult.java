package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): result for {@code task/attach}.
 *
 * <p>Wire format:
 * <pre>
 *   { "task": { id, status, prompt, cwd, ... },
 *     "events": [ {seq, ts, type, payload}, ... ],
 *     "lastSeq": N }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskAttachResult(
        @JsonProperty("task") Map<String, Object> task,
        @JsonProperty("events") List<Map<String, Object>> events,
        @JsonProperty("lastSeq") long lastSeq
) {}
