package org.aethercode.protocol.rpc.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): params for {@code task/spawn}
 * (refined v1 — same shape, plus optional model + limits).
 *
 * <p>Wire format:
 * <pre>
 *   { prompt, cwd, model?, parentId?,
 *     limits?: {wallClockMs?, tokens?, calls?, fileWrites?, network?, idleMs?} }
 * </pre>
 *
 * <p>The fields are identical to the v1 wire shape plus
 * {@code model} and {@code limits}; existing v1 callers keep
 * working (the additional fields default to null and the
 * server uses the existing defaults when absent).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskSpawnParams(
        @JsonProperty("prompt") String prompt,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("model") String model,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("limits") Map<String, Object> limits,
        @JsonProperty("config") Map<String, Object> config
) {
    public TaskSpawnParams {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (cwd == null || cwd.isBlank()) {
            throw new IllegalArgumentException("cwd is required");
        }
    }
}
