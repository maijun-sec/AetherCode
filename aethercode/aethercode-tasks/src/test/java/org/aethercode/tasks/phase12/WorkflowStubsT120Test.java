package org.aethercode.tasks.phase12;

import org.aethercode.tasks.supervisor.SupervisorRpcServer;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-20 / design.md §3.1): 3 handler tests for
 * the workflow/* stub methods. The real impl ships in
 * Phase 2 (Java-A); for now the dispatcher returns the
 * structured NOT_IMPLEMENTED envelope.
 */
class WorkflowStubsT120Test {

    private SupervisorStore store;
    private SupervisorService service;
    private SupervisorRpcServer rpc;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        service = new SupervisorService(store);
        rpc = new SupervisorRpcServer(service);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void workflowListReturnsNotImplemented() throws Exception {
        Object r = invoke("workflow/list", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) r;
        assertEquals(false, envelope.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) envelope.get("error");
        assertEquals("NOT_IMPLEMENTED", err.get("name"));
    }

    @Test
    void workflowRunReturnsNotImplemented() throws Exception {
        Object r = invoke("workflow/run",
                Map.of("name", "tdd-feature",
                        "inputs", Map.of("feature", "login")));
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) r;
        assertEquals(false, envelope.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) envelope.get("error");
        assertEquals("NOT_IMPLEMENTED", err.get("name"));
    }

    @Test
    void workflowMethodsAreWiredOnDispatcher() throws Exception {
        // Each of the five workflow/* methods should be
        // resolvable on the dispatcher (returns the
        // NOT_IMPLEMENTED envelope). We probe by
        // dispatching each name and confirming the
        // response is the same structured failure.
        for (String method : new String[]{
                "workflow/list", "workflow/show", "workflow/run",
                "workflow/upsert", "workflow/delete"}) {
            Object r = invoke(method, Map.of("name", "x"));
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) r;
            @SuppressWarnings("unchecked")
            Map<String, Object> err = (Map<String, Object>) envelope.get("error");
            assertEquals("NOT_IMPLEMENTED", err.get("name"),
                    "method " + method + " should return NOT_IMPLEMENTED");
        }
    }

    private Object invoke(String method, Object params) {
        // Round-trip through the wire codec so the
        // envelope travels the same path a real client
        // would use.
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method
                + "\",\"params\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .valueToTree(params).toString() + "}";
        String reply = rpc.handleLine(line);
        assertNotNull(reply);
        try {
            Object decoded = org.aethercode.tasks.supervisor.JsonRpcEnvelope.decode(
                    rpc.mapper(), reply);
            return ((org.aethercode.tasks.supervisor.JsonRpcEnvelope.Response) decoded)
                    .result();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
