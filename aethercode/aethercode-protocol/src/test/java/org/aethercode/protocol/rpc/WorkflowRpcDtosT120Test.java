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
