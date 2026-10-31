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
