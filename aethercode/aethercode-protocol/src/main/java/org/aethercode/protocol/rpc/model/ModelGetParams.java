package org.aethercode.protocol.rpc.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-19 / design.md §3.1): params for {@code model/get}.
 *
 * <p>Wire format:
 * <pre>
 *   { name }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelGetParams(
        @JsonProperty("name") String name
) {
    public ModelGetParams {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
