package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): result for {@code task/resume}.
 *
 * <p>Wire format:
 * <pre>
 *   { "id": "...", "status": "RUNNING", "ok": true }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskResumeResult(
        @JsonProperty("id") String id,
        @JsonProperty("status") String status,
        @JsonProperty("ok") boolean ok
) {}
