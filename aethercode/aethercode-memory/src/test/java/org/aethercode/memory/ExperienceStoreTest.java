package org.aethercode.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExperienceStoreTest {

    private Path tmp;

    @BeforeEach
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("experience-store-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmp != null) {
            Files.walk(tmp)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
        }
    }

    @Test
    void putAndGetRoundTrip() {
        ExperienceStore store = new ExperienceStore(tmp);
        ExperienceRecord rec = store.put(ExperienceKind.CASE, "maven-build",
                "ran `mvn -B install`, succeeded in 42s",
                "sess-1", "how to build?", "success",
                List.of("maven"), List.of());
        Optional<ExperienceRecord> got = store.get(rec.id());
        assertTrue(got.isPresent());
        assertEquals("maven-build", got.get().title());
        assertEquals(ExperienceKind.CASE, got.get().kind());
        assertEquals("sess-1", got.get().sourceSessionId());
        assertEquals(List.of("maven"), got.get().tags());
    }

    @Test
    void filePerRecordPersistsAcrossReopen() {
        ExperienceStore store = new ExperienceStore(tmp);
        ExperienceRecord rec = store.put(ExperienceKind.STRATEGY, "ts-strict",
                "always pass --strict to tsc; ignore is not enough",
                "sess-1", "ts compile fail", "success",
                List.of("typescript"), List.of());
        // Re-open
        ExperienceStore reopened = new ExperienceStore(tmp);
        assertEquals(1, reopened.size());
        assertTrue(reopened.get(rec.id()).isPresent());
    }

    @Test
    void recordUseLiftsUtility() {
        ExperienceStore store = new ExperienceStore(tmp);
        ExperienceRecord rec = store.put(ExperienceKind.CASE, "x", "body", null, null, "success", List.of(), List.of());
        double u0 = rec.utility();
        ExperienceRecord u1 = store.recordUse(rec.id());
        assertNotNull(u1);
        assertTrue(u1.utility() > u0);
        assertEquals(1L, u1.uses());
        ExperienceRecord u2 = store.recordUse(rec.id());
        assertTrue(u2.utility() >= u1.utility());
        assertEquals(2L, u2.uses());
    }

    @Test
    void topKSortsByUtilityThenUses() {
        ExperienceStore store = new ExperienceStore(tmp);
        ExperienceRecord a = store.put(ExperienceKind.CASE, "a", "x", null, null, "success", List.of(), List.of());
        ExperienceRecord b = store.put(ExperienceKind.CASE, "b", "x", null, null, "success", List.of(), List.of());
        ExperienceRecord c = store.put(ExperienceKind.CASE, "c", "x", null, null, "success", List.of(), List.of());
        // Use b 3 times, a 1 time
        store.recordUse(a.id());
        store.recordUse(b.id());
        store.recordUse(b.id());
        store.recordUse(b.id());
        List<ExperienceRecord> top = store.topK(2, null);
        assertEquals(2, top.size());
        assertEquals(b.id(), top.get(0).id());
        assertEquals(a.id(), top.get(1).id());
    }

    @Test
    void tagFilterIntersects() {
        ExperienceStore store = new ExperienceStore(tmp);
        store.put(ExperienceKind.CASE, "a", "x", null, null, "success", List.of("maven"), List.of());
        store.put(ExperienceKind.CASE, "b", "x", null, null, "success", List.of("gradle"), List.of());
        List<ExperienceRecord> maven = store.topK(10, List.of("maven"));
        assertEquals(1, maven.size());
        assertEquals("a", maven.get(0).title());
    }

    @Test
    void removeDeletesFileAndCache() {
        ExperienceStore store = new ExperienceStore(tmp);
        ExperienceRecord rec = store.put(ExperienceKind.CASE, "a", "x", null, null, "success", List.of(), List.of());
        assertEquals(1, store.size());
        assertTrue(store.remove(rec.id()));
        assertEquals(0, store.size());
        // Re-open should also see 0
        ExperienceStore reopened = new ExperienceStore(tmp);
        assertEquals(0, reopened.size());
    }

    @Test
    void statsByKindCountsPerKind() {
        ExperienceStore store = new ExperienceStore(tmp);
        store.put(ExperienceKind.CASE, "a", "x", null, null, "success", List.of(), List.of());
        store.put(ExperienceKind.CASE, "b", "x", null, null, "success", List.of(), List.of());
        store.put(ExperienceKind.STRATEGY, "c", "x", null, null, "success", List.of(), List.of());
        var stats = store.statsByKind();
        assertEquals(2L, stats.get("case"));
        assertEquals(1L, stats.get("strategy"));
    }

    @Test
    void recordUseOnUnknownIdReturnsNull() {
        ExperienceStore store = new ExperienceStore(tmp);
        assertNull(store.recordUse(UUID.randomUUID().toString()));
    }
}
