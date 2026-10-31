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

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): result for {@code session/list}.
 *
 * <p>Wire format:
 * <pre>
 *   { "sessions": [ {id, title, cwd, lastActiveAtMs, tokenTotal,
 *                     parentSessionId, status, trashedAtMs?,
 *                     promptPreview}, ... ],
 *     "total": N,
 *     "offset": O,
 *     "limit": L }
 * </pre>
 *
 * <p>The {@code total} field is the unpaginated count, so a
 * renderer can show "showing 1-50 of 273" without a second
 * round-trip. The {@code limit} / {@code offset} echo the params
 * so the client can verify the slice it requested.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionListResult(
        @JsonProperty("sessions") List<SessionSummary> sessions,
        @JsonProperty("total") int total,
        @JsonProperty("offset") int offset,
        @JsonProperty("limit") int limit
) {

    /** A single row in {@link SessionListResult#sessions()}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionSummary(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("cwd") String cwd,
            @JsonProperty("status") String status,
            @JsonProperty("parentSessionId") String parentSessionId,
            @JsonProperty("createdAtMs") long createdAtMs,
            @JsonProperty("lastActiveAtMs") Long lastActiveAtMs,
            @JsonProperty("trashedAtMs") Long trashedAtMs,
            @JsonProperty("tokenTotal") long tokenTotal,
            @JsonProperty("model") String model,
            @JsonProperty("promptPreview") String promptPreview
    ) {}
}
