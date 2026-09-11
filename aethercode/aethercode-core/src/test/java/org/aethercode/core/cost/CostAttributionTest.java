package org.aethercode.core.cost;

import java.util.List;
import java.util.Map;
import org.aethercode.core.cost.CostAttribution.TaggedUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostAttributionTest {

    private static long totalProbes(CostTracker t) {
        return t.summary().byModel().values().stream()
                .mapToLong(CostTracker.ModelUsage::totalTokens).sum() / 1; // just to exercise it
    }

    private static long countCalls(CostTracker t) {
        return t.summary().byModel().values().stream()
                .mapToLong(m -> m.totalTokens() > 0 ? 1 : 0).sum();
    }

    @Test
    void record_unattributedGoesOnlyToGlobal() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", 1000, 0);
        assertEquals(1, countCalls(t));
        assertEquals(0, a.taggedCalls());
    }

    @Test
    void record_taggedGoesToBoth() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", "feature:x", 1000, 0);
        assertEquals(1, countCalls(t));
        assertEquals(1, a.taggedCalls());
        assertEquals(0.001, a.tagSummary("feature:x").totalCostUsd(), 1e-9);
    }

    @Test
    void record_multipleTags() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", new TaggedUsage(List.of("a", "b"), 1000, 0));
        assertEquals(0.001, a.tagSummary("a").totalCostUsd(), 1e-9);
        assertEquals(0.001, a.tagSummary("b").totalCostUsd(), 1e-9);
    }

    @Test
    void byTag_returnsAll() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", "x", 1000, 0);
        a.record("m", "y", 2000, 0);
        assertEquals(2, a.byTag().size());
    }

    @Test
    void byTag_includesAllTagged() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", "x", 1000, 0);
        a.record("m", "y", 2000, 0);
        Map<String, CostTracker.Summary> by = a.byTag();
        assertTrue(by.containsKey("x"));
        assertTrue(by.containsKey("y"));
    }

    @Test
    void tagSummary_unknownTagReturnsEmpty() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        CostTracker.Summary s = a.tagSummary("unknown");
        assertEquals(0, s.totalInput());
    }

    @Test
    void taggedCalls_incrementsForTagged() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", "x", 1000, 0);
        a.record("m", "x", 1000, 0);
        a.record("m", 1000, 0);
        assertEquals(2, a.taggedCalls());
    }

    @Test
    void topTags_returnsSortedByCost() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", "x", 1000, 0); // 0.001
        a.record("m", "y", 5000, 0); // 0.005
        a.record("m", "z", 2000, 0); // 0.002
        var top = a.topTags(3);
        assertEquals("y", top.get(0).getKey());
        assertEquals("z", top.get(1).getKey());
        assertEquals("x", top.get(2).getKey());
    }

    @Test
    void topTags_respectsLimit() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", "x", 1000, 0);
        a.record("m", "y", 2000, 0);
        a.record("m", "z", 3000, 0);
        var top = a.topTags(2);
        assertEquals(2, top.size());
    }

    @Test
    void tagCount_returnsDistinctTags() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        CostAttribution a = new CostAttribution(t);
        a.record("m", "x", 1000, 0);
        a.record("m", "x", 1000, 0);
        a.record("m", "y", 1000, 0);
        assertEquals(2, a.tagCount());
    }

    @Test
    void constructor_rejectsNullTracker() {
        assertThrows(IllegalArgumentException.class, () -> new CostAttribution(null));
    }

    @Test
    void record_rejectsNullArgs() {
        CostTracker t = new CostTracker();
        CostAttribution a = new CostAttribution(t);
        assertThrows(NullPointerException.class, () -> a.record(null, 0, 0));
        assertThrows(NullPointerException.class, () -> a.record("m", (TaggedUsage) null));
    }

    @Test
    void taggedUsage_constructsAndValidates() {
        TaggedUsage u = new TaggedUsage(List.of("a"), 10, 20);
        assertEquals(10, u.inputTokens());
        assertTrue(u.hasTag("a"));
        assertEquals(false, u.hasTag("b"));
    }

    @Test
    void taggedUsage_nullTagsBecomeEmpty() {
        TaggedUsage u = new TaggedUsage(null, 0, 0);
        assertTrue(u.tags().isEmpty());
    }

    @Test
    void tracker_returnsConstructorValue() {
        CostTracker t = new CostTracker();
        CostAttribution a = new CostAttribution(t);
        assertEquals(t, a.tracker());
    }
}
