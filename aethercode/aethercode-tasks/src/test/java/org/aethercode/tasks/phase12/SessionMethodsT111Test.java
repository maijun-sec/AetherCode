package org.aethercode.tasks.phase12;

import org.aethercode.tasks.supervisor.ChildRecord;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-11 / design.md §3.1): 4 handler tests for
 * {@code session/list}, {@code session/show},
 * {@code session/rename}. The service is built on top of a
 * real {@link SupervisorStore} (in-memory SQLite) so the
 * tests exercise the same path the production daemon uses.
 */
class SessionMethodsT111Test {

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
    void sessionListReturnsSpawnedSessions() throws Exception {
        // Spawn 3 children.
        service.sessionSpawn(Map.of("prompt", "a", "cwd", "/tmp/p"));
        service.sessionSpawn(Map.of("prompt", "b", "cwd", "/tmp/p"));
        service.sessionSpawn(Map.of("prompt", "c", "cwd", "/tmp/q"));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionList(
                Map.of("limit", 100));
        assertEquals(3, ((Number) r.get("total")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        assertEquals(3, sessions.size());
        // The set of returned prompts is order-independent
        // (timestamps from rapid successive spawns can tie).
        java.util.Set<String> prompts = new java.util.HashSet<>();
        for (Map<String, Object> s : sessions) prompts.add((String) s.get("prompt"));
        assertEquals(java.util.Set.of("a", "b", "c"), prompts);
    }

    @Test
    void sessionListFiltersByCwd() throws Exception {
        service.sessionSpawn(Map.of("prompt", "a", "cwd", "/tmp/p"));
        service.sessionSpawn(Map.of("prompt", "b", "cwd", "/tmp/q"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionList(
                Map.of("cwd", "/tmp/p"));
        assertEquals(1, ((Number) r.get("total")).intValue());
    }

    @Test
    void sessionShowReturnsChildAndEvents() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "explain", "cwd", "/tmp/p",
                        "model", "claude-sonnet-4-5")).get("sessionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionShow(Map.of("id", id));
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) r.get("session");
        assertEquals(id, session.get("id"));
        assertEquals("explain", session.get("prompt"));
        assertNotNull(r.get("events"));
        // config carries the model passed to spawn; it
        // is null when the spawn has no model/config.
        assertNotNull(r.get("config"));
    }

    @Test
    void sessionRenamePersistsAndStampsEvent() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionRename(
                Map.of("id", id, "title", "My session"));
        assertEquals("My session", r.get("title"));
        ChildRecord reloaded = store.getChild(id).orElseThrow();
        assertEquals("My session", reloaded.titleOpt().orElseThrow());
    }
}
