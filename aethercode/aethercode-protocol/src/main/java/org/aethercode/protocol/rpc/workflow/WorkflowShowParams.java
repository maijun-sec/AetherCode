package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): params for {@code workflow/show}.
 *
 * <p>Wire format:
 * <pre>
 *   { name, cwd? }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowShowParams(
        @JsonProperty("name") String name,
        @JsonProperty("cwd") String cwd
) {
    public WorkflowShowParams {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
