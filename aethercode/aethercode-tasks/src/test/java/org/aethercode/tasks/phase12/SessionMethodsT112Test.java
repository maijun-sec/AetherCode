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

import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.ChildStatus;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-12 / design.md §3.1): 6 handler tests for
 * session/{spawn, resume, delete, restore}.
 */
class SessionMethodsT112Test {

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
    void sessionSpawnCreatesChildInQueued() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionSpawn(Map.of(
                "prompt", "explain this", "cwd", "/tmp/p",
                "title", "Test", "model", "claude-sonnet-4-5"));
        String id = (String) r.get("sessionId");
        assertNotNull(id);
        assertEquals(ChildStatus.QUEUED.name(), r.get("status"));
        ChildRecord rec = store.getChild(id).orElseThrow();
        assertEquals("Test", rec.titleOpt().orElseThrow());
        // Model lands in config (caller-side effect).
        assertTrue(rec.configJson().contains("claude-sonnet-4-5"));
    }

    @Test
    void sessionSpawnRejectsMissingPrompt() {
        assertThrows(IllegalArgumentException.class,
                () -> service.sessionSpawn(Map.of("cwd", "/tmp")));
    }

    @Test
    void sessionResumeFromPausedTransitionsToRunning() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        // QUEUED → RUNNING is the only legal first step;
        // RUNNING → PAUSED is the pause path the user
        // takes via session/pause.
        store.updateStatus(id, ChildStatus.RUNNING);
        store.updateStatus(id, ChildStatus.PAUSED);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionResume(
                Map.of("id", id));
        assertEquals(ChildStatus.RUNNING.name(), r.get("status"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.FALSE, r.get("noop"));
    }

    @Test
    void sessionResumeAlreadyRunningIsNoop() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        store.updateStatus(id, ChildStatus.RUNNING);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionResume(
                Map.of("id", id));
        assertEquals(Boolean.TRUE, r.get("noop"));
    }

    @Test
    void sessionDeleteSoftMovesToTrash() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionDelete(
                Map.of("id", id));
        assertEquals(Boolean.TRUE, r.get("trashed"));
        assertEquals(Boolean.FALSE, r.get("hard"));
        ChildRecord rec = store.getChild(id).orElseThrow();
        assertTrue(rec.isTrashed());
    }

    @Test
    void sessionRestoreUndoesSoftDelete() throws Exception {
        String id = (String) service.sessionSpawn(
                Map.of("prompt", "x", "cwd", "/tmp/p")).get("sessionId");
        service.sessionDelete(Map.of("id", id));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) service.sessionRestore(
                Map.of("id", id));
        assertEquals(Boolean.FALSE, r.get("trashed"));
        ChildRecord rec = store.getChild(id).orElseThrow();
        assertEquals(false, rec.isTrashed());
    }
}
