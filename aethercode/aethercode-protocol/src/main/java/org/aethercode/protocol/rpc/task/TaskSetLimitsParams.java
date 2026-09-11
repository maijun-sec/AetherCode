package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): params for {@code task/setLimits}
 * (refined — supports the new {@code idleMs} limit).
 *
 * <p>Wire format:
 * <pre>
 *   { id,
 *     limits: { wallClockMs?, tokens?, calls?,
 *               fileWrites?, network?, idleMs? },
 *     remove?: ["tokens", "idleMs", ...] }
 * </pre>
 *
 * <p>{@code limits} is a partial map; missing fields are left
 * at the current value. {@code remove} clears the named
 * fields entirely. The seven supported limits are:
 * {@code wallClockMs}, {@code tokens}, {@code calls},
 * {@code fileWrites}, {@code network}, {@code idleMs}, and
 * the alias {@code wallClock} (mapped to {@code wallClockMs}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskSetLimitsParams(
        @JsonProperty("id") String id,
        @JsonProperty("limits") Map<String, Object> limits,
        @JsonProperty("remove") List<String> remove
) {
    public TaskSetLimitsParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
