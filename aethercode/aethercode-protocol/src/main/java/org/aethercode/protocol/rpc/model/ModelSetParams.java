package org.aethercode.protocol.rpc.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-19 / design.md §3.1): params for {@code model/set}.
 *
 * <p>Wire format:
 * <pre>
 *   { sessionId, name }
 * </pre>
 *
 * <p>Mid-session switch: the supervisor writes the new model to
 * the child's {@code config.model} field. The next LLM call
 * picks it up; in-flight calls complete with the old model.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelSetParams(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("name") String name
) {
    public ModelSetParams {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
