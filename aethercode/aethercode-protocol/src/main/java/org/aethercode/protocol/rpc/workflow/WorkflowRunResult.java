package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): result for {@code workflow/run}.
 *
 * <p>Wire format:
 * <pre>
 *   { "sessionId": "...", "workflowName": "...",
 *     "ok": true }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunResult(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("workflowName") String workflowName,
        @JsonProperty("ok") boolean ok
) {}
