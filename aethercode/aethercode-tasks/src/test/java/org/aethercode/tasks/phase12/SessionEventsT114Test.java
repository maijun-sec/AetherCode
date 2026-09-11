package org.aethercode.tasks.phase12;

import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1.2 (T-1-14 / design.md §3.1): 5 handler tests for
 * session/events. The method returns a JSONL-shaped snapshot
 * (a list of envelopes, one of which is the {@code hello},
 * the rest are {@code event}, the last is {@code end}).
 */
class SessionEventsT114Test {

    private SupervisorStore store;
    private SupervisorService service;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        service = new SupervisorService(store);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void sessionEventsRejectsMissingId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.sessionEvents(Map.of()));
    }

    @Test
    void sessionEventsReturnsHelloAndEndEnvelopes() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        store.appendEvent(id, "model_message", "{\"role\":\"assistant\"}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> r = (List<Map<String, Object>>) service.sessionEvents(
                Map.of("id", id));
        // hello + 2 events (status_change from spawn +
        // model_message) + end
        assertEquals(4, r.size());
        assertEquals("hello", r.get(0).get("kind"));
        assertEquals("event", r.get(1).get("kind"));
        assertEquals("status_change", r.get(1).get("type"));
        assertEquals("event", r.get(2).get("kind"));
        assertEquals("model_message", r.get(2).get("type"));
        assertEquals("end", r.get(3).get("kind"));
    }

    @Test
    void sessionEventsSinceCursorFiltersBuffer() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        long first = store.appendEvent(id, "first", "{}");
        long second = store.appendEvent(id, "second", "{}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> r = (List<Map<String, Object>>) service.sessionEvents(
                Map.of("id", id, "sinceSeq", first));
        // hello + 1 event (second) + end
        assertEquals(3, r.size());
        assertEquals("hello", r.get(0).get("kind"));
        assertEquals("event", r.get(1).get("kind"));
        assertEquals("second", r.get(1).get("type"));
    }

    @Test
    void sessionEventsEnvelopesCarrySinceAndLastSeq() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        store.appendEvent(id, "e1", "{}");
        store.appendEvent(id, "e2", "{}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> r = (List<Map<String, Object>>) service.sessionEvents(
                Map.of("id", id, "sinceSeq", 0L));
        @SuppressWarnings("unchecked")
        Map<String, Object> hello = (Map<String, Object>) r.get(0);
        assertEquals(0L, ((Number) hello.get("sinceSeq")).longValue());
        assertNotNull(hello.get("lastSeq"));
        assertEquals(1000, ((Number) hello.get("limit")).intValue());
    }

    @Test
    void sessionEventsEndMarksTerminal() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        store.updateStatus(id, org.aethercode.tasks.supervisor.ChildStatus.KILLED);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> r = (List<Map<String, Object>>) service.sessionEvents(
                Map.of("id", id));
        @SuppressWarnings("unchecked")
        Map<String, Object> end = (Map<String, Object>) r.get(r.size() - 1);
        assertEquals(Boolean.TRUE, end.get("terminal"));
    }
}
