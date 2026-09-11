package org.aethercode.core.cost;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * tests for the system-prompt cache. The cache is supposed
 * to skip re-rendering when the input key hasn't changed.
 */
class PromptCacheTest {

    @Test
    void cacheHitsOnRepeatedKey() {
        PromptCache cache = new PromptCache();
        AtomicInteger renderCount = new AtomicInteger();
        // First call: miss, renders.
        String v1 = cache.getOrRender("k1", () -> {
            renderCount.incrementAndGet();
            return "rendered " + System.nanoTime();
        });
        // Second call with the same key: hit, no re-render.
        String v2 = cache.getOrRender("k1", () -> {
            renderCount.incrementAndGet();
            return "should-not-be-called";
        });
        assertEquals(v1, v2, "cache must return the same value on hit");
        assertEquals(1, renderCount.get(), "render should only run once");
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void differentKeysEachRender() {
        PromptCache cache = new PromptCache();
        cache.getOrRender("k1", () -> "value1");
        cache.getOrRender("k2", () -> "value2");
        cache.getOrRender("k3", () -> "value3");
        assertEquals(0, cache.hits());
        assertEquals(3, cache.misses());
        assertEquals(3, cache.size());
    }

    @Test
    void clearWipesEverything() {
        PromptCache cache = new PromptCache();
        cache.getOrRender("k1", () -> "v1");
        cache.getOrRender("k2", () -> "v2");
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void keyOf_isDeterministic() {
        String k1 = PromptCache.keyOf("alpha", "beta", "gamma");
        String k2 = PromptCache.keyOf("alpha", "beta", "gamma");
        assertEquals(k1, k2);
    }

    @Test
    void keyOf_differentInputsDifferentKeys() {
        String k1 = PromptCache.keyOf("alpha", "beta");
        String k2 = PromptCache.keyOf("alpha", "beta2");
        assertNotEquals(k1, k2);
    }

    @Test
    void keyOf_nullTreatedAsEmpty() {
        String k1 = PromptCache.keyOf("alpha", null, "beta");
        String k2 = PromptCache.keyOf("alpha", "", "beta");
        assertEquals(k1, k2, "null parts should hash the same as empty string parts");
    }

    @Test
    void keyOf_handlesNullVarargs() {
        // Empty varargs should still produce a stable hash.
        String k1 = PromptCache.keyOf();
        String k2 = PromptCache.keyOf();
        assertEquals(k1, k2);
    }
}
