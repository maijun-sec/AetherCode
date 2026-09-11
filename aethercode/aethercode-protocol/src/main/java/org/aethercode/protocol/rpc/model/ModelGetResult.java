package org.aethercode.protocol.rpc.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Phase 1.2 (T-1-19 / design.md §3.1): result for {@code model/get}.
 *
 * <p>Wire format is a single {@code ModelProfile} map. The
 * shape is the same as a row in {@link ModelListResult#models()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelGetResult(
        @JsonProperty("model") Map<String, Object> model
) {}
