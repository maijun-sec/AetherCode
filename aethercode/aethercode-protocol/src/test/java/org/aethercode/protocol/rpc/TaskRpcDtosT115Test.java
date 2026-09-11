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
