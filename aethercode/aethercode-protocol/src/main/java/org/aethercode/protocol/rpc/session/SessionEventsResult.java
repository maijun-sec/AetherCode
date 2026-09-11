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
