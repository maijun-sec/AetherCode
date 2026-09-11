package org.aethercode.protocol.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.protocol.rpc.workflow.WorkflowDeleteParams;
import org.aethercode.protocol.rpc.workflow.WorkflowRunParams;
import org.aethercode.protocol.rpc.workflow.WorkflowUpsertParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): the workflow/* DTO
 * stubs. The full impl ships in Phase 2; this test file
 * pins the wire shape.
 */
class WorkflowRpcDtosT120Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void workflowRunParamsRequiresName() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowRunParams("", Map.of(), "/tmp"));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowRunParams(null, Map.of(), "/tmp"));
    }

    @Test
    void workflowUpsertParamsRequiresNameAndYaml() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowUpsertParams("", "version: 1", "user", "/tmp"));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowUpsertParams("x", "", "user", "/tmp"));
        // cwd is optional — no throw.
        new WorkflowUpsertParams("x", "version: 1", "user", null);
    }

    @Test
    void workflowDeleteParamsRoundtrip() throws Exception {
        WorkflowDeleteParams p = new WorkflowDeleteParams("tdd-feature", "project", "/tmp");
        String json = MAPPER.writeValueAsString(p);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("tdd-feature", n.get("name").asText());
        assertEquals("project", n.get("scope").asText());
        assertEquals("/tmp", n.get("cwd").asText());
    }
}
