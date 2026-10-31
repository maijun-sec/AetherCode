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
 * Phase 1.2 (T-1-13 / design.md §3.1): result for {@code session/trash}.
 *
 * <p>Wire format depends on the mode:
 * <pre>
 *   { "trashed": [ SessionSummary, ... ], "total": N, "offset": O, "limit": L }   // list
 *   { "removed": N }                                                              // empty
 *   { "restoredId": "..." }                                                       // restore
 *   { "count": N }                                                                // bare count
 * </pre>
 *
 * <p>Only the fields relevant to the invoked mode are set; the
 * others are omitted from the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionTrashResult(
        @JsonProperty("trashed") List<SessionListResult.SessionSummary> trashed,
        @JsonProperty("total") Integer total,
        @JsonProperty("offset") Integer offset,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("removed") Integer removed,
        @JsonProperty("restoredId") String restoredId,
        @JsonProperty("count") Integer count
) {
    public static SessionTrashResult list(List<SessionListResult.SessionSummary> rows,
                                          int total, int offset, int limit) {
        return new SessionTrashResult(rows, total, offset, limit, null, null, null);
    }
    public static SessionTrashResult empty(int removed) {
        return new SessionTrashResult(null, null, null, null, removed, null, null);
    }
    public static SessionTrashResult restored(String id) {
        return new SessionTrashResult(null, null, null, null, null, id, null);
    }
    public static SessionTrashResult count(int n) {
        return new SessionTrashResult(null, null, null, null, null, null, n);
    }
}
