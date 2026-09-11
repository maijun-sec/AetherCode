package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): params for {@code workflow/run}.
 *
 * <p>Wire format:
 * <pre>
 *   { name, inputs?, cwd? }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunParams(
        @JsonProperty("name") String name,
        @JsonProperty("inputs") Map<String, Object> inputs,
        @JsonProperty("cwd") String cwd
) {
    public WorkflowRunParams {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
