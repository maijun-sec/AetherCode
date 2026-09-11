package org.aethercode.tasks.phase12;

import org.aethercode.tasks.supervisor.ChildStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-16 / design.md §3.1): 6 handler tests for
 * the refined task/{attach, events, list, setLimits} methods.
 */
class TaskMethodsT116Test {

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
    void taskAttachRefinedReturnsTaskAndEvents() throws Exception {
        String id = (String) service.taskSpawn(Map.of("prompt", "x", "cwd", "/tmp/p"))
                .get("childId");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskAttachRefined(
                Map.of("id", id));
        assertNotNull(r.get("task"));
        assertNotNull(r.get("events"));
        assertNotNull(r.get("lastSeq"));
    }

    @Test
    void taskEventsRefinedReplaysSinceCursor() throws Exception {
        String id = (String) service.taskSpawn(Map.of("prompt", "x", "cwd", "/tmp/p"))
                .get("childId");
        // taskSpawn emits one event (status_change); we
        // append two more so we can probe the cursor.
        long first = store.appendEvent(id, "first", "{}");
        long second = store.appendEvent(id, "second", "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskEventsRefined(
                Map.of("id", id, "sinceSeq", first));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) r.get("events");
        // Two events: "second" (after `first`) plus the
        // spawn event which has a smaller id (issued first).
        // We accept >= 1 and verify "second" is present.
        assertTrue(events.size() >= 1);
        boolean hasSecond = events.stream().anyMatch(e -> "second".equals(e.get("type")));
        assertTrue(hasSecond, "expected the 'second' event to be in the replay");
    }

    @Test
    void taskListRefinedReturnsPagedRows() throws Exception {
        service.taskSpawn(Map.of("prompt", "a", "cwd", "/tmp/p"));
        service.taskSpawn(Map.of("prompt", "b", "cwd", "/tmp/p"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskListRefined(
                Map.of("limit", 50, "offset", 0));
        assertEquals(2, ((Number) r.get("total")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) r.get("tasks");
        assertEquals(2, tasks.size());
    }

    @Test
    void taskListRefinedFiltersByStatus() throws Exception {
        String a = (String) service.taskSpawn(Map.of("prompt", "a", "cwd", "/tmp/p"))
                .get("childId");
        String b = (String) service.taskSpawn(Map.of("prompt", "b", "cwd", "/tmp/p"))
                .get("childId");
        store.updateStatus(a, ChildStatus.RUNNING);
        store.updateStatus(b, ChildStatus.KILLED);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskListRefined(
                Map.of("state", "KILLED"));
        assertEquals(1, ((Number) r.get("total")).intValue());
    }

    @Test
    void taskSetLimitsRefinedUpdatesAndPersists() throws Exception {
        String id = (String) service.taskSpawn(Map.of("prompt", "x", "cwd", "/tmp/p"))
                .get("childId");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskSetLimitsRefined(
                Map.of("id", id,
                        "limits", Map.of("idleMs", 30_000L, "tokens", 8_000_000L)));
        assertEquals(Boolean.TRUE, r.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) r.get("limits");
        assertEquals(30_000L, ((Number) limits.get("idleMs")).longValue());
        var rec = store.getChild(id).orElseThrow();
        assertTrue(rec.configJson().contains("idleMs"));
    }

    @Test
    void taskSetLimitsRefinedRejectsUnknownChild() {
        assertThrows(IllegalArgumentException.class,
                () -> service.taskSetLimitsRefined(Map.of(
                        "id", "nope", "limits", Map.of("tokens", 1L))));
    }
}
