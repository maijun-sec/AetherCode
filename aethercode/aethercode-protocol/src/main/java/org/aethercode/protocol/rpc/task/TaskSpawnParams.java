/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
