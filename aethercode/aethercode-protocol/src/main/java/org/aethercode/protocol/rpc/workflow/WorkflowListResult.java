package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): result for {@code workflow/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { "workflows": [ {name, description, version, scope,
 *                      path, inputs, skills, prompts}, ... ] }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowListResult(
        @JsonProperty("workflows") List<Map<String, Object>> workflows
) {}
