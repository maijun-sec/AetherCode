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
