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
import org.aethercode.protocol.rpc.task.TaskKillParams;
import org.aethercode.protocol.rpc.task.TaskKillResult;
import org.aethercode.protocol.rpc.task.TaskPauseParams;
import org.aethercode.protocol.rpc.task.TaskResumeParams;
import org.aethercode.protocol.rpc.task.TaskSpawnParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): the refined
 * task/{spawn, resume, pause, kill} DTOs. Same shape as v1
 * plus optional model + limits.
 */
class TaskRpcDtosT115Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void taskSpawnParamsRejectsMissingFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskSpawnParams(null, "/tmp", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskSpawnParams("p", "", null, null, null, null));
    }

    @Test
    void taskResumeParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskResumeParams(""));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskResumeParams(null));
    }

    @Test
    void taskPauseParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskPauseParams("", "limit hit"));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskPauseParams(null, "limit hit"));
    }

    @Test
    void taskKillParamsAndResultRoundtrip() throws Exception {
        TaskKillParams p = new TaskKillParams("t-1", "user aborted");
        String json = MAPPER.writeValueAsString(p);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("t-1", n.get("id").asText());
        assertEquals("user aborted", n.get("reason").asText());

        TaskKillResult r = new TaskKillResult("t-1", "KILLED", true, false);
        String rj = MAPPER.writeValueAsString(r);
        assertTrue(rj.contains("\"noop\":false"));
    }
}
