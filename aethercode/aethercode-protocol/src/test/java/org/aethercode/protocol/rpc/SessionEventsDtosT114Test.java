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

package org.aethercode.protocol.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.protocol.rpc.session.SessionEventsParams;
import org.aethercode.protocol.rpc.session.SessionEventsResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1.2 (T-1-14 / design.md §3.1): the session/events
 * JSONL stream DTOs. The wire shape is one JSON object per
 * line; the {@link SessionEventsResult} is the per-line
 * envelope with a {@code kind} discriminator.
 */
class SessionEventsDtosT114Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sessionEventsParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionEventsParams("", 0L, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionEventsParams(null, 0L, 1000));
    }

    @Test
    void sessionEventsParamsRoundtrip() throws Exception {
        SessionEventsParams p = new SessionEventsParams("s-1", 42L, 500);
        String json = MAPPER.writeValueAsString(p);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("s-1", n.get("id").asText());
        assertEquals(42L, n.get("sinceSeq").asLong());
        assertEquals(500, n.get("limit").asInt());
    }

    @Test
    void sessionEventsResultHelloFactory() throws Exception {
        SessionEventsResult hello = SessionEventsResult.hello("s-1", 0L, 100L, 1000);
        String json = MAPPER.writeValueAsString(hello);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("hello", n.get("kind").asText());
        assertEquals("s-1", n.get("sessionId").asText());
        assertEquals(0L, n.get("sinceSeq").asLong());
        assertEquals(100L, n.get("lastSeq").asLong());
    }

    @Test
    void sessionEventsResultEventFactory() throws Exception {
        SessionEventsResult ev = SessionEventsResult.event(
                7L, 1_700_000_000L, "status_change", Map.of("to", "RUNNING"));
        String json = MAPPER.writeValueAsString(ev);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("event", n.get("kind").asText());
        assertEquals(7L, n.get("seq").asLong());
        assertEquals("status_change", n.get("type").asText());
        assertEquals("RUNNING", n.get("payload").get("to").asText());
    }

    @Test
    void sessionEventsResultEndFactory() throws Exception {
        SessionEventsResult end = SessionEventsResult.end("s-1", 200L, true);
        String json = MAPPER.writeValueAsString(end);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("end", n.get("kind").asText());
        assertEquals(200L, n.get("lastSeq").asLong());
        assertEquals(true, n.get("terminal").asBoolean());
    }
}
