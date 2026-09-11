package org.aethercode.memory;

import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryLifecycleTest {

    private Path tmp;
    private LayeredMemoryStore store;
    private SessionMemoryStore session;
    private MemoryAudit audit;
    private MemoryLifecycle lifecycle;
    private Path projectCwd;

    @BeforeEach
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("memory-lifecycle-test-");
        Path memoryBase = tmp.resolve("base");
        Path sessionDb = tmp.resolve("sessions.db");
        session = new SessionMemoryStore(sessionDb);
        ProjectMemoryCompressor.ChatClient noop = prompt -> java.util.Optional.empty();
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(noop);
        store = new LayeredMemoryStore(memoryBase, "test-agent", session, compressor,
                50, 10, true);
        audit = new MemoryAudit(tmp.resolve("audit.log"));
        audit.setEnabled(true);
        MemoryAudit.setInstanceForTesting(audit);
        projectCwd = tmp.resolve("projA");
        Files.createDirectories(projectCwd);
        // Use a tight decay interval so tests can trigger it
        MemoryLifecycle.Config cfg = new MemoryLifecycle.Config(
                true, 50L /*decayIntervalMs*/, 100, 2000, 1500, 1, true, true);
        lifecycle = new MemoryLifecycle("sess-test", "test-agent", store,
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

    // ---- Tier 1: onQueryStart ----

    @Test
    void onQueryStartCreatesBufferAndAudits() {
        WorkingMemoryBuffer buf = lifecycle.onQueryStart("test query");
        assertNotNull(buf);
        assertEquals("sess-test", buf.sessionId());
        assertNotNull(buf.queryId());
        assertEquals(1L, lifecycle.stats().queriesStarted());
        // Audit should have one query-start entry
        List<String> lines = readAuditLines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"kind\":\"buffer\"")),
                "audit log should contain query-start buffer entry");
    }

    @Test
    void onQueryStartCreatesDistinctBufferPerQuery() {
        WorkingMemoryBuffer b1 = lifecycle.onQueryStart("q1");
        WorkingMemoryBuffer b2 = lifecycle.onQueryStart("q2");
        assertNotEquals(b1.queryId(), b2.queryId());
    }

    // ---- Tier 0: onToolCall ----

    @Test
    void onToolCallRecordsName() {
        lifecycle.onQueryStart("q");
        lifecycle.onToolCall("file_read");
        lifecycle.onToolCall("file_edit");
        lifecycle.onToolCall("file_read");
        List<String> lines = readAuditLines();
        long toolAudit = lines.stream().filter(l -> l.contains("\"kind\":\"tool-call\"")).count();
        assertEquals(3L, toolAudit);
    }

    @Test
    void onToolCallNullOrBlankSkipped() {
        lifecycle.onQueryStart("q");
        lifecycle.onToolCall(null);
        lifecycle.onToolCall("");
        lifecycle.onToolCall("   ");
        List<String> lines = readAuditLines();
        long toolAudit = lines.stream().filter(l -> l.contains("\"kind\":\"tool-call\"")).count();
        assertEquals(0L, toolAudit);
    }

    // ---- Tier 1: onMemoryRecallHit ----

    @Test
    void onMemoryRecallHitBumpsCounter() {
        lifecycle.onQueryStart("q");
        lifecycle.onMemoryRecallHit("USER", "build.cmd", "abc-123");
        assertEquals(1L, lifecycle.stats().recallHits());
        List<String> lines = readAuditLines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"action\":\"recall\"")));
    }

    // ---- Tier 1: onMemoryWrite ----

    @Test
    void onMemoryWriteAudits() {
        lifecycle.onQueryStart("q");
        lifecycle.onMemoryWrite("PROJECT", "ts-strict", "rule", "internal");
        List<String> lines = readAuditLines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"key\":\"ts-strict\"")));
    }

    // ---- Tier 1: onQueryEnd ----

    @Test
    void onQueryEndClearsWorkingBuffer() {
        WorkingMemoryBuffer buf = lifecycle.onQueryStart("q");
        buf.put(WorkingMemoryBuffer.Kind.TODO, "remember to do X");
        assertEquals(1, buf.size());
        var report = lifecycle.onQueryEnd(true, List.of(), null);
        assertNotNull(report);
        assertEquals(0, buf.size());
        assertEquals(1L, lifecycle.stats().workingBufferClears());
    }

    @Test
    void onQueryEndFailureIncrementsFailedCounter() {
        lifecycle.onQueryStart("q");
        lifecycle.onQueryEnd(false, List.of(), null);
        var s = lifecycle.stats();
        assertEquals(1L, s.queriesFailed());
        assertEquals(0L, s.queriesCompleted());
    }

    @Test
    void onQueryEndSuccessIncrementsCompletedCounter() {
        lifecycle.onQueryStart("q");
        lifecycle.onQueryEnd(true, List.of(), null);
        var s = lifecycle.stats();
        assertEquals(1L, s.queriesCompleted());
    }

    // ---- Tier 2: case extraction ----

    @Test
    void extractCaseOnSuccessWithToolCalls() {
        lifecycle.onQueryStart("how to build maven?");
        lifecycle.onToolCall("file_read");
        lifecycle.onToolCall("file_edit");
        List<Message> transcript = new ArrayList<>();
        transcript.add(Message.userText("how to build maven?"));
        transcript.add(Message.assistantText("run mvn -B install"));
        var sink = new ArrayList<ExperienceRecord>();
        var report = lifecycle.onQueryEnd(true, transcript, sink);
        assertTrue(report.caseExtracted(), "case should be extracted");
        assertNotNull(report.caseRecord());
        assertEquals(ExperienceKind.CASE, report.caseRecord().kind());
        assertEquals(1L, lifecycle.stats().caseExtractions());
        assertEquals(1, sink.size(), "sink should receive the experience");
    }

    @Test
    void noExtractionOnFailure() {
        lifecycle.onQueryStart("q");
        lifecycle.onToolCall("file_read");
        List<Message> transcript = new ArrayList<>();
        transcript.add(Message.userText("q"));
        var sink = new ArrayList<ExperienceRecord>();
        var report = lifecycle.onQueryEnd(false, transcript, sink);
        assertFalse(report.caseExtracted());
        assertEquals(0L, lifecycle.stats().caseExtractions());
    }

    @Test
    void noExtractionWhenNoToolCalls() {
        lifecycle.onQueryStart("q");
        // No tool calls
        List<Message> transcript = new ArrayList<>();
        transcript.add(Message.userText("q"));
        transcript.add(Message.assistantText("a"));
        var sink = new ArrayList<ExperienceRecord>();
        var report = lifecycle.onQueryEnd(true, transcript, sink);
        assertFalse(report.caseExtracted());
    }

    @Test
    void extractionThresholdRespected() {
        // config has minToolCallsForExtraction=1; try 0
        MemoryLifecycle.Config cfg = new MemoryLifecycle.Config(
                true, 50L, 100, 2000, 1500, 5 /*require 5+*/, true, true);
        MemoryLifecycle l2 = new MemoryLifecycle("sess-2", "test-agent", store,
                ForgettingPolicy.defaults(), audit, cfg);
        l2.setProjectCwd(projectCwd.toString());
        try {
            l2.onQueryStart("q");
            l2.onToolCall("file_read");  // only 1
            l2.onToolCall("file_edit");  // 2
            List<Message> transcript = new ArrayList<>();
            transcript.add(Message.userText("q"));
            transcript.add(Message.assistantText("a"));
            var report = l2.onQueryEnd(true, transcript, null);
            assertFalse(report.caseExtracted(), "should NOT extract with only 2 tool calls when threshold=5");
        } finally {
            l2.stop();
        }
    }

    @Test
    void experienceWritesToProjectStore() {
        lifecycle.onQueryStart("how to maven build?");
        lifecycle.onToolCall("bash");
        List<Message> transcript = new ArrayList<>();
        transcript.add(Message.userText("how to maven build?"));
        transcript.add(Message.assistantText("mvn -B install"));
        lifecycle.onQueryEnd(true, transcript, null);
        // List the project experience
        var list = store.listProjectExperience(projectCwd.toString(), 10);
        assertTrue(list.size() >= 1, "project scope should have at least 1 experience");
        assertTrue(list.stream().anyMatch(e -> e.kind() == ExperienceKind.CASE),
                "should have at least one CASE record");
    }

    // ---- Tier 4: periodic decay ----

    @Test
    void periodicDecayRunsOnDemand() {
        // Add 5 ancient + low-utility items
        Instant longAgo = Instant.now().minusMillis(100L * ForgettingPolicy.DEFAULT_TAU_MS);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var items = new java.util.ArrayList<FileBackedMemory.MemoryItem>();
        for (int i = 0; i < 5; i++) {
            items.add(new FileBackedMemory.MemoryItem(
                    "old-" + i, "body-" + i, "user", List.of("utility=0.1"),
                    longAgo, longAgo, Sensitivity.INTERNAL, 0L, longAgo));
        }
        var userStore = store.userStore();
        try {
            String json = mapper.writeValueAsString(items);
            Path memFile = userStore.file();
            Files.writeString(memFile, json);
            // Reopen
            store = new LayeredMemoryStore(tmp.resolve("base"), "test-agent", session,
                    new ProjectMemoryCompressor(p -> java.util.Optional.empty()),
                    50, 10, true);
            // Bump the lifecycle to use the new store
            lifecycle = new MemoryLifecycle("sess-test", "test-agent", store,
                    ForgettingPolicy.defaults(), audit, lifecycle.config());
            lifecycle.setProjectCwd(projectCwd.toString());
            assertEquals(5, store.userStore().size());
            lifecycle.runPeriodicDecay();
            assertTrue(lifecycle.stats().decayPasses() >= 1);
        } catch (Exception e) {
            fail("decay test setup failed: " + e.getMessage());
        }
    }

    @Test
    void periodicDecayIsThrottledByQueryStart() {
        // With decayInterval=50ms, two onQueryStart calls back-to-back
        // should fire the decay at most once within 50ms.
        Instant t0 = Instant.now();
        lifecycle.onQueryStart("q1");
        lifecycle.onQueryStart("q2");
        lifecycle.onQueryStart("q3");
        // The first call fires the decay; subsequent ones inside the
        // 50ms window should NOT. Allow a small slack for the thread
        // scheduler.
        long passes = lifecycle.stats().decayPasses();
        assertTrue(passes <= 2, "expected <= 2 decay passes, got " + passes);
    }

    // ---- Disabled lifecycle ----

    @Test
    void disabledLifecycleIsNoOp() {
        MemoryLifecycle.Config cfg = new MemoryLifecycle.Config(
                false, 50L, 100, 2000, 1500, 1, true, true);
        MemoryLifecycle l2 = new MemoryLifecycle("sess-d", "test-agent", store,
                ForgettingPolicy.defaults(), audit, cfg);
        l2.setProjectCwd(projectCwd.toString());
        try {
            l2.onQueryStart("q");
            l2.onToolCall("t");
            l2.onMemoryRecallHit("USER", "k", "id");
            l2.onMemoryWrite("USER", "k", "fact", null);
            l2.onQueryEnd(true, List.of(), null);
            assertEquals(0L, l2.stats().queriesStarted());
        } finally {
            l2.stop();
        }
    }

    // ---- Heuristic helpers ----

    @Test
    void extractCaseFromTranscriptReturnsNullForEmpty() {
        assertNull(lifecycle.extractCaseFromTranscript(List.of(), Instant.now()));
        assertNull(lifecycle.extractCaseFromTranscript(null, Instant.now()));
    }

    @Test
    void extractCaseFromTranscriptBuildsCase() {
        Instant now = Instant.now();
        List<Message> transcript = new ArrayList<>();
        transcript.add(Message.userText("how do I configure tsc strict mode?"));
        transcript.add(Message.assistantText("add \"strict\": true to tsconfig.json compilerOptions"));
        ExperienceRecord rec = lifecycle.extractCaseFromTranscript(transcript, now);
        assertNotNull(rec);
        assertEquals(ExperienceKind.CASE, rec.kind());
        assertTrue(rec.title().contains("tsc strict"));
        assertTrue(rec.body().contains("Last assistant response"));
        assertEquals("success", rec.sourceOutcome());
    }

    @Test
    void configDefaultsAreSensible() {
        MemoryLifecycle.Config c = MemoryLifecycle.Config.defaults();
        assertTrue(c.enabled());
        assertEquals(MemoryLifecycle.DEFAULT_DECAY_INTERVAL_MS, c.decayIntervalMs());
        assertTrue(c.extractCaseOnSuccess());
        assertTrue(c.extractStrategyGated());
    }

    private List<String> readAuditLines() {
        try {
            Path log = tmp.resolve("audit.log");
            if (!Files.exists(log)) return List.of();
            return Files.readAllLines(log);
        } catch (Exception e) {
            return List.of();
        }
    }
}
