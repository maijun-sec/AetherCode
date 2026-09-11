package org.aethercode.core.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link PersistentToolResultCache}. Verifies
 * round-trip persistence and key sanitization.
 */
class PersistentToolResultCacheTest {

    @Test
    void putAndGet_roundTrips(@TempDir Path tmp) {
        PersistentToolResultCache c = new PersistentToolResultCache(tmp);
        c.put("k1", "v1");
        assertEquals(Optional.of("v1"), c.get("k1"));
        assertTrue(c.containsKey("k1"));
    }

    @Test
    void survivesCacheRecreation(@TempDir Path tmp) throws Exception {
        // First instance writes, second instance reads.
        PersistentToolResultCache a = new PersistentToolResultCache(tmp);
        a.put("persistent-key", "persistent-value");
        // The directory persists across "sessions" (cache recreations).
        PersistentToolResultCache b = new PersistentToolResultCache(tmp);
        assertEquals(Optional.of("persistent-value"), b.get("persistent-key"));
    }

    @Test
    void keySanitization_blocksPathEscape(@TempDir Path tmp) throws IOException {
        PersistentToolResultCache c = new PersistentToolResultCache(tmp);
        // Attempt to escape via "../" — sanitized to "_.._"
        c.put("../escape", "value");
        // No file should have been created outside the root.
        // The sanitized file is "_.._escape" inside root.
        assertTrue(c.containsKey("../escape"));
        // And no parent escape.
        assertFalse(java.nio.file.Files.exists(tmp.getParent().resolve("escape")));
    }

    @Test
    void invalidate_removesKey(@TempDir Path tmp) {
        PersistentToolResultCache c = new PersistentToolResultCache(tmp);
        c.put("k", "v");
        c.invalidate("k");
        assertTrue(c.get("k").isEmpty());
    }

    @Test
    void size_reflectsEntries(@TempDir Path tmp) {
        PersistentToolResultCache c = new PersistentToolResultCache(tmp);
        c.put("a", "1");
        c.put("b", "2");
        c.put("c", "3");
        assertEquals(3, c.size());
    }
}
