package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): params for {@code session/resume}.
 *
 * <p>Wire format:
 * <pre>
 *   { id, prompt? }
 * </pre>
 *
 * <p>{@code id} is required. The optional {@code prompt} is the
 * first user message to send to the LLM after the resume (the
 * "Continue" affordance in the session panel sends a
 * pre-canned "continue" prompt when the user types nothing).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResumeParams(
        @JsonProperty("id") String id,
        @JsonProperty("prompt") String prompt
) {
    public SessionResumeParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
