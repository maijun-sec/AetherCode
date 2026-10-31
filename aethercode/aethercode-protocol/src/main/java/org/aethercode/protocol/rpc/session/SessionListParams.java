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
 * Phase 1.2 (T-1-11 / design.md §3.1): params for {@code session/list}.
 *
 * <p>Wire format (all fields optional; default behaviour with no
 * args is "list every active session"):
 * <pre>
 *   { cwd?, since?, query?, limit?, offset?, trashed? }
 * </pre>
 *
 * <ul>
 *   <li>{@code cwd} — restrict to a single working directory.</li>
 *   <li>{@code since} — millisecond epoch; only sessions with
 *       {@code lastActiveAt >= since} are returned.</li>
 *   <li>{@code query} — case-insensitive substring matched against
 *       the title and the first user prompt preview.</li>
 *   <li>{@code limit} / {@code offset} — page through large lists;
 *       default 100, max 500.</li>
 *   <li>{@code trashed} — when {@code true}, list sessions in the
 *       trash (30-day retention); when {@code false} or absent,
 *       list active sessions.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionListParams(
        @JsonProperty("cwd") String cwd,
        @JsonProperty("since") Long since,
        @JsonProperty("query") String query,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("offset") Integer offset,
        @JsonProperty("trashed") Boolean trashed,
        @JsonProperty("statuses") List<String> statuses
) {
    /** Empty params — list every active session. */
    public static SessionListParams empty() {
        return new SessionListParams(null, null, null, null, null, null, null);
    }
}
