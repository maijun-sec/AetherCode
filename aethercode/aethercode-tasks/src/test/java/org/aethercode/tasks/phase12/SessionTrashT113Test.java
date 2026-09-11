package org.aethercode.tasks.phase12;

import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1.2 (T-1-13 / design.md §3.1): 3 handler tests for
 * session/trash. The method has four modes (empty / restore
 * / list / count); the tests cover the three that mutate
 * state plus the bare count.
 */
class SessionTrashT113Test {

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
    void sessionTrashCountReturnsTrashedRowCount() throws Exception {
        String a = (String) service.sessionSpawn(
                Map.of("prompt", "a", "cwd", "/tmp/p")).get("sessionId");
        service.sessionSpawn(Map.of("prompt", "b", "cwd", "/tmp/p"));
        service.sessionDelete(Map.of("id", a));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionTrash(Map.of());
        assertEquals(1, ((Number) r.get("count")).intValue());
    }

    @Test
    void sessionTrashListReturnsDeletedRows() throws Exception {
        String a = (String) service.sessionSpawn(
                Map.of("prompt", "a", "cwd", "/tmp/p")).get("sessionId");
        String b = (String) service.sessionSpawn(
                Map.of("prompt", "b", "cwd", "/tmp/p")).get("sessionId");
        service.sessionDelete(Map.of("id", a));
        service.sessionDelete(Map.of("id", b));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionTrash(
                Map.of("list", true));
        assertEquals(2, ((Number) r.get("total")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("trashed");
        assertEquals(2, rows.size());
    }

    @Test
    void sessionTrashEmptyRemovesAll() throws Exception {
        String a = (String) service.sessionSpawn(
                Map.of("prompt", "a", "cwd", "/tmp/p")).get("sessionId");
        String b = (String) service.sessionSpawn(
                Map.of("prompt", "b", "cwd", "/tmp/p")).get("sessionId");
        service.sessionDelete(Map.of("id", a));
        service.sessionDelete(Map.of("id", b));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionTrash(
                Map.of("empty", true));
        assertEquals(2, ((Number) r.get("removed")).intValue());
        // Subsequent count is zero.
        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = (Map<String, Object>) service.sessionTrash(Map.of());
        assertEquals(0, ((Number) r2.get("count")).intValue());
    }
}
