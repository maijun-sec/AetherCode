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
