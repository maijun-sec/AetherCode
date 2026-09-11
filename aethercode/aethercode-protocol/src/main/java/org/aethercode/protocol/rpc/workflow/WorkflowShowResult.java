package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): result for {@code workflow/show}.
 *
 * <p>Wire format:
 * <pre>
 *   { "workflow": { ...fields }, "yaml": "..." }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowShowResult(
        @JsonProperty("workflow") Map<String, Object> workflow,
        @JsonProperty("yaml") String yaml
) {}
