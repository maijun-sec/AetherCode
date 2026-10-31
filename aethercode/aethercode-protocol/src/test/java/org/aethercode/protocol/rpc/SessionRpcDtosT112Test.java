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
import org.aethercode.protocol.rpc.session.SessionDeleteParams;
import org.aethercode.protocol.rpc.session.SessionDeleteResult;
import org.aethercode.protocol.rpc.session.SessionRestoreParams;
import org.aethercode.protocol.rpc.session.SessionResumeParams;
import org.aethercode.protocol.rpc.session.SessionSpawnParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): roundtrip + null/error
 * cases for session/{spawn, resume, delete, restore} DTOs.
 */
class SessionRpcDtosT112Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sessionSpawnParamsRejectsMissingFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSpawnParams(null, "/tmp", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSpawnParams("p", "", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSpawnParams("", "/tmp", null, null, null, null, null));
    }

    @Test
    void sessionSpawnParamsRoundtripWithOptionals() throws Exception {
        SessionSpawnParams p = new SessionSpawnParams(
                "explain this", "/tmp/proj",
                "claude-sonnet-4-5", "tdd-feature", "p-1", "TDD for login",
                Map.of("feature", "login"));
        String json = MAPPER.writeValueAsString(p);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("explain this", n.get("prompt").asText());
        assertEquals("/tmp/proj", n.get("cwd").asText());
        assertEquals("claude-sonnet-4-5", n.get("model").asText());
        assertEquals("tdd-feature", n.get("workflow").asText());
        assertEquals("p-1", n.get("parentId").asText());
        assertEquals("TDD for login", n.get("title").asText());
        assertEquals("login", n.get("config").get("feature").asText());
    }

    @Test
    void sessionResumeParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionResumeParams("", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionResumeParams(null, "go"));
    }

    @Test
    void sessionDeleteParamsHandlesHardFlag() throws Exception {
        SessionDeleteParams soft = new SessionDeleteParams("s-1", null);
        SessionDeleteParams hard = new SessionDeleteParams("s-1", Boolean.TRUE);
        String softJson = MAPPER.writeValueAsString(soft);
        String hardJson = MAPPER.writeValueAsString(hard);
        assertTrue(!softJson.contains("\"hard\""),
                "soft delete omits the 'hard' field");
        assertEquals(Boolean.TRUE, MAPPER.readTree(hardJson).get("hard").asBoolean());
        SessionDeleteResult res = new SessionDeleteResult("s-1", true, false);
        assertEquals(true, res.trashed());
        assertEquals(false, res.hard());
    }

    @Test
    void sessionRestoreParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRestoreParams(""));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRestoreParams(null));
    }

    @Test
    void sessionRestoreResultCarriesTrashedFalse() {
        var r = new org.aethercode.protocol.rpc.session.SessionRestoreResult("s-1", false);
        assertEquals("s-1", r.id());
        assertEquals(false, r.trashed());
    }
}
