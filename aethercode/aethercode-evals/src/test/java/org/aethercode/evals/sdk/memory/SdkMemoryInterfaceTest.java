package org.aethercode.evals.sdk.memory;

import org.aethercode.memory.ExperienceKind;
import org.aethercode.memory.ExperienceRecord;
import org.aethercode.memory.FileBackedMemory;
import org.aethercode.memory.ForgettingPolicy;
import org.aethercode.memory.MemoryScope;
import org.aethercode.memory.Sensitivity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-3: AetherCode Memory Interface conformance.
 *
 * <p>Companion to {@code MemoryCapabilityTest} (R-eval-2). The capability
 * suite proves the memory design (episodic / semantic / procedural,
 * forgetting policy) on a self-contained model. This suite proves the
 * actual AetherCode {@code ExperienceRecord} + {@code ForgettingPolicy}
 * + {@code FileBackedMemory} classes — the ones the TUI memory panel
 * and the recall scorer use — behave correctly.</p>
 */
class SdkMemoryInterfaceTest {

    /* ---------------- ExperienceRecord ---------------- */

    @Test
    void experienceRecordAssignsIdWhenBlank() {
        ExperienceRecord rec = new ExperienceRecord(
                null, ExperienceKind.STRATEGY, "x", "body",
                Instant.now(), "sess", "q", "success",
                0.5, 0, List.of(), List.of());
        assertNotNull(rec.id());
        assertFalse(rec.id().isBlank());
    }

    @Test
    void experienceRecordUtilityClampedToZeroOne() {
        ExperienceRecord rec = new ExperienceRecord(
                "r1", ExperienceKind.SKILL, "t", "b",
                Instant.now(), "sess", "q", "success",
                1.7, 0, List.of(), List.of());
        assertTrue(rec.utility() <= 1.0, "utility clamped to 1, got " + rec.utility());
    }

    @Test
    void experienceRecordNegativeUsesClampedToZero() {
        ExperienceRecord rec = new ExperienceRecord(
                "r1", ExperienceKind.STRATEGY, "t", "b",
                Instant.now(), "sess", "q", "success",
                0.5, -10, List.of(), List.of());
        assertEquals(0L, rec.uses());
    }

    @Test
    void experienceRecordWithUseIsAsymptoticToOne() {
        ExperienceRecord rec = new ExperienceRecord(
                "r1", ExperienceKind.CASE, "t", "b",
                Instant.now(), "sess", "q", "success",
                0.0, 0, List.of(), List.of());
        ExperienceRecord r1 = rec.withUse();
        ExperienceRecord r2 = r1.withUse();
        ExperienceRecord r10 = rec;
        for (int i = 0; i < 10; i++) r10 = r10.withUse();
        // first hit: 0.0 + 0.1*(1-0.0) = 0.1
        assertTrue(r1.utility() > 0.0);
        assertTrue(r1.utility() <= 0.1, "first use bumps utility to 0.1, got " + r1.utility());
        // tenth hit should be approaching 1 but not reach it
        assertTrue(r10.utility() > 0.5);
        assertTrue(r10.utility() < 1.0);
        // monotone non-decreasing
        assertTrue(r1.utility() <= r2.utility());
        assertTrue(r2.utility() <= r10.utility());
    }

    @Test
    void experienceRecordTagsAreCopied() {
        java.util.List<String> mutable = new java.util.ArrayList<>();
        mutable.add("a");
        ExperienceRecord rec = new ExperienceRecord(
                "r1", ExperienceKind.STRATEGY, "t", "b",
                Instant.now(), "sess", "q", "success",
                0.5, 0, mutable, List.of());
        mutable.add("z");
        // external mutation must not affect the record.
        assertEquals(List.of("a"), rec.tags());
    }

    /* ---------------- ForgettingPolicy: weights & thresholds ---------------- */

    @Test
    void forgettingPolicyDefaultsAreNormalised() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        double sum = p.weightRecency() + p.weightFrequency() + p.weightUtility();
        assertEquals(1.0, sum, 0.001, "weights should sum to 1, got " + sum);
    }

    @Test
    void forgettingPolicyCustomWeightsNormalise() {
        // 5/3/2 raw -> normalised to 0.5/0.3/0.2.
        ForgettingPolicy p = new ForgettingPolicy(5, 3, 2,
                ForgettingPolicy.DEFAULT_TAU_MS,
                ForgettingPolicy.DEFAULT_TOMBSTONE_THRESHOLD,
                ForgettingPolicy.DEFAULT_PRUNE_THRESHOLD,
                ForgettingPolicy.DEFAULT_TOMBSTONE_DAYS);
        assertEquals(0.5, p.weightRecency(), 0.001);
        assertEquals(0.3, p.weightFrequency(), 0.001);
        assertEquals(0.2, p.weightUtility(), 0.001);
    }

    @Test
    void forgettingPolicyTombstoneAndPruneThresholds() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        assertTrue(p.shouldTombstone(0.01));
        assertTrue(p.shouldPrune(0.001));
        assertFalse(p.shouldTombstone(0.5));
        assertFalse(p.shouldPrune(0.5));
    }

    /* ---------------- ForgettingPolicy: scoring ---------------- */

    @Test
    void forgettingPolicyFreshItemScoresHigherThanStale() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        Instant now = Instant.now();
        FileBackedMemory.MemoryItem fresh = new FileBackedMemory.MemoryItem(
                "id1", "c", "scope", List.of(), now, now,
                Sensitivity.PUBLIC, 0, now);
        FileBackedMemory.MemoryItem stale = new FileBackedMemory.MemoryItem(
                "id2", "c", "scope", List.of(),
                now.minusSeconds(60L * 24 * 3600), // 60 days ago
                now.minusSeconds(60L * 24 * 3600),
                Sensitivity.PUBLIC, 0, now);
        double sFresh = p.score(fresh, 100, now);
        double sStale = p.score(stale, 100, now);
        assertTrue(sFresh > sStale, "fresh score should beat stale, got fresh=" + sFresh + " stale=" + sStale);
        assertTrue(sFresh > 0.5, "fresh item should be well above tombstone");
    }

    @Test
    void forgettingPolicyUtilityTagLiftsScore() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        Instant now = Instant.now();
        FileBackedMemory.MemoryItem baseline = new FileBackedMemory.MemoryItem(
                "id1", "c", "scope", List.of(), now, now,
                Sensitivity.PUBLIC, 0, now);
        FileBackedMemory.MemoryItem withUtilityTag = new FileBackedMemory.MemoryItem(
                "id2", "c", "scope", List.of("utility=0.95"), now, now,
                Sensitivity.PUBLIC, 0, now);
        ForgettingPolicy.Components c1 = p.components(baseline, 100, now);
        ForgettingPolicy.Components c2 = p.components(withUtilityTag, 100, now);
        assertEquals(0.5, c1.utility(), 0.001, "no utility tag -> default 0.5");
        assertEquals(0.95, c2.utility(), 0.001, "utility=0.95 tag -> 0.95");
    }

    /* ---------------- FileBackedMemory.MemoryItem immutability ---------------- */

    @Test
    void memoryItemTagsAreCopied() {
        java.util.List<String> mutable = new java.util.ArrayList<>();
        mutable.add("a");
        FileBackedMemory.MemoryItem item = new FileBackedMemory.MemoryItem(
                "id", "c", "scope", mutable,
                Instant.now(), Instant.now(),
                Sensitivity.PUBLIC, 0, Instant.now());
        mutable.add("z");
        assertEquals(List.of("a"), item.tags());
    }

    /* ---------------- MemoryScope + ExperienceKind cross-product ---------------- */

    @Test
    void memoryScopeAndExperienceKindAreDistinct() {
        // Sanity: the two enums are independent, both are usable
        // in different combinations without throwing.
        MemoryScope scope = MemoryScope.PROJECT;
        ExperienceKind kind = ExperienceKind.SKILL;
        assertNotNull(scope.name());
        assertNotNull(kind.name());
        // the record pair doesn't need a constructor argument,
        // but the front-end can pair them freely.
        assertNotEquals(scope.name(), kind.name());
    }
}
