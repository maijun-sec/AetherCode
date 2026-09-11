package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): result for {@code task/spawn}
 * (refined).
 *
 * <p>Wire format:
 * <pre>
 *   { "taskId": "...", "status": "QUEUED", "parentId": "..."? }
 * </pre>
 *
 * <p>The {@code taskId} is the same id the supervisor's
 * existing {@code task/spawn} returns; v1 callers keep working.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskSpawnResult(
        @JsonProperty("taskId") String taskId,
        @JsonProperty("status") String status,
        @JsonProperty("parentId") String parentId
) {}
