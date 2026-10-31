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
