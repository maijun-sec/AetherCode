package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankGrowthTest {

    private static ReasoningUnit unit(String id, double utility) {
        return new ReasoningUnit(id, "k", "e", "f", "", utility, 0L, Instant.now());
    }

    // -----------------------------------------------------------------
    //  NoGrowthCap (default)
    // -----------------------------------------------------------------

    @Test
    void noGrowthCapAllowsUnboundedGrowth() {
        ReasoningBank bank = new ReasoningBank();
        for (int i = 0; i < 50; i++) {
            bank.add(unit("u" + i, 0.5));
        }
        assertEquals(50, bank.size());
        assertEquals(0, bank.evictIfNeeded());
    }

    // -----------------------------------------------------------------
    //  UtilityBasedEviction
    // -----------------------------------------------------------------

    @Test
    void utilityBasedEvictionTrimsAboveCap() {
        UtilityBasedEviction policy = new UtilityBasedEviction(3, UtilityDecay.NO_DECAY);
        ReasoningBank bank = new ReasoningBank(
                new InMemoryBankStorage(), UtilityDecay.NO_DECAY, policy);
        bank.add(unit("a", 0.9));
        bank.add(unit("b", 0.5));
        bank.add(unit("c", 0.3));
        assertEquals(3, bank.size());
        // Adding a 4th triggers eviction of the lowest-utility unit.
        bank.add(unit("d", 0.7));
        assertEquals(3, bank.size(),
                "bank should be capped at maxUnits=3 after add");
        assertTrue(bank.contains("a"), "high-utility unit should survive");
        assertTrue(bank.contains("d"), "newly added unit should survive");
        assertFalse(bank.contains("c"),
                "lowest-utility unit should be evicted");
    }

    @Test
    void utilityBasedEvictionUsesEffectiveUtilityUnderDecay() {
        // Two units at equal stored utility 0.5; the older one
        // should rank lower under exponential decay.
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = t0.plus(Duration.ofDays(7));
        ReasoningUnit oldUnit = new ReasoningUnit("old", "k", "e", "f", "",
                0.5, 0L, t0);
        ReasoningUnit newUnit = new ReasoningUnit("new", "k", "e", "f", "",
                0.5, 0L, t1);

        UtilityBasedEviction policy = new UtilityBasedEviction(
                1, UtilityDecay.exponential(Duration.ofDays(7)));
        List<ReasoningUnit> snapshot = List.of(oldUnit, newUnit);
        List<ReasoningUnit> toEvict = policy.selectEvictions(2, snapshot, t1);
        assertEquals(1, toEvict.size());
        assertEquals("old", toEvict.get(0).id(),
                "older unit should rank lower under decay");
    }

    @Test
    void utilityBasedEvictionRejectsNonPositiveMax() {
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityBasedEviction(0, UtilityDecay.NO_DECAY));
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityBasedEviction(-1, UtilityDecay.NO_DECAY));
    }

    @Test
    void utilityBasedEvictionKeepsAtOrBelowCap() {
        // maxUnits=5, no batch. Add 10 units one at a time;
        // the bank should always end up at exactly 5 (each
        // add triggers an eviction of the weakest).
        UtilityBasedEviction policy = new UtilityBasedEviction(
                5, UtilityDecay.NO_DECAY);
        ReasoningBank bank = new ReasoningBank(
                new InMemoryBankStorage(), UtilityDecay.NO_DECAY, policy);
        for (int i = 0; i < 10; i++) {
            bank.add(unit("u" + i, 0.1 + 0.05 * i));
        }
        assertEquals(5, bank.size(),
                "bank should be exactly at maxUnits; size=" + bank.size());
    }

    // -----------------------------------------------------------------
    //  LruEviction
    // -----------------------------------------------------------------

    @Test
    void lruEvictionDropsOldestTouchedFirst() throws Exception {
        LruEviction policy = new LruEviction(2);
        ReasoningBank bank = new ReasoningBank(
                new InMemoryBankStorage(), UtilityDecay.NO_DECAY, policy);
        bank.add(unit("a", 0.5));
        Thread.sleep(5);
        bank.add(unit("b", 0.5));
        Thread.sleep(5);
        bank.touch("a"); // 'a' becomes more recent
        bank.add(unit("c", 0.5));
        // 'b' is now the LRU; should be evicted.
        assertEquals(2, bank.size());
        assertTrue(bank.contains("a"));
        assertTrue(bank.contains("c"));
        assertFalse(bank.contains("b"));
    }

    @Test
    void lruEvictionRejectsNonPositiveMax() {
        assertThrows(IllegalArgumentException.class, () -> new LruEviction(0));
        assertThrows(IllegalArgumentException.class, () -> new LruEviction(-5));
    }

    @Test
    void lruEvictionFallsBackToCreatedAtWhenNoTouchRecord() {
        // Manually craft a snapshot+map mismatch: snapshot
        // has units u1..u3, lastTouched has only u1. u2 and
        // u3 should fall back to createdAt.
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = t0.plus(Duration.ofDays(1));
        Instant t2 = t0.plus(Duration.ofDays(2));
        ReasoningUnit u1 = new ReasoningUnit("u1", "k", "e", "f", "", 0.5, 0L, t0);
        ReasoningUnit u2 = new ReasoningUnit("u2", "k", "e", "f", "", 0.5, 0L, t1);
        ReasoningUnit u3 = new ReasoningUnit("u3", "k", "e", "f", "", 0.5, 0L, t2);
        java.util.Map<String, Instant> touches = new java.util.HashMap<>();
        touches.put("u1", t2);
        LruEviction policy = new LruEviction(2);
        List<ReasoningUnit> toEvict = policy.selectEvictions(
                3, List.of(u1, u2, u3), touches);
        assertEquals(1, toEvict.size());
        // u2 is the oldest in createdAt among those without
        // an explicit touch; should be picked.
        assertEquals("u2", toEvict.get(0).id());
    }

    // -----------------------------------------------------------------
    //  Wiring
    // -----------------------------------------------------------------

    @Test
    void withFileStorageSupportsGrowthPolicy(@TempDir Path dir) {
        UtilityBasedEviction policy = new UtilityBasedEviction(
                2, UtilityDecay.NO_DECAY);
        ReasoningBank bank = ReasoningBank.withFileStorage(
                dir, UtilityDecay.NO_DECAY, policy);
        bank.add(unit("a", 0.9));
        bank.add(unit("b", 0.5));
        bank.add(unit("c", 0.3));
        assertEquals(2, bank.size());
        // The file storage should reflect the eviction.
        ReasoningBank bank2 = ReasoningBank.withFileStorage(
                dir, UtilityDecay.NO_DECAY, policy);
        assertEquals(2, bank2.size());
    }

    @Test
    void storageSeesEviction(@TempDir Path dir) {
        UtilityBasedEviction policy = new UtilityBasedEviction(
                1, UtilityDecay.NO_DECAY);
        ReasoningBank bank = new ReasoningBank(
                new JsonFileBankStorage(dir), UtilityDecay.NO_DECAY, policy);
        bank.add(unit("a", 0.9));
        bank.add(unit("b", 0.5));
        // After eviction only "a" should remain on disk.
        assertTrue(java.nio.file.Files.exists(dir.resolve("a.json")));
        assertFalse(java.nio.file.Files.exists(dir.resolve("b.json")));
    }

    @Test
    void defaultConstructorIsNoGrowthCap() {
        ReasoningBank bank = new ReasoningBank();
        assertEquals(0, bank.evictIfNeeded());
        for (int i = 0; i < 200; i++) {
            bank.add(unit("u" + i, 0.5));
        }
        assertEquals(200, bank.size());
    }
}
