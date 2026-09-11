package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R230 (G1): tests for the experience-layer additions on
 * {@link LayeredMemoryStore}.
 */
class LayeredMemoryStoreR230Test {

    @Test
    void userExperienceRoundTrip(@TempDir Path tmp) {
        try (var pair = buildStore(tmp)) {
            var rec = pair.store.appendUserExperience(
                    ExperienceKind.STRATEGY,
                    "tsc --strict",
                    "use --strict; ignore is not enough",
                    "sess-1", "ts compile fail", "success",
                    List.of("typescript"), List.of());
            assertNotNull(rec.id());
            var top = pair.store.listUserExperience(5);
            assertEquals(1, top.size());
            assertEquals(rec.id(), top.get(0).id());
        }
    }

    @Test
    void projectExperienceIsCwdScoped(@TempDir Path tmp) {
        // Use real temp subdirs so we don't pollute D:\proj or hit
        // Windows case-insensitive fs collisions.
        String cwdA = tmp.resolve("projA").toString();
        String cwdB = tmp.resolve("projB").toString();
        try (var pair = buildStore(tmp)) {
            var recA = pair.store.appendProjectExperience(
                    cwdA, ExperienceKind.CASE,
                    "build a", "body a", "s1", "q", "success",
                    List.of(), List.of());
            var recB = pair.store.appendProjectExperience(
                    cwdB, ExperienceKind.CASE,
                    "build b", "body b", "s1", "q", "success",
                    List.of(), List.of());
            var listA = pair.store.listProjectExperience(cwdA, 10);
            assertEquals(1, listA.size(), "projA should have 1 record");
            assertEquals(recA.id(), listA.get(0).id());
            var listB = pair.store.listProjectExperience(cwdB, 10);
            assertEquals(1, listB.size(), "projB should have 1 record");
            assertEquals(recB.id(), listB.get(0).id());
        }
    }

    @Test
    void recordExperienceUseFindsAcrossScopes(@TempDir Path tmp) {
        try (var pair = buildStore(tmp)) {
            var recU = pair.store.appendUserExperience(
                    ExperienceKind.CASE, "u", "x", null, null, "success", List.of(), List.of());
            Optional<ExperienceRecord> updated = pair.store.recordExperienceUse(null, recU.id());
            assertTrue(updated.isPresent());
            assertTrue(updated.get().uses() >= 1);
        }
    }

    @Test
    void projectExperiencePersistsAcrossReopen(@TempDir Path tmp) {
        String cwdX = tmp.resolve("projX").toString();
        try (var pair = buildStore(tmp)) {
            var rec = pair.store.appendProjectExperience(
                    cwdX, ExperienceKind.SKILL,
                    "MCP mavis", "use mavis cli for memory ops",
                    "s1", "q", "success",
                    List.of("mcp"), List.of());
            try (var pair2 = buildStore(tmp)) {
                var list = pair2.store.listProjectExperience(cwdX, 10);
                assertEquals(1, list.size());
                assertEquals(rec.id(), list.get(0).id());
            }
        }
    }

    @Test
    void listUserExperienceTopKSortedByUtility(@TempDir Path tmp) {
        try (var pair = buildStore(tmp)) {
            var lowU = pair.store.appendUserExperience(
                    ExperienceKind.CASE, "low", "x", null, null, "success", List.of(), List.of());
            var highU = pair.store.appendUserExperience(
                    ExperienceKind.CASE, "high", "x", null, null, "success", List.of(), List.of());
            for (int i = 0; i < 3; i++) {
                pair.store.recordExperienceUse(null, highU.id());
            }
            var top = pair.store.listUserExperience(2);
            assertEquals(2, top.size());
            assertEquals("high", top.get(0).title());
        }
    }

    @Test
    void recordExperienceUseUnknownIdReturnsEmpty(@TempDir Path tmp) {
        try (var pair = buildStore(tmp)) {
            assertTrue(pair.store.recordExperienceUse(tmp.resolve("xx").toString(), "no-such-id").isEmpty());
        }
    }

    // ---- helper ----

    private static final class StorePair implements AutoCloseable {
        final LayeredMemoryStore store;
        final SessionMemoryStore session;
        StorePair(LayeredMemoryStore s, SessionMemoryStore sess) {
            this.store = s; this.session = sess;
        }
        @Override public void close() { session.close(); }
    }

    private static StorePair buildStore(@TempDir Path tmp) {
        Path memoryBase = tmp.resolve("base");
        Path sessionDb = tmp.resolve("sessions.db");
        SessionMemoryStore session = new SessionMemoryStore(sessionDb);
        ProjectMemoryCompressor.ChatClient noop = prompt -> java.util.Optional.empty();
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(noop);
        LayeredMemoryStore store = new LayeredMemoryStore(
                memoryBase, "test-agent", session, compressor,
                50, 10, true);
        return new StorePair(store, session);
    }
}
