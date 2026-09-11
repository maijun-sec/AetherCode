package org.aethercode.tasks.phase12;

import org.aethercode.tasks.supervisor.ChildStatus;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-15 / design.md §3.1): 4 handler tests for
 * the refined task/{spawn, resume, pause, kill} methods.
 */
class TaskMethodsT115Test {

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
    void taskSpawnRefinedAddsModelAndLimits() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskSpawnRefined(Map.of(
                "prompt", "explain", "cwd", "/tmp/p",
                "model", "claude-sonnet-4-5",
                "limits", Map.of("idleMs", 30_000L)));
        String id = (String) r.get("taskId");
        assertNotNull(id);
        assertEquals(ChildStatus.QUEUED.name(), r.get("status"));
        // Limits persisted to config.
        var rec = store.getChild(id).orElseThrow();
        assertTrue(rec.configJson().contains("claude-sonnet-4-5"));
        assertTrue(rec.configJson().contains("idleMs"));
    }

    @Test
    void taskResumeRefinedUsesIdField() throws Exception {
        String id = (String) service.taskSpawn(Map.of("prompt", "x", "cwd", "/tmp/p"))
                .get("childId");
        // QUEUED → RUNNING → PAUSED is the legal path
        // (the state machine rejects QUEUED → PAUSED).
        store.updateStatus(id, ChildStatus.RUNNING);
        store.updateStatus(id, ChildStatus.PAUSED);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskResumeRefined(
                Map.of("id", id));
        assertEquals(ChildStatus.RUNNING.name(), r.get("status"));
        assertEquals(id, r.get("id"));
    }

    @Test
    void taskPauseRefinedPausesRunningAndStampsEvent() throws Exception {
        String id = (String) service.taskSpawn(Map.of("prompt", "x", "cwd", "/tmp/p"))
                .get("childId");
        store.updateStatus(id, ChildStatus.RUNNING);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskPauseRefined(
                Map.of("id", id, "reason", "limit hit"));
        assertEquals(ChildStatus.PAUSED.name(), r.get("status"));
        assertEquals(Boolean.FALSE, r.get("noop"));
        var rec = store.getChild(id).orElseThrow();
        assertEquals("limit hit", rec.errorOpt().orElseThrow());
    }

    @Test
    void taskKillRefinedKillsRunningAndIsIdempotent() throws Exception {
        String id = (String) service.taskSpawn(Map.of("prompt", "x", "cwd", "/tmp/p"))
                .get("childId");
        store.updateStatus(id, ChildStatus.RUNNING);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.taskKillRefined(
                Map.of("id", id, "reason", "user"));
        assertEquals(ChildStatus.KILLED.name(), r.get("status"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        // Second kill is a noop.
        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = (Map<String, Object>) service.taskKillRefined(
                Map.of("id", id));
        assertEquals(ChildStatus.KILLED.name(), r2.get("status"));
        assertEquals(Boolean.TRUE, r2.get("noop"));
    }
}
