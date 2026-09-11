package org.aethercode.core.cache;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtlCacheTest {

    @Test
    void put_thenGet_returnsValue() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.put("k", "v");
        assertTrue(c.get("k").isPresent());
        assertEquals("v", c.get("k").get());
    }

    @Test
    void get_missingKeyReturnsEmpty() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        assertFalse(c.get("missing").isPresent());
    }

    @Test
    void get_withLoader_cachesResult() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        int[] calls = {0};
        String v1 = c.get("k", k -> { calls[0]++; return "computed"; }).orElseThrow();
        String v2 = c.get("k", k -> { calls[0]++; return "computed"; }).orElseThrow();
        assertEquals("computed", v1);
        assertEquals("computed", v2);
        assertEquals(1, calls[0]); // loader called once
    }

    @Test
    void entryExpires_afterTtl() throws Exception {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMillis(50));
        c.put("k", "v");
        assertTrue(c.get("k").isPresent());
        Thread.sleep(80);
        assertFalse(c.get("k").isPresent());
    }

    @Test
    void get_withLoaderReloadsAfterExpiry() throws Exception {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMillis(50));
        int[] calls = {0};
        c.put("k", "v1");
        // First get finds fresh entry — loader not called
        assertEquals("v1", c.get("k", k -> { calls[0]++; return "x"; }).orElse(null));
        Thread.sleep(80);
        // After expiry, loader IS called
        assertEquals("v2", c.get("k", k -> { calls[0]++; return "v2"; }).orElse(null));
        assertEquals(1, calls[0]);
    }

    @Test
    void invalidate_removesEntry() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.put("k", "v");
        c.invalidate("k");
        assertFalse(c.get("k").isPresent());
    }

    @Test
    void invalidate_missingKeyIsNoOp() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.invalidate("missing"); // should not throw
    }

    @Test
    void clear_emptiesEverything() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.put("a", "1");
        c.put("b", "2");
        c.clear();
        assertEquals(0, c.size());
    }

    @Test
    void prune_removesExpiredOnly() throws Exception {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMillis(50));
        c.put("a", "1");
        Thread.sleep(80);
        c.put("b", "2");
        int n = c.prune();
        assertEquals(1, n);
        assertEquals(1, c.size());
    }

    @Test
    void hits_incrementsOnGet() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.put("k", "v");
        c.get("k");
        c.get("k");
        assertEquals(2, c.hits());
    }

    @Test
    void misses_incrementsOnMissing() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.get("missing");
        c.get("missing");
        assertEquals(2, c.misses());
    }

    @Test
    void evictions_incrementOnInvalidate() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.put("k", "v");
        c.invalidate("k");
        assertEquals(1, c.evictions());
    }

    @Test
    void constructor_rejectsBadTtl() {
        assertThrows(IllegalArgumentException.class, () -> new TtlCache<String, String>(null));
        assertThrows(IllegalArgumentException.class, () -> new TtlCache<String, String>(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TtlCache<String, String>(Duration.ofMillis(-1)));
    }

    @Test
    void defaultTtl_returnsConstructorValue() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(7));
        assertEquals(Duration.ofMinutes(7), c.defaultTtl());
    }

    @Test
    void put_withCustomTtl_overridesDefault() throws Exception {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.put("k", "v", Duration.ofMillis(30));
        Thread.sleep(60);
        assertFalse(c.get("k").isPresent());
    }

    @Test
    void put_rejectsNullKeyOrValue() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        assertThrows(NullPointerException.class, () -> c.put(null, "v"));
        assertThrows(NullPointerException.class, () -> c.put("k", null));
    }

    @Test
    void containsKey_returnsFalseAfterExpiry() throws Exception {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMillis(30));
        c.put("k", "v");
        assertTrue(c.containsKey("k"));
        Thread.sleep(60);
        assertFalse(c.containsKey("k"));
    }

    @Test
    void keys_returnsAllKeys() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.put("a", "1");
        c.put("b", "2");
        c.put("c", "3");
        assertEquals(3, c.keys().size());
    }

    @Test
    void putAll_addsAllEntries() {
        TtlCache<String, String> c = new TtlCache<>(Duration.ofMinutes(1));
        c.putAll(Map.of("a", "1", "b", "2"));
        assertEquals(2, c.size());
    }
}
