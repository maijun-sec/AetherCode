package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): params for {@code workflow/upsert}.
 *
 * <p>Wire format:
 * <pre>
 *   { name, yaml, scope?: "user"|"project", cwd? }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowUpsertParams(
        @JsonProperty("name") String name,
        @JsonProperty("yaml") String yaml,
        @JsonProperty("scope") String scope,
        @JsonProperty("cwd") String cwd
) {
    public WorkflowUpsertParams {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("yaml is required");
        }
    }
}
