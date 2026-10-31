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

package org.aethercode.core.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for {@link AppState#surfacedMemories()}, the per-session
 * tracking of memory files already injected into the system prompt. The
 * engine uses this to avoid re-injecting the same memory file on every
 * turn within a session.
 */
class AppStateMemoryTest {

    @Test
    void surfacedMemories_initiallyEmpty() {
        AppState s = new AppState("sid-1", Path.of("/tmp"));
        assertTrue(s.surfacedMemories().isEmpty());
    }

    @Test
    void markMemorySurfaced_addsPath() {
        AppState s = new AppState("sid-1", Path.of("/tmp"));
        Path p = Path.of("/home/user/.aethercode/agent-memory/x/note.md");
        assertTrue(s.markMemorySurfaced(p));
        assertTrue(s.surfacedMemories().contains(p));
    }

    @Test
    void markMemorySurfaced_dedupesByPath() {
        AppState s = new AppState("sid-1", Path.of("/tmp"));
        Path p = Path.of("/home/user/.aethercode/agent-memory/x/note.md");
        // First call adds; second is a no-op and returns false (Set semantics).
        assertTrue(s.markMemorySurfaced(p));
        assertFalse(s.markMemorySurfaced(p));
        assertEquals(1, s.surfacedMemories().size());
    }

    @Test
    void surfacedMemories_concurrentAdd() throws Exception {
        AppState s = new AppState("sid-1", Path.of("/tmp"));
        int n = 100;
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> s.markMemorySurfaced(Path.of("/p/" + idx + ".md")));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        assertEquals(n, s.surfacedMemories().size());
    }
}
