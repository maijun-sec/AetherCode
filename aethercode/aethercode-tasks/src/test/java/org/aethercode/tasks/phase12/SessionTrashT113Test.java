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
