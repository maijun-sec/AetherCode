package org.aethercode.orchestration.perf;

import org.aethercode.orchestration.verifier.Verifier.Severity;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ActionCache}.
 */
class ActionCacheTest {

    private static VerificationResult pass() {
        return VerificationResult.pass("ok");
    }

    private static VerificationResult fail() {
        return VerificationResult.fail(Severity.BLOCK, "no");
    }

    @Test
    void constructorRejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> new ActionCache(0));
        assertThrows(IllegalArgumentException.class, () -> new ActionCache(-1));
    }

    @Test
    void getReturnsNullOnMiss() {
        ActionCache c = new ActionCache(10);
        assertNull(c.get("x"));
        assertEquals(0L, c.hits());
        assertEquals(1L, c.misses());
    }

    @Test
    void putAndGetRoundTrips() {
        ActionCache c = new ActionCache(10);
        VerificationResult r = pass();
        c.put("x", r);
        assertSame(r, c.get("x"));
        assertEquals(1L, c.hits());
        assertEquals(0L, c.misses());
    }

    @Test
    void putRejectsNullArgs() {
        ActionCache c = new ActionCache(10);
        assertThrows(IllegalArgumentException.class, () -> c.put(null, pass()));
        assertThrows(IllegalArgumentException.class, () -> c.put("x", null));
    }

    @Test
    void getOrComputeCachesTheFreshResult() {
        ActionCache c = new ActionCache(10);
        AtomicInteger loaderCalls = new AtomicInteger();
        VerificationResult r1 = c.getOrCompute("x", k -> {
            loaderCalls.incrementAndGet();
            return pass();
        });
        VerificationResult r2 = c.getOrCompute("x", k -> {
            loaderCalls.incrementAndGet();
            return pass();
        });
        assertSame(r1, r2, "second call must hit the cache");
        assertEquals(1, loaderCalls.get(), "loader must run once");
        assertEquals(1, c.hits());
        assertEquals(1, c.misses());
    }

    @Test
    void lruEvictionRemovesLeastRecentlyUsed() {
        ActionCache c = new ActionCache(2);
        c.put("a", pass());
        c.put("b", pass());
        // Touch "a" so it's most-recently used; "b" is now the LRU.
        assertNotNull(c.get("a"));
        c.put("c", pass());
        // "b" should have been evicted; "a" and "c" remain.
        assertNull(c.get("b"));
        assertNotNull(c.get("a"));
        assertNotNull(c.get("c"));
        assertEquals(2, c.size());
    }

    @Test
    void hitRateTracksLookups() {
        ActionCache c = new ActionCache(10);
        c.put("x", pass());
        c.get("x"); // hit
        c.get("y"); // miss
        c.get("x"); // hit
        assertEquals(2L, c.hits());
        assertEquals(1L, c.misses());
        assertEquals(2.0 / 3.0, c.hitRate(), 1e-9);
    }

    @Test
    void clearResetsStateAndCounters() {
        ActionCache c = new ActionCache(10);
        c.put("x", pass());
        c.get("x");
        c.clear();
        assertEquals(0, c.size());
        assertEquals(0L, c.hits());
        assertEquals(0L, c.misses());
        assertNull(c.get("x"));
    }

    @Test
    void keysAreDedupedByEquals() {
        ActionCache c = new ActionCache(10);
        VerificationResult r = pass();
        VerificationResult f = fail();
        c.put("x", r);
        c.put("x", f);
        assertSame(f, c.get("x"),
                "second put with the same key must replace the value");
        assertEquals(1, c.size());
        assertTrue(c.misses() == 0L);
    }
}
