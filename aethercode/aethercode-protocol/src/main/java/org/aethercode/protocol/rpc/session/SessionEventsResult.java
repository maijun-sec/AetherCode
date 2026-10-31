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
 * Phase 1.2 (T-1-14 / design.md §3.1): a single line of the
 * {@code session/events} JSONL stream.
 *
 * <p>Wire format (one per line):
 * <pre>
 *   { "kind": "hello", "sessionId": "...", "sinceSeq": N,
 *     "lastSeq": M, "limit": L }
 *   { "kind": "event", "seq": N, "ts": M, "type": "...",
 *     "payload": { ... } }
 *   { "kind": "end", "sessionId": "...", "lastSeq": M,
 *     "terminal": true }
 * </pre>
 *
 * <p>The {@code kind} discriminator lets the client route each
 * line without re-parsing the payload. A renderer that
 * subscribes to the stream and then disconnects only needs to
 * remember the {@code lastSeq} from the {@code hello} / last
 * {@code event} to resume from there.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEventsResult(
        @JsonProperty("kind") String kind,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("sinceSeq") Long sinceSeq,
        @JsonProperty("lastSeq") Long lastSeq,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("seq") Long seq,
        @JsonProperty("ts") Long ts,
        @JsonProperty("type") String type,
        @JsonProperty("payload") Object payload,
        @JsonProperty("terminal") Boolean terminal
) {
    public static final String KIND_HELLO = "hello";
    public static final String KIND_EVENT = "event";
    public static final String KIND_END   = "end";

    public static SessionEventsResult hello(String sessionId, long sinceSeq, long lastSeq, int limit) {
        return new SessionEventsResult(KIND_HELLO, sessionId, sinceSeq, lastSeq, limit,
                null, null, null, null, null);
    }
    public static SessionEventsResult event(long seq, long ts, String type, Object payload) {
        return new SessionEventsResult(KIND_EVENT, null, null, null, null,
                seq, ts, type, payload, null);
    }
    public static SessionEventsResult end(String sessionId, long lastSeq, boolean terminal) {
        return new SessionEventsResult(KIND_END, sessionId, null, lastSeq, null,
                null, null, null, null, terminal);
    }
}
