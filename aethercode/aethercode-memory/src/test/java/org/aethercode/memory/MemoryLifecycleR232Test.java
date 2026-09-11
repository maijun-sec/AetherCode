package org.aethercode.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for the new MemoryLifecycle methods that close the
 * "write but never read" gap (EXPERIENCE recall, SESSION k/v recall)
 * and the {@code MemoryExtractor} wire-in.
 */
class MemoryLifecycleR232Test {

    private Path tmp;
    private LayeredMemoryStore store;
    private SessionMemoryStore session;
    private MemoryAudit audit;
    private MemoryLifecycle lifecycle;
    private Path projectCwd;

    @BeforeEach
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("memory-lifecycle-r232-");
        Path memoryBase = tmp.resolve("base");
        Path sessionDb = tmp.resolve("sessions.db");
        session = new SessionMemoryStore(sessionDb);
        ProjectMemoryCompressor.ChatClient noop = p -> java.util.Optional.empty();
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(noop);
        store = new LayeredMemoryStore(memoryBase, "test-agent", session, compressor,
                50, 10, true);
        audit = new MemoryAudit(tmp.resolve("audit.log"));
        audit.setEnabled(true);
        MemoryAudit.setInstanceForTesting(audit);
        projectCwd = tmp.resolve("projA");
        Files.createDirectories(projectCwd);
        MemoryLifecycle.Config cfg = new MemoryLifecycle.Config(
                true, 50L /*decayIntervalMs*/, 100, 2000, 1500, 1, true, true);
        lifecycle = new MemoryLifecycle("sess-r232", "test-agent", store,
                ForgettingPolicy.defaults(), audit, cfg);
        lifecycle.setProjectCwd(projectCwd.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (lifecycle != null) lifecycle.stop();
        MemoryAudit.clearForTesting();
        if (session != null) session.close();
        if (tmp != null) {
            Files.walk(tmp)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
        }
    }

    @Test
    void recallExperienceEmptyStoreReturnsEmpty() {
        var hits = lifecycle.recallExperience("any query", 3);
        assertNotNull(hits);
        assertTrue(hits.isEmpty());
    }

    @Test
    void recallExperienceReturnsRelevantByTokenOverlap() {
        // Seed 2 cases: one about Maven, one about Python
        store.appendUserExperience(ExperienceKind.CASE, "maven-build",
                "ran mvn -B install, succeeded in 42s", "s1", "how to build", "success",
                List.of("maven"), List.of());
        store.appendUserExperience(ExperienceKind.CASE, "python-pip",
                "use pip install -r requirements.txt", "s1", "how to install python deps",
                "success", List.of("python"), List.of());
        // Ask about Maven — should return the Maven one
        var hits = lifecycle.recallExperience("how to build with maven", 3);
        assertFalse(hits.isEmpty(), "should find maven experience");
        assertEquals("maven-build", hits.get(0).title());
    }

    @Test
    void recallExperienceMergesUserAndProjectScopes() {
        store.appendUserExperience(ExperienceKind.CASE, "user-experience",
                "user-scope content about maven", "s1", "q", "success", List.of(), List.of());
        store.appendProjectExperience(projectCwd.toString(),
                ExperienceKind.CASE, "project-experience",
                "project-scope content about maven", "s1", "q", "success",
                List.of(), List.of());
        var hits = lifecycle.recallExperience("maven build", 5);
        assertEquals(2, hits.size(), "should merge user + project");
        // Project gets first-slot priority per R232 design
        assertEquals("project-experience", hits.get(0).title(),
                "project experience should come first (current-project context)");
    }

    @Test
    void recallExperienceBumpsUtilityOnHit() {
        var rec = store.appendUserExperience(ExperienceKind.CASE, "hot",
                "maven build summary", "s1", "q", "success",
                List.of("maven"), List.of());
        assertEquals(0L, rec.uses());
        // First recall — uses=1
        lifecycle.recallExperience("maven", 3);
        var reloaded = store.listUserExperience(5).stream()
                .filter(r -> r.id().equals(rec.id())).findFirst().orElseThrow();
        assertEquals(1L, reloaded.uses());
        // Second recall — uses=2
        lifecycle.recallExperience("maven", 3);
        var reloaded2 = store.listUserExperience(5).stream()
                .filter(r -> r.id().equals(rec.id())).findFirst().orElseThrow();
        assertEquals(2L, reloaded2.uses());
    }

    @Test
    void recallExperienceAuditLogsHit() throws Exception {
        store.appendUserExperience(ExperienceKind.CASE, "x",
                "maven build content", "s1", "q", "success", List.of(), List.of());
        lifecycle.recallExperience("maven", 3);
        var log = tmp.resolve("audit.log");
        assertTrue(Files.exists(log));
        String content = Files.readString(log);
        assertTrue(content.contains("\"kind\":\"experience\""),
                "audit should log experience recall: " + content);
    }

    @Test
    void recallExperienceRespectsKCap() {
        for (int i = 0; i < 10; i++) {
            store.appendUserExperience(ExperienceKind.CASE, "exp-" + i,
                    "maven build content " + i, "s1", "q", "success",
                    List.of(), List.of());
        }
        var hits = lifecycle.recallExperience("maven", 2);
        assertTrue(hits.size() <= 6, "k=2 should cap at 2*3=6 max, got " + hits.size());
    }

    @Test
    void recallSessionKvEmptySessionReturnsEmpty() {
        var hits = lifecycle.recallSessionKv("nonexistent", "any query", 16);
        assertNotNull(hits);
        assertTrue(hits.isEmpty());
    }

    @Test
    void recallSessionKvFindsRelevantByTokenOverlap() {
        // Seed some session facts
        store.putSession("sess-1", "build_cmd", "mvn -B install -DskipTests");
        store.putSession("sess-1", "test_framework", "junit 5");
        store.putSession("sess-1", "package_manager", "npm");
        var hits = lifecycle.recallSessionKv("sess-1", "how to run the build?", 16);
        assertFalse(hits.isEmpty(), "should find build_cmd");
        boolean hasBuild = hits.stream().anyMatch(e -> "build_cmd".equals(e.key()));
        assertTrue(hasBuild, "should include build_cmd");
    }

    @Test
    void recallSessionKvFallsBackToMostRecentOnNoOverlap() {
        // No overlap between user input and stored facts
        store.putSession("sess-1", "favorite_color", "blue");
        store.putSession("sess-1", "lunch_preference", "sushi");
        var hits = lifecycle.recallSessionKv("sess-1", "explain quantum mechanics", 16);
        // No overlap → should fall back to most recent (returns at least 1)
        assertFalse(hits.isEmpty(),
                "should fall back to returning recent entries on no overlap");
    }

    @Test
    void recallSessionKvRespectsKCap() {
        for (int i = 0; i < 20; i++) {
            store.putSession("sess-1", "key-" + i, "value about maven " + i);
        }
        var hits = lifecycle.recallSessionKv("sess-1", "maven", 3);
        assertTrue(hits.size() <= 3, "k=3 should cap, got " + hits.size());
    }

    @Test
    void recallSessionKvNullSessionIdReturnsEmpty() {
        var hits = lifecycle.recallSessionKv(null, "any", 16);
        assertTrue(hits.isEmpty());
        var hits2 = lifecycle.recallSessionKv("", "any", 16);
        assertTrue(hits2.isEmpty());
    }

    @Test
    void renderExperienceSectionEmpty() {
        assertEquals("", MemoryLifecycle.renderExperienceSection(null));
        assertEquals("", MemoryLifecycle.renderExperienceSection(List.of()));
    }

    @Test
    void renderExperienceSectionProducesMarkdown() {
        var rec = new ExperienceRecord("id-1", ExperienceKind.CASE, "maven-build",
                "ran mvn -B install", java.time.Instant.now(),
                "s1", "q", "success", 0.5, 0L,
                List.of("maven"), List.of());
        String s = MemoryLifecycle.renderExperienceSection(List.of(rec));
        assertTrue(s.contains("## Past experience"));
        assertTrue(s.contains("maven-build"));
        assertTrue(s.contains("[case]"));
        assertTrue(s.contains("ran mvn -B install"));
    }

    @Test
    void renderSessionKvSectionEmpty() {
        assertEquals("", MemoryLifecycle.renderSessionKvSection(null));
        assertEquals("", MemoryLifecycle.renderSessionKvSection(List.of()));
    }

    @Test
    void renderSessionKvSectionProducesMarkdown() {
        var entry = new SessionMemoryStore.MemoryEntry(
                "build_cmd", "mvn -B install",
                System.currentTimeMillis(), System.currentTimeMillis());
        String s = MemoryLifecycle.renderSessionKvSection(List.of(entry));
        assertTrue(s.contains("## Session facts"));
        assertTrue(s.contains("**build_cmd**"));
        assertTrue(s.contains("mvn -B install"));
    }

    @Test
    void renderSessionKvSectionTruncatesLongValues() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append("verylongcontent");
        var entry = new SessionMemoryStore.MemoryEntry(
                "huge", big.toString(),
                System.currentTimeMillis(), System.currentTimeMillis());
        String s = MemoryLifecycle.renderSessionKvSection(List.of(entry));
        assertTrue(s.contains("…"), "long value should be truncated with ellipsis");
    }

    @Test
    void setMemoryExtractorStoresAndRetrieves() {
        var extractor = new org.aethercode.memory.MemoryExtractor(
                (org.aethercode.core.llm.ChatClient) null,
                tmp.resolve("session-mem"));
        lifecycle.setMemoryExtractor(extractor);
        assertSame(extractor, lifecycle.memoryExtractor().orElseThrow());
    }

    @Test
    void setMemoryExtractorNullIsNoOp() {
        lifecycle.setMemoryExtractor(new org.aethercode.memory.MemoryExtractor(
                (org.aethercode.core.llm.ChatClient) null, tmp.resolve("x")));
        // Setting null should be a no-op, not replace with null
        lifecycle.setMemoryExtractor(null);
        assertTrue(lifecycle.memoryExtractor().isPresent(),
                "setting null should be no-op when one was already set");
    }

    @Test
    void onQueryEndWithoutExtractorDoesNotFail() {
        // No extractor wired — should not throw
        lifecycle.onQueryStart("q");
        lifecycle.onQueryEnd(true, List.of(), null);
        // No assertion needed — if it didn't throw, the test passes
    }

    @Test
    void onQueryEndExtractorFailsSilently() {
        // Extractor with null chatClient + empty messages —
        // shouldExtract returns false on empty list, extract should
        // not be called, no throw
        var extractor = new org.aethercode.memory.MemoryExtractor(
                (org.aethercode.core.llm.ChatClient) null, tmp.resolve("session-mem"));
        lifecycle.setMemoryExtractor(extractor);
        lifecycle.onQueryStart("q");
        lifecycle.onQueryEnd(true, List.of(), null);
        // If we got here, no exception was thrown. The extractor
        // was checked but shouldExtract was false (no messages).
    }

    @Test
    void disabledLifecycleSkipsRecallExperience() {
        MemoryLifecycle.Config cfg = new MemoryLifecycle.Config(
                false, 50L, 100, 2000, 1500, 1, true, true);
        MemoryLifecycle l2 = new MemoryLifecycle("sess-d", "test-agent", store,
                ForgettingPolicy.defaults(), audit, cfg);
        l2.setProjectCwd(projectCwd.toString());
        try {
            var hits = l2.recallExperience("maven", 3);
            assertTrue(hits.isEmpty(), "disabled lifecycle should not recall experience");
            var sessionHits = l2.recallSessionKv("sess", "maven", 16);
            assertTrue(sessionHits.isEmpty(), "disabled lifecycle should not recall session kv");
        } finally {
            l2.stop();
        }
    }
}
