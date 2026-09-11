package org.aethercode.tasks.limits;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LimitsEnforcerTest {

    @Test
    void unlimitedLimits_neverTrip() {
        LimitsEnforcer.Verdict v = LimitsEnforcer.evaluate(
                Limits.unlimited(),
                new LimitsEnforcer.Usage(1_000_000, 999_999, 99_999, 99_999, 99_999));
        assertFalse(v.any());
        assertEquals(0, v.tripped().size());
    }

    @Test
    void usageBelowLimit_doesNotTrip() {
        Limits l = Limits.builder().tokens(100).calls(5).build();
        LimitsEnforcer.Verdict v = LimitsEnforcer.evaluate(
                l, new LimitsEnforcer.Usage(0, 50, 3, 0, 0));
        assertFalse(v.any());
    }

    @Test
    void usageEqualToLimit_doesNotTrip() {
        // The predicate is strict-greater-than; matching the cap
        // exactly is still in budget.
        Limits l = Limits.builder().tokens(100).build();
        LimitsEnforcer.Verdict v = LimitsEnforcer.evaluate(
                l, new LimitsEnforcer.Usage(0, 100, 0, 0, 0));
        assertFalse(v.any());
    }

    @Test
    void singleFieldExceeded_tripsThatField() {
        Limits l = Limits.builder().tokens(100).build();
        LimitsEnforcer.Verdict v = LimitsEnforcer.evaluate(
                l, new LimitsEnforcer.Usage(0, 101, 0, 0, 0));
        assertTrue(v.any());
        assertEquals(1, v.tripped().size());
        LimitsEnforcer.LimitHit h = v.tripped().get(0);
        assertEquals("tokens", h.name());
        assertEquals(100L, h.limit());
        assertEquals(101L, h.actual());
    }

    @Test
    void multipleFieldsExceeded_tripsAll() {
        Limits l = Limits.builder()
                .wallClockMs(1000).tokens(50).calls(2).fileWrites(1).network(0)
                .build();
        LimitsEnforcer.Verdict v = LimitsEnforcer.evaluate(
                l, new LimitsEnforcer.Usage(2000, 75, 3, 5, 1));
        assertTrue(v.any());
        assertEquals(5, v.tripped().size());
        assertTrue(v.tripped().stream().anyMatch(h -> h.name().equals("wallClockMs")));
        assertTrue(v.tripped().stream().anyMatch(h -> h.name().equals("tokens")));
        assertTrue(v.tripped().stream().anyMatch(h -> h.name().equals("calls")));
        assertTrue(v.tripped().stream().anyMatch(h -> h.name().equals("fileWrites")));
        assertTrue(v.tripped().stream().anyMatch(h -> h.name().equals("network")));
    }

    @Test
    void partialLimits_onlyEnforceSetFields() {
        // Only tokens has a cap; a wildly large fileWrites usage
        // should not trip.
        Limits l = Limits.builder().tokens(10).build();
        LimitsEnforcer.Verdict v = LimitsEnforcer.evaluate(
                l, new LimitsEnforcer.Usage(0, 50, 0, 999_999, 0));
        assertTrue(v.any());
        assertEquals(1, v.tripped().size());
        assertEquals("tokens", v.tripped().get(0).name());
    }

    @Test
    void limitHit_toJson_isValidJsonFragment() {
        LimitsEnforcer.LimitHit h = new LimitsEnforcer.LimitHit("calls", 5L, 7L);
        String json = h.toJson();
        assertTrue(json.contains("\"name\":\"calls\""));
        assertTrue(json.contains("\"limit\":5"));
        assertTrue(json.contains("\"actual\":7"));
    }

    @Test
    void verdictToJsonList_emptyWhenNoTrips() {
        LimitsEnforcer.Verdict v = new LimitsEnforcer.Verdict(java.util.List.of());
        assertEquals("[]", v.toJsonList());
    }

    @Test
    void verdictToJsonList_concatenatesHits() {
        LimitsEnforcer.Verdict v = new LimitsEnforcer.Verdict(java.util.List.of(
                new LimitsEnforcer.LimitHit("tokens", 100L, 200L),
                new LimitsEnforcer.LimitHit("calls", 5L, 8L)
        ));
        String json = v.toJsonList();
        assertTrue(json.startsWith("["));
        assertTrue(json.endsWith("]"));
        assertTrue(json.contains("\"name\":\"tokens\""));
        assertTrue(json.contains("\"name\":\"calls\""));
    }

    @Test
    void usageBuilder_roundTrip() {
        LimitsEnforcer.Usage u = new LimitsEnforcer.Builder()
                .wallClockMs(1000).tokens(200).calls(3).fileWrites(2).network(1)
                .build();
        assertEquals(1000L, u.wallClockMs());
        assertEquals(200L, u.tokens());
        assertEquals(3L, u.calls());
        assertEquals(2L, u.fileWrites());
        assertEquals(1L, u.network());
        assertEquals(u, u.toBuilder().build());
    }
}
