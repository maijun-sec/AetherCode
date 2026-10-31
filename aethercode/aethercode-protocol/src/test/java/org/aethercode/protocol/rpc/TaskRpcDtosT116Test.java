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
import org.aethercode.protocol.rpc.task.TaskAttachParams;
import org.aethercode.protocol.rpc.task.TaskEventsParams;
import org.aethercode.protocol.rpc.task.TaskListParams;
import org.aethercode.protocol.rpc.task.TaskSetLimitsParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): the refined
 * task/{attach, events, list, setLimits} DTOs.
 */
class TaskRpcDtosT116Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void taskAttachParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAttachParams("", 0L, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAttachParams(null, 0L, 100));
    }

    @Test
    void taskEventsParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskEventsParams("", null, null));
    }

    @Test
    void taskListParamsEffectiveStates() {
        TaskListParams none = new TaskListParams(null, null, null, null);
        assertNull(none.effectiveStates());
        TaskListParams single = new TaskListParams("RUNNING", null, null, null);
        assertEquals(List.of("RUNNING"), single.effectiveStates());
        TaskListParams multi = new TaskListParams(null,
                List.of("RUNNING", "PAUSED"), null, null);
        assertEquals(List.of("RUNNING", "PAUSED"), multi.effectiveStates());
    }

    @Test
    void taskSetLimitsParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskSetLimitsParams("", Map.of("tokens", 1000), null));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskSetLimitsParams(null, Map.of(), null));
    }

    @Test
    void taskSetLimitsParamsRoundtripWithNewIdleLimit() throws Exception {
        TaskSetLimitsParams p = new TaskSetLimitsParams(
                "t-1",
                Map.of("wallClockMs", 24 * 3600_000L, "tokens", 32_000_000L,
                        "idleMs", 30 * 60_000L),
                List.of("network"));
        String json = MAPPER.writeValueAsString(p);
        JsonNode n = MAPPER.readTree(json);
        assertEquals(24 * 3600_000L, n.get("limits").get("wallClockMs").asLong());
        assertEquals(32_000_000L, n.get("limits").get("tokens").asLong());
        assertEquals(30 * 60_000L, n.get("limits").get("idleMs").asLong());
        assertTrue(n.get("remove").isArray());
        assertEquals("network", n.get("remove").get(0).asText());
    }

    @Test
    void taskAttachParamsRoundtrip() throws Exception {
        TaskAttachParams p = new TaskAttachParams("t-1", 5L, 200);
        String json = MAPPER.writeValueAsString(p);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("t-1", n.get("id").asText());
        assertEquals(5L, n.get("sinceSeq").asLong());
        assertEquals(200, n.get("limit").asInt());
    }
}
