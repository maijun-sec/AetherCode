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

package org.aethercode.protocol.rpc.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): result for {@code session/show}.
 *
 * <p>Wire format:
 * <pre>
 *   { "session": { ...SessionSummary },
 *     "transcript": [ {role, content, tsMs, ...}, ... ],
 *     "config": { ...free-form JSON },
 *     "state": { ...free-form JSON } }
 * </pre>
 *
 * <p>The transcript is the rolling message log; each entry is a
 * free-form map so the daemon can attach tool-call cards, file
 * diffs, and TODO updates without a schema migration. The
 * {@code config} and {@code state} blobs are the same JSON the
 * supervisor stores on the child row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionShowResult(
        @JsonProperty("session") SessionListResult.SessionSummary session,
        @JsonProperty("transcript") List<Map<String, Object>> transcript,
        @JsonProperty("config") Map<String, Object> config,
        @JsonProperty("state") Map<String, Object> state,
        @JsonProperty("events") List<Map<String, Object>> events
) {}
