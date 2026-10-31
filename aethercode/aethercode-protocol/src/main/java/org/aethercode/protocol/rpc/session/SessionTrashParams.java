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
 * Phase 1.2 (T-1-13 / design.md §3.1): params for {@code session/trash}.
 *
 * <p>Wire format (the three modes share one method):
 * <pre>
 *   { empty: true }                    // empty the trash (irreversible)
 *   { restoreId: "..." }               // restore a single session
 *   { list: true, limit?, offset? }    // list trashed sessions
 * </pre>
 *
 * <p>At most one of {@code empty} / {@code restoreId} / {@code list}
 * may be set. With no flags, the call returns the count of
 * trashed sessions (so the trash tab can show a "12 items"
 * badge without a follow-up list).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionTrashParams(
        @JsonProperty("empty") Boolean empty,
        @JsonProperty("restoreId") String restoreId,
        @JsonProperty("list") Boolean list,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("offset") Integer offset
) {
    public boolean isEmpty() { return Boolean.TRUE.equals(empty); }
    public boolean isList() { return Boolean.TRUE.equals(list); }
    public boolean isRestore() { return restoreId != null && !restoreId.isBlank(); }
}
