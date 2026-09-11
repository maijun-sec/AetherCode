package org.aethercode.tasks.limits;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LimitsTest {

    @Test
    void unlimited_hasNoFields() {
        Limits l = Limits.unlimited();
        assertTrue(l.isUnlimited());
        assertNull(l.wallClockMs());
        assertNull(l.tokens());
        assertNull(l.calls());
        assertNull(l.fileWrites());
        assertNull(l.network());
    }

    @Test
    void builder_setsAllFields() {
        Limits l = Limits.builder()
                .wallClockMs(60_000)
                .tokens(8192)
                .calls(50)
                .fileWrites(20)
                .network(10)
                .build();
        assertEquals(60_000L, l.wallClockMs());
        assertEquals(8192L, l.tokens());
        assertEquals(50L, l.calls());
        assertEquals(20L, l.fileWrites());
        assertEquals(10L, l.network());
        assertFalse(l.isUnlimited());
    }

    @Test
    void merge_overridesOnlySetFields() {
        Limits base = Limits.builder()
                .wallClockMs(60_000)
                .tokens(8192)
                .calls(50)
                .build();
        Limits updated = base.merge(Limits.builder().wallClockMs(120_000));
        assertEquals(120_000L, updated.wallClockMs());
        assertEquals(8192L, updated.tokens());
        assertEquals(50L, updated.calls());
    }

    @Test
    void toMap_omitsNullFields() {
        Limits l = Limits.builder().wallClockMs(30_000).calls(10).build();
        Map<String, Object> m = l.toMap();
        assertEquals(30_000L, m.get("wallClockMs"));
        assertEquals(10L, m.get("calls"));
        assertFalse(m.containsKey("tokens"));
        assertFalse(m.containsKey("fileWrites"));
        assertFalse(m.containsKey("network"));
    }

    @Test
    void fromMap_roundTrip() {
        Limits l = Limits.builder()
                .wallClockMs(60_000)
                .tokens(2048)
                .calls(7)
                .fileWrites(3)
                .network(1)
                .build();
        Limits parsed = Limits.fromMap(l.toMap());
        assertEquals(l, parsed);
    }

    @Test
    void fromMap_handlesNullAndEmpty() {
        assertEquals(Limits.unlimited(), Limits.fromMap(null));
        assertEquals(Limits.unlimited(), Limits.fromMap(Map.of()));
    }

    @Test
    void fromMap_acceptsWallClockAlias() {
        Limits l = Limits.fromMap(Map.of("wallClock", 42_000L));
        assertEquals(42_000L, l.wallClockMs());
    }
}
