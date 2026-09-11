package org.aethercode.protocol.rpc.grants;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): params for {@code grants/setPreset}.
 *
 * <p>Wire format:
 * <pre>
 *   { preset: "permissive"|"cautious"|"strict" }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrantsSetPresetParams(
        @JsonProperty("preset") String preset
) {
    public static final String PRESET_PERMISSIVE = "permissive";
    public static final String PRESET_CAUTIOUS   = "cautious";
    public static final String PRESET_STRICT     = "strict";

    public GrantsSetPresetParams {
        if (preset == null || preset.isBlank()) {
            throw new IllegalArgumentException("preset is required");
        }
        if (!PRESET_PERMISSIVE.equals(preset)
                && !PRESET_CAUTIOUS.equals(preset)
                && !PRESET_STRICT.equals(preset)) {
            throw new IllegalArgumentException("unknown preset: " + preset);
        }
    }
}
