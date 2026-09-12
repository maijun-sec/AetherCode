package org.aethercode.memory;

import org.aethercode.memory.SelectiveForgettingPolicy.Forgettable;
import org.aethercode.memory.SelectiveForgettingPolicy.Weights;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectiveForgettingPolicyTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final long ONE_YEAR_MS = 365L * 24 * 3600 * 1000;
    // Use a fixed nowMs that matches the test records
    private static SelectiveForgettingPolicy policyAt(long now) {
        return new SelectiveForgettingPolicy(
            new SelectiveForgettingPolicy.Weights(0.4, 0.3, 0.3), 0.2, now, 30L * 24 * 3600 * 1000);
    }

    private static Forgettable rec(String id, long lastAccess, int count, double importance) {
        return new Forgettable() {
            public String id() { return id; }
            public long lastAccessedMs() { return lastAccess; }
            public int accessCount() { return count; }
            public double structuralImportance() { return importance; }
        };
    }

    @Test
    void recentRecordScoresHigher() {
        SelectiveForgettingPolicy p = policyAt(NOW);
        Forgettable recent = rec("a", NOW, 1, 0.0);
        Forgettable old = rec("b", NOW - ONE_YEAR_MS, 1, 0.0);
        assertTrue(p.score(recent) > p.score(old));
    }

    @Test
    void frequentRecordScoresHigher() {
        SelectiveForgettingPolicy p = new SelectiveForgettingPolicy();
        Forgettable frequent = rec("a", NOW, 100, 0.0);
        Forgettable rare = rec("b", NOW, 0, 0.0);
        assertTrue(p.score(frequent) > p.score(rare));
    }

    @Test
    void importantRecordScoresHigher() {
        SelectiveForgettingPolicy p = new SelectiveForgettingPolicy();
        Forgettable important = rec("a", NOW, 0, 1.0);
        Forgettable unimportant = rec("b", NOW, 0, 0.0);
        assertTrue(p.score(important) > p.score(unimportant));
    }

    @Test
    void scoreBounded0to1() {
        SelectiveForgettingPolicy p = new SelectiveForgettingPolicy();
        Forgettable r = rec("a", NOW, 1000, 1.0);
        double s = p.score(r);
        assertTrue(s >= 0.0 && s <= 1.0, "score " + s + " out of [0,1]");
    }

    @Test
    void pruneRemovesLowScore() {
        SelectiveForgettingPolicy p = policyAt(NOW);
        Forgettable high = rec("high", NOW, 100, 1.0);
        Forgettable low = rec("low", 0, 0, 0.0);
        List<Forgettable> pruned = p.prune(List.of(high, low));
        assertEquals(1, pruned.size());
        assertEquals("high", pruned.get(0).id());
    }

    @Test
    void topKReturnsSorted() {
        SelectiveForgettingPolicy p = policyAt(NOW);
        Forgettable a = rec("a", NOW, 0, 0.5);
        Forgettable b = rec("b", NOW, 100, 1.0);
        Forgettable c = rec("c", NOW, 50, 0.5);
        List<Forgettable> top = p.topK(List.of(a, b, c), 2);
        assertEquals(2, top.size());
        assertEquals("b", top.get(0).id());
    }

    @Test
    void topKLargerThanListReturnsAll() {
        SelectiveForgettingPolicy p = policyAt(NOW);
        Forgettable a = rec("a", NOW, 1, 0.5);
        assertEquals(1, p.topK(List.of(a), 5).size());
    }

    @Test
    void invalidWeightsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Weights(0.5, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Weights(0.2, 0.2, 0.2));
    }

    @Test
    void customWeightsUsed() {
        SelectiveForgettingPolicy p = new SelectiveForgettingPolicy(
            new Weights(1.0, 0.0, 0.0), 0.0, NOW, 1000);
        Forgettable recent = rec("a", NOW, 0, 0.0);
        Forgettable old = rec("b", 0, 0, 0.0);
        assertTrue(p.score(recent) > p.score(old));
    }

    @Test
    void customThreshold() {
        SelectiveForgettingPolicy p = new SelectiveForgettingPolicy(
            new Weights(0.4, 0.3, 0.3), 0.9, NOW, 1000);
        Forgettable r = rec("a", NOW, 1, 0.5);
        assertTrue(p.prune(List.of(r)).isEmpty(), "should be pruned with high threshold");
    }

    @Test
    void weightsGetter() {
        SelectiveForgettingPolicy p = new SelectiveForgettingPolicy();
        assertEquals(0.4, p.weights().recency());
    }

    @Test
    void nullRecordRejected() {
        SelectiveForgettingPolicy p = new SelectiveForgettingPolicy();
        assertThrows(NullPointerException.class, () -> p.score(null));
    }
}
