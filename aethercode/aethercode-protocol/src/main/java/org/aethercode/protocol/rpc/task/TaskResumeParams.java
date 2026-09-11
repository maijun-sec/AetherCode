package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): params for {@code task/resume}
 * (refined — accepts both {@code id} and {@code childId} on the
 * wire for v1 backward compat).
 *
 * <p>Wire format:
 * <pre>
 *   { id }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskResumeParams(
        @JsonProperty("id") String id
) {
    public TaskResumeParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
