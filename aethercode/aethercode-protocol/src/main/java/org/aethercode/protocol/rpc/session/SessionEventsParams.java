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

/**
 * Phase 1.2 (T-1-14 / design.md §3.1): params for {@code session/events}.
 *
 * <p>Wire format:
 * <pre>
 *   { id, sinceSeq?, limit? }
 * </pre>
 *
 * <p>The response is a stream of JSONL records (one JSON object
 * per line) over the supervisor socket. The first line is a
 * {@code hello} envelope that echoes the params + the most
 * recent sequence id; subsequent lines are the buffered events
 * since the cursor.
 *
 * <p>{@code sinceSeq} is the last sequence number the client
 * already saw; the server starts at {@code sinceSeq + 1}.
 * {@code limit} caps the buffer replay (default 1000).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEventsParams(
        @JsonProperty("id") String id,
        @JsonProperty("sinceSeq") Long sinceSeq,
        @JsonProperty("limit") Integer limit
) {
    public SessionEventsParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
