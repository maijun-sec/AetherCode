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
