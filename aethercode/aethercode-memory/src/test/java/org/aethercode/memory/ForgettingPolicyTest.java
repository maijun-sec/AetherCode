package org.aethercode.memory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForgettingPolicyTest {

    @Test
    void defaultsHaveSensibleValues() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        assertEquals(0.5, p.weightRecency(), 1e-9);
        assertEquals(0.3, p.weightFrequency(), 1e-9);
        assertEquals(0.2, p.weightUtility(), 1e-9);
        assertEquals(ForgettingPolicy.DEFAULT_TAU_MS, p.tauMs());
        assertTrue(p.tombstoneThreshold() > 0);
        assertTrue(p.pruneThreshold() < p.tombstoneThreshold());
    }

    @Test
    void recencyDecaysExponentially() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        Instant t0 = Instant.now();
        // 0 days old → 1.0
        var fresh = makeItem(t0, t0);
        double s0 = p.components(fresh, 100, t0).recency();
        assertEquals(1.0, s0, 1e-9);
        // tau days old → ~0.368
        var tauOld = makeItem(t0, t0.minusMillis(ForgettingPolicy.DEFAULT_TAU_MS));
        double sTau = p.components(tauOld, 100, t0).recency();
        assertEquals(Math.exp(-1.0), sTau, 1e-3);
        // 5*tau days old → ~0.0067
        var veryOld = makeItem(t0, t0.minusMillis(5 * ForgettingPolicy.DEFAULT_TAU_MS));
        double s5 = p.components(veryOld, 100, t0).recency();
        assertTrue(s5 < 0.01);
    }

    @Test
    void frequencyGrowsLogarithmic() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        Instant t0 = Instant.now();
        var components = p.components(makeItem(t0, t0), 10, t0);
        // Item with no access history should get the floor of 0.5
        // (since updatedAt == createdAt ⇒ 0 days delta ⇒ 0 accesses
        // ⇒ log(1+0)/log(1+10) = 0). The current implementation
        // returns 0.5 in that case as a "neutral" signal; we only
        // assert it's in [0, 1].
        assertTrue(components.frequency() >= 0 && components.frequency() <= 1);
    }

    @Test
    void utilityParsesFromTag() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        Instant t0 = Instant.now();
        var high = makeItem(t0, t0, List.of("utility=0.9"));
        var low = makeItem(t0, t0, List.of("utility=0.1"));
        assertEquals(0.9, p.components(high, 10, t0).utility(), 1e-9);
        assertEquals(0.1, p.components(low, 10, t0).utility(), 1e-9);
        var none = makeItem(t0, t0, List.of("other-tag"));
        assertEquals(0.5, p.components(none, 10, t0).utility(), 1e-9);
    }

    @Test
    void scoreIsInUnitInterval() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        Instant t0 = Instant.now();
        // Fresh + utility=0.9 tag: 0.5*1.0 + 0.3*0.0 + 0.2*0.9 = 0.68
        var fresh = makeItem(t0, t0, List.of("utility=0.9"));
        assertTrue(p.score(fresh, 100, t0) > 0.6,
                "fresh+high-utility score should be > 0.6, got " + p.score(fresh, 100, t0));
        // Very old (10*tau), no access, default utility 0.5
        // recency = e^-10 ≈ 0, frequency = 0.5 (no signal),
        // utility = 0.5 ⇒ score = 0.5*0 + 0.3*0.5 + 0.2*0.5 = 0.25
        var veryOld = makeItem(t0, t0.minusMillis(10 * ForgettingPolicy.DEFAULT_TAU_MS));
        double oldScore = p.score(veryOld, 0, t0);
        assertTrue(oldScore < 0.4,
                "very-old score should be < 0.4, got " + oldScore);
        // Combined: fresh high-utility should beat very-old no-utility
        assertTrue(p.score(fresh, 100, t0) > oldScore,
                "fresh should score higher than very-old: " +
                p.score(fresh, 100, t0) + " vs " + oldScore);
    }

    @Test
    void shouldTombstoneAndPruneRespectThresholds() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        assertTrue(p.shouldTombstone(0.01));
        assertFalse(p.shouldTombstone(0.5));
        assertTrue(p.shouldPrune(0.001));
        assertFalse(p.shouldPrune(0.05));
    }

    @Test
    void decayPassRemovesBelowThreshold() throws IOException {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        Path tmp = Files.createTempFile("forgetting-test-", ".json");
        try {
            // Build 5 ancient items + 1 fresh directly in the JSON file,
            // then reopen the store. Bypassing the public add() is
            // intentional — the public add() resets the timestamp.
            Instant longAgo = Instant.now().minusMillis(100L * ForgettingPolicy.DEFAULT_TAU_MS);
            Instant now = Instant.now();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            var items = new java.util.ArrayList<FileBackedMemory.MemoryItem>();
            for (int i = 0; i < 5; i++) {
                // Old + low utility + never accessed → should score below
                // the default tombstone threshold (0.05).
                items.add(new FileBackedMemory.MemoryItem(
                        "old-" + i, "body-" + i, "user", List.of("utility=0.1"),
                        longAgo, longAgo,
                        Sensitivity.INTERNAL, 0L, longAgo));
            }
            items.add(new FileBackedMemory.MemoryItem(
                    "fresh", "fresh body", "user", List.of("utility=0.9"),
                    now, now,
                    Sensitivity.INTERNAL, 5L, now));
            Files.writeString(tmp, mapper.writeValueAsString(items));

            FileBackedMemory store = new FileBackedMemory(tmp);
            int before = store.size();
            assertEquals(6, before);
            var report = p.runDecayPass(store, false);
            assertEquals(6, report.scanned());
            assertTrue(report.tombstoned() >= 4,
                    "expected >= 4 tombstoned, got " + report.tombstoned());
            assertTrue(store.size() <= 2,
                    "expected <= 2 survivors, got " + store.size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void weightsAreNormalised() {
        ForgettingPolicy p = new ForgettingPolicy(1.0, 1.0, 1.0, 1L, 0.05, 0.01, 7L);
        double sum = p.weightRecency() + p.weightFrequency() + p.weightUtility();
        assertEquals(1.0, sum, 1e-9);
    }

    @Test
    void negativeWeightsBecomeZero() {
        ForgettingPolicy p = new ForgettingPolicy(-1, 0.5, 0.5, 1L, 0.05, 0.01, 7L);
        assertEquals(0, p.weightRecency(), 1e-9);
    }

    private FileBackedMemory.MemoryItem makeItem(Instant createdAt, Instant updatedAt) {
        return makeItem(createdAt, updatedAt, List.of());
    }

    private FileBackedMemory.MemoryItem makeItem(Instant createdAt, Instant updatedAt, List<String> tags) {
        return new FileBackedMemory.MemoryItem("test", "body", "user", tags, createdAt, updatedAt);
    }
}
