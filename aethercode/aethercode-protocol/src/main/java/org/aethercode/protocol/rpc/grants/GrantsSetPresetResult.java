package org.aethercode.protocol.rpc.grants;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): result for {@code grants/setPreset}.
 *
 * <p>Wire format:
 * <pre>
 *   { "ok": true, "preset": "cautious" }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrantsSetPresetResult(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("preset") String preset
) {}
