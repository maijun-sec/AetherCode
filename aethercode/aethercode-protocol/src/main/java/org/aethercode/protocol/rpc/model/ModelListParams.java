package org.aethercode.protocol.rpc.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-19 / design.md §3.1): params for {@code model/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { provider? }
 * </pre>
 *
 * <p>When {@code provider} is present the result is restricted
 * to that provider (e.g. {@code "anthropic"},
 * {@code "openai"}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelListParams(
        @JsonProperty("provider") String provider
) {}
