package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): result for {@code task/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { "tasks": [ {id, status, prompt, cwd, ...}, ... ],
 *     "total": N, "offset": O, "limit": L }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskListResult(
        @JsonProperty("tasks") List<Map<String, Object>> tasks,
        @JsonProperty("total") int total,
        @JsonProperty("offset") int offset,
        @JsonProperty("limit") int limit
) {}
