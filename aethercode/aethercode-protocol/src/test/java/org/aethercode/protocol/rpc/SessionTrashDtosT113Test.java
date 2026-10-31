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
import org.aethercode.protocol.rpc.session.SessionTrashParams;
import org.aethercode.protocol.rpc.session.SessionTrashResult;
import org.aethercode.protocol.rpc.session.SessionListResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-13 / design.md §3.1): roundtrip + null/error
 * cases for session/trash DTOs. The trash method is tri-state
 * (empty / restore / list / count) and the params record
 * exposes mode helpers.
 */
class SessionTrashDtosT113Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sessionTrashParamsModes() {
        SessionTrashParams empty = new SessionTrashParams(true, null, null, null, null);
        SessionTrashParams restore = new SessionTrashParams(null, "s-1", null, null, null);
        SessionTrashParams list = new SessionTrashParams(null, null, true, 50, 0);
        SessionTrashParams count = new SessionTrashParams(null, null, null, null, null);

        assertTrue(empty.isEmpty());
        assertFalse(empty.isRestore());
        assertTrue(restore.isRestore());
        assertTrue(list.isList());
        assertFalse(count.isEmpty() || count.isList() || count.isRestore());
    }

    @Test
    void sessionTrashResultFactories() throws Exception {
        SessionTrashResult listRes = SessionTrashResult.list(
                List.of(new SessionListResult.SessionSummary(
                        "s-1", "old", "/tmp", "COMPLETED", null,
                        1_700_000_000L, 1_700_000_500L, 1_700_001_000L,
                        100L, null, "old prompt")),
                1, 0, 50);
        String json = MAPPER.writeValueAsString(listRes);
        JsonNode n = MAPPER.readTree(json);
        assertEquals(1, n.get("total").asInt());
        assertEquals(0, n.get("offset").asInt());
        assertEquals(50, n.get("limit").asInt());
        assertEquals("s-1", n.get("trashed").get(0).get("id").asText());

        SessionTrashResult emptyRes = SessionTrashResult.empty(5);
        assertEquals(5, emptyRes.removed());
        assertNull(emptyRes.trashed());

        SessionTrashResult restored = SessionTrashResult.restored("s-1");
        assertEquals("s-1", restored.restoredId());

        SessionTrashResult count = SessionTrashResult.count(12);
        assertEquals(12, count.count());
    }

    @Test
    void sessionTrashParamsRoundtrip() throws Exception {
        SessionTrashParams p = new SessionTrashParams(false, "s-1", false, 100, 5);
        String json = MAPPER.writeValueAsString(p);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("s-1", n.get("restoreId").asText());
        assertEquals(100, n.get("limit").asInt());
        assertEquals(5, n.get("offset").asInt());
    }
}
