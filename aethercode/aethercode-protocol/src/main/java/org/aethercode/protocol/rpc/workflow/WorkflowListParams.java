package org.aethercode.protocol.rpc.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): params for {@code workflow/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { cwd? }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowListParams(
        @JsonProperty("cwd") String cwd
) {}
