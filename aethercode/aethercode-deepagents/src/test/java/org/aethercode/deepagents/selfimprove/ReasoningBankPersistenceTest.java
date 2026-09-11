package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningBankPersistenceTest {

    @Test
    void defaultConstructorUsesInMemoryAndNoDecay() {
        ReasoningBank bank = new ReasoningBank();
        // NoDecay means stored utility == effective utility.
        ReasoningUnit u = bank.add(ReasoningUnit.of("k1", "err", "fix", "ex"));
        // Close the bank; the data is gone.
        assertEquals(1, bank.size());

        ReasoningBank bank2 = new ReasoningBank();
        assertEquals(0, bank2.size(),
                "default in-memory bank should NOT persist across instances");
        // And the new bank does not see the old unit by id.
        assertTrue(bank2.get(u.id()).isEmpty());
    }

    @Test
    void withFileStoragePersistsAcrossInstances(@TempDir Path dir) {
        ReasoningBank bank1 = ReasoningBank.withFileStorage(dir);
        ReasoningUnit u1 = bank1.add(ReasoningUnit.of("k1", "errA", "fixA", "exA"));
        ReasoningUnit u2 = bank1.add(ReasoningUnit.of("k2", "errB", "fixB", "exB"));
        assertEquals(2, bank1.size());

        // New bank pointing at the same directory = "new session"
        ReasoningBank bank2 = ReasoningBank.withFileStorage(dir);
        assertEquals(2, bank2.size(),
                "second bank on the same dir should see the persisted units");
        Optional<ReasoningUnit> loadedU1 = bank2.get(u1.id());
        assertTrue(loadedU1.isPresent());
        assertEquals(u1.id(), loadedU1.get().id());
        assertEquals("errA", loadedU1.get().errorPattern());
        assertEquals("fixA", loadedU1.get().fixStrategy());

        Optional<ReasoningUnit> loadedU2 = bank2.get(u2.id());
        assertTrue(loadedU2.isPresent());
        assertEquals("k2", loadedU2.get().taskKind());
    }

    @Test
    void touchUpdatesPersistedUtility(@TempDir Path dir) {
        ReasoningBank bank1 = ReasoningBank.withFileStorage(dir);
        ReasoningUnit u = bank1.add(ReasoningUnit.of("k1", "err", "fix", "ex"));
        assertEquals(0.5, u.utility(), 1e-9);

        bank1.touch(u.id());
        bank1.touch(u.id());
        Optional<ReasoningUnit> after = bank1.get(u.id());
        assertTrue(after.isPresent());
        assertEquals(2L, after.get().uses());
        // 0.5 → 0.55 → 0.60
        assertEquals(0.60, after.get().utility(), 1e-9);

        // New bank on the same dir sees the bumped value.
        ReasoningBank bank2 = ReasoningBank.withFileStorage(dir);
        Optional<ReasoningUnit> reloaded = bank2.get(u.id());
        assertTrue(reloaded.isPresent());
        assertEquals(0.60, reloaded.get().utility(), 1e-9);
        assertEquals(2L, reloaded.get().uses());
    }

    @Test
    void exponentialDecayAffectsRecallRanking(@TempDir Path dir) {
        // Two units, one created 7 days ago at 0.9, one created
        // now at 0.5. With exponential half-life = 7 days, the
        // effective utility of the older one (0.45) should be
        // lower than the new one (0.5), so the new one wins
        // recall.
        Instant now = Instant.parse("2026-01-08T00:00:00Z");
        Instant aWeekAgo = now.minus(Duration.ofDays(7));

        ReasoningUnit oldUnit = new ReasoningUnit("old", "k1", "e1", "f1", "",
                0.9, 0L, aWeekAgo);
        ReasoningUnit newUnit = new ReasoningUnit("new", "k1", "e2", "f2", "",
                0.5, 0L, now);

        ReasoningBank bank = new ReasoningBank(
                new InMemoryBankStorage(),
                UtilityDecay.exponential(Duration.ofDays(7)));
        bank.add(oldUnit);
        bank.add(newUnit);

        List<ReasoningUnit> top = bank.recallFor("k1", 5, now);
        assertEquals(2, top.size());
        // New unit should rank first because the old one's
        // effective utility (0.45) is now lower than the new
        // one's (0.5).
        assertEquals("new", top.get(0).id());
        assertEquals("old", top.get(1).id());
    }

    @Test
    void decayPassPersistsLowerUtilities(@TempDir Path dir) {
        // Build a unit 7 days in the past. Run a decay pass at
        // "now"; the stored utility should drop to half, and
        // the new value should be on disk.
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = t0.plus(Duration.ofDays(7));
        ReasoningUnit oldUnit = new ReasoningUnit("u1", "k1", "e", "f", "",
                1.0, 0L, t0);

        ReasoningBank bank = ReasoningBank.withFileStorage(
                dir,
                UtilityDecay.exponential(Duration.ofDays(7)));
        bank.add(oldUnit);

        int changed = bank.decayPass(t1);
        assertEquals(1, changed, "decay pass should rewrite the unit");
        Optional<ReasoningUnit> reloaded = bank.get("u1");
        assertTrue(reloaded.isPresent());
        assertEquals(0.5, reloaded.get().utility(), 1e-3);

        // Reload from disk to confirm the change is persisted.
        ReasoningBank bank2 = ReasoningBank.withFileStorage(
                dir,
                UtilityDecay.exponential(Duration.ofDays(7)));
        Optional<ReasoningUnit> reloaded2 = bank2.get("u1");
        assertTrue(reloaded2.isPresent());
        assertEquals(0.5, reloaded2.get().utility(), 1e-3);
    }

    @Test
    void decayPassNoDecayIsZeroOp(@TempDir Path dir) {
        ReasoningBank bank = ReasoningBank.withFileStorage(dir);
        ReasoningUnit u = bank.add(ReasoningUnit.of("k1", "e", "f", "x"));
        // NoDecay: stored utility == effective utility; the
        // decay pass should rewrite nothing.
        int changed = bank.decayPass(Instant.now().plus(Duration.ofDays(30)));
        assertEquals(0, changed);
        Optional<ReasoningUnit> still = bank.get(u.id());
        assertTrue(still.isPresent());
        assertEquals(0.5, still.get().utility(), 1e-9);
    }

    @Test
    void withFileStorageAndExponentialRecoversAcrossInstances(@TempDir Path dir) {
        // Session 1: write a high-utility unit.
        ReasoningBank b1 = ReasoningBank.withFileStorage(
                dir,
                UtilityDecay.exponential(Duration.ofDays(7)));
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = new ReasoningUnit("u1", "k1", "e", "f", "x",
                0.9, 0L, t0);
        b1.add(u);

        // Session 2: same dir, much later. Recall ranks
        // according to decayed utility.
        ReasoningBank b2 = ReasoningBank.withFileStorage(
                dir,
                UtilityDecay.exponential(Duration.ofDays(7)));
        Instant t1 = t0.plus(Duration.ofDays(14)); // 2 half-lives
        List<ReasoningUnit> top = b2.recallFor("k1", 3, t1);
        assertEquals(1, top.size());
        // Stored utility 0.9, 2 half-lives → 0.225
        // We can't see the effective value directly, but the
        // round-trip works (unit is present and recallable).
        assertNotNull(top.get(0));
        assertEquals("u1", top.get(0).id());
    }

    @Test
    void jsonFileStorageAutoPersistsAddAndTouch(@TempDir Path dir) {
        // Drive the underlying storage directly to confirm the
        // "every add is on disk by the time add() returns" contract.
        JsonFileBankStorage storage = new JsonFileBankStorage(dir);
        ReasoningBank bank = new ReasoningBank(storage);
        bank.add(ReasoningUnit.of("k1", "e", "f", "x"));
        // Storage should have the unit without any flush().
        assertEquals(1, storage.loadAll().size());
    }
}
