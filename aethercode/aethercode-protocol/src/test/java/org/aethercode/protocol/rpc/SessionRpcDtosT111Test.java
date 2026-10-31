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
import org.aethercode.protocol.rpc.session.SessionListParams;
import org.aethercode.protocol.rpc.session.SessionListResult;
import org.aethercode.protocol.rpc.session.SessionRenameParams;
import org.aethercode.protocol.rpc.session.SessionShowParams;
import org.aethercode.protocol.rpc.session.SessionShowResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): roundtrip + null/error
 * cases for the three session/{list, show, rename} DTOs.
 */
class SessionRpcDtosT111Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sessionListParamsRoundtrip() throws Exception {
        SessionListParams p = new SessionListParams(
                "/tmp/proj", 1_700_000_000L, "needle", 50, 10, false,
                List.of("RUNNING", "PAUSED"));
        String json = MAPPER.writeValueAsString(p);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("/tmp/proj", n.get("cwd").asText());
        assertEquals(1_700_000_000L, n.get("since").asLong());
        assertEquals("needle", n.get("query").asText());
        assertEquals(50, n.get("limit").asInt());
        assertEquals(10, n.get("offset").asInt());
        assertEquals(false, n.get("trashed").asBoolean());
        assertEquals("RUNNING", n.get("statuses").get(0).asText());
    }

    @Test
    void sessionShowParamsRejectsBlankId() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionShowParams(""));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionShowParams(null));
    }

    @Test
    void sessionShowResultRoundtrips() throws Exception {
        SessionListResult.SessionSummary summary = new SessionListResult.SessionSummary(
                "s-1", "My session", "/tmp", "RUNNING", "p-1",
                1_700_000_000L, 1_700_000_500L, null, 1234L, "claude-sonnet-4-5",
                "explain the code");
        SessionShowResult r = new SessionShowResult(
                summary, List.of(Map.of("role", "user", "content", "hi")),
                Map.of("model", "claude-sonnet-4-5"), Map.of(), null);
        String json = MAPPER.writeValueAsString(r);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("s-1", n.get("session").get("id").asText());
        assertEquals("My session", n.get("session").get("title").asText());
        assertEquals("user", n.get("transcript").get(0).get("role").asText());
        assertEquals("claude-sonnet-4-5", n.get("config").get("model").asText());
    }

    @Test
    void sessionRenameParamsRejectsBlankIdAndNullTitle() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRenameParams("", "new title"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRenameParams(null, "new title"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRenameParams("s-1", null));
    }
}
