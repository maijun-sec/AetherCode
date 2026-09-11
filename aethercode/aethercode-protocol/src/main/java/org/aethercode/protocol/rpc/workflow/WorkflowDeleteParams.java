package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): params for {@code workflow/delete}.
 *
 * <p>Wire format:
 * <pre>
 *   { name, scope?: "user"|"project", cwd? }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDeleteParams(
        @JsonProperty("name") String name,
        @JsonProperty("scope") String scope,
        @JsonProperty("cwd") String cwd
) {
    public WorkflowDeleteParams {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
