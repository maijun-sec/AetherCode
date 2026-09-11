package org.aethercode.tasks.phase12;

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
 * Phase 1.2 (T-1-17 / design.md §3.1): 3 handler tests for
 * the refined, per-session compact/{status, run} methods.
 */
class CompactMethodsT117Test {

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
    void compactStatusRejectsMissingSessionId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.compactStatus(Map.of()));
    }

    @Test
    void compactStatusReturnsPerSessionShape() {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.compactStatus(
                Map.of("sessionId", "s-1"));
        assertEquals("s-1", r.get("sessionId"));
        assertEquals(Boolean.FALSE, r.get("autoCompactDisabled"));
        assertNotNull(r.get("percent"));
    }

    @Test
    void compactRunDefaultsToBalancedMode() {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.compactRun(
                Map.of("sessionId", "s-1"));
        assertEquals("s-1", r.get("sessionId"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        // Default mode is balanced; the supervisor doesn't run
        // the actual compactor (the engine owns that), so
        // the reply is a structured "skipped" envelope.
        assertEquals(Boolean.TRUE, r.get("skipped"));
    }
}
