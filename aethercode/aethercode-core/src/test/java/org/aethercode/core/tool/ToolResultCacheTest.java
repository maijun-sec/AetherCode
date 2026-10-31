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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link ToolResultCache}. Verifies LRU
 * eviction, TTL expiry, and basic put/get semantics.
 */
class ToolResultCacheTest {

    @Test
    void putAndGet_roundTrips() {
        ToolResultCache c = new ToolResultCache(10, 60_000L);
        c.put("k1", "v1");
        assertEquals(Optional.of("v1"), c.get("k1"));
    }

    @Test
    void get_unknownKeyReturnsEmpty() {
        ToolResultCache c = new ToolResultCache(10, 60_000L);
        assertTrue(c.get("missing").isEmpty());
    }

    @Test
    void lruEviction_dropsOldestOnOverflow() {
        ToolResultCache c = new ToolResultCache(3, 60_000L);
        c.put("a", "1");
        c.put("b", "2");
        c.put("c", "3");
        // Access "a" so it becomes most-recently-used.
        c.get("a");
        c.put("d", "4"); // evicts "b" (LRU)
        assertTrue(c.get("a").isPresent(), "a should survive (was just accessed)");
        assertTrue(c.get("b").isEmpty(), "b should be evicted (LRU)");
        assertTrue(c.get("c").isPresent());
        assertTrue(c.get("d").isPresent());
    }

    @Test
    void ttlExpiry_dropsAfterTtl() throws Exception {
        ToolResultCache c = new ToolResultCache(10, 50L);
        c.put("k", "v");
        assertTrue(c.get("k").isPresent());
        Thread.sleep(80);
        assertTrue(c.get("k").isEmpty(), "entry should expire after TTL");
    }

    @Test
    void ttlZero_neverExpires() throws Exception {
        ToolResultCache c = new ToolResultCache(10, 0L);
        c.put("k", "v");
        Thread.sleep(50);
        assertTrue(c.get("k").isPresent());
    }

    @Test
    void evictExpired_removesStaleEntries() throws Exception {
        ToolResultCache c = new ToolResultCache(10, 50L);
        c.put("old", "1");
        Thread.sleep(80);
        c.put("new", "2");
        int removed = c.evictExpired();
        assertEquals(1, removed);
        assertEquals(1, c.size());
        assertFalse(c.containsKey("old"));
        assertTrue(c.containsKey("new"));
    }

    @Test
    void invalidate_removesSingleKey() {
        ToolResultCache c = new ToolResultCache(10, 60_000L);
        c.put("k1", "v1");
        c.put("k2", "v2");
        c.invalidate("k1");
        assertTrue(c.get("k1").isEmpty());
        assertTrue(c.get("k2").isPresent());
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResultCache(0, 60_000L));
        assertThrows(IllegalArgumentException.class, () -> new ToolResultCache(10, -1L));
    }
}
