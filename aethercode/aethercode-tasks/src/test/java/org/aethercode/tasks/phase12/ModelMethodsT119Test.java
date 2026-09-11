package org.aethercode.tasks.phase12;

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
 * Phase 1.2 (T-1-19 / design.md §3.1): 4 handler tests for
 * model/{list, get, set}. The first two serve a static
 * catalog (Anthropic + OpenAI). model/set is the
 * mid-session switch: it writes the new model to the
 * child's {@code config} JSON.
 */
class ModelMethodsT119Test {

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
    void modelListReturnsCatalog() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.modelList(
                Map.of());
        assertTrue(rows.size() >= 3,
                "expected at least 3 models in the default catalog");
        boolean foundAnthropic = rows.stream().anyMatch(m -> "anthropic".equals(m.get("provider")));
        assertTrue(foundAnthropic, "catalog should include Anthropic models");
    }

    @Test
    void modelListFiltersByProvider() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.modelList(
                Map.of("provider", "openai"));
        assertTrue(rows.stream().allMatch(m -> "openai".equals(m.get("provider"))));
    }

    @Test
    void modelGetReturnsSingleModel() {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.modelGet(
                Map.of("name", "claude-sonnet-4-5"));
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) r.get("model");
        assertEquals("claude-sonnet-4-5", model.get("name"));
        assertEquals("anthropic", model.get("provider"));
    }

    @Test
    void modelSetPersistsConfig() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.modelSet(Map.of(
                "sessionId", id, "name", "claude-opus-4-7"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("claude-opus-4-7", r.get("name"));
        var rec = store.getChild(id).orElseThrow();
        assertTrue(rec.configJson().contains("claude-opus-4-7"));
    }
}
