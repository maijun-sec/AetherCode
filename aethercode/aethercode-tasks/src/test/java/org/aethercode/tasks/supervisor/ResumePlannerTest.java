package org.aethercode.tasks.supervisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-314): resume-planner + supervisor-on-startup
 * integration. The test creates a fresh DB, leaves some
 * children in unfinished states, then restarts the
 * supervisor and checks the rows were brought into the
 * expected post-resume state.
 */
class ResumePlannerTest {

    private Path tmpDir;
    private Path dbPath;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("aethercode-resume-test-");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SupervisorHome.override(tmpDir);
        SupervisorSocketAddress.override(
                tmpDir.resolve("supervisor-" + suffix + ".sock"),
                "aethercode-supervisor-resume-" + suffix);
        dbPath = tmpDir.resolve("sessions.db");
    }

    @AfterEach
    void tearDown() throws IOException {
        SupervisorSocketAddress.clearOverride();
        SupervisorHome.clearOverride();
        if (tmpDir != null) {
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void plannerReturnsRespawnForQueuedAndRunningNoopForPaused() throws Exception {
        try (SupervisorStore store = SupervisorStore.inMemory()) {
            store.migrate();
            String q = store.createChild("/tmp", "q", null, null);
            String r = store.createChild("/tmp", "r", null, null);
            store.updateStatus(r, ChildStatus.RUNNING);
            String p = store.createChild("/tmp", "p", null, null);
            store.updateStatus(p, ChildStatus.RUNNING);
            store.updateStatus(p, ChildStatus.PAUSED);
            ResumePlanner planner = new ResumePlanner(store);
            List<ResumePlanner.ResumeDecision> decisions = planner.planAndApply();
            assertEquals(3, decisions.size());
            for (ResumePlanner.ResumeDecision d : decisions) {
                if (d.child().id().equals(p)) {
                    assertEquals(ResumePlanner.ResumeAction.NOOP_PAUSED, d.action());
                } else {
                    assertEquals(ResumePlanner.ResumeAction.RESPAWN, d.action());
                }
            }
        }
    }

    @Test
    void supervisorStartupRestartsQueuedAndRunningAndKeepsPaused() throws Exception {
        // Phase 1: open a store, leave unfinished children,
        // close it (simulate a crashed supervisor).
        try (SupervisorStore seed = SupervisorStore.open(dbPath)) {
            seed.migrate();
            String q = seed.createChild(tmpDir.toString(), "queued", null, null);
            String r = seed.createChild(tmpDir.toString(), "running", null, null);
            seed.updateStatus(r, ChildStatus.RUNNING);
            String p = seed.createChild(tmpDir.toString(), "paused", null, null);
            seed.updateStatus(p, ChildStatus.RUNNING);
            seed.updateStatus(p, ChildStatus.PAUSED);
            String done = seed.createChild(tmpDir.toString(), "done", null, null);
            seed.updateStatus(done, ChildStatus.RUNNING);
            seed.updateStatus(done, ChildStatus.COMPLETED);
        }
        // Phase 2: start the supervisor on the same DB.
        SupervisorProcess proc = new SupervisorProcess(dbPath);
        proc.start();
        try {
            // Phase 3: assert the rows are in the expected post-resume state.
            try (SupervisorStore verify = SupervisorStore.open(dbPath)) {
                List<ChildRecord> rows = verify.listChildren(null);
                // 4 rows total; the COMPLETED one stays COMPLETED.
                assertEquals(4, rows.size());
                for (ChildRecord r : rows) {
                    switch (r.prompt()) {
                        case "queued" -> assertEquals(ChildStatus.RUNNING, r.status(),
                                "RESPAWN should have moved queued to RUNNING");
                        case "running" -> assertEquals(ChildStatus.RUNNING, r.status(),
                                "RESPAWN should have left running as RUNNING");
                        case "paused" -> assertEquals(ChildStatus.PAUSED, r.status(),
                                "NOOP_PAUSED must leave paused alone");
                        case "done" -> assertEquals(ChildStatus.COMPLETED, r.status(),
                                "terminal rows must stay terminal");
                        default -> fail("unexpected row: " + r.prompt());
                    }
                }
            }
        } finally {
            proc.stop();
        }
    }

    @Test
    void supervisorStartupEmitsStatusChangeEventForResumedChild() throws Exception {
        try (SupervisorStore seed = SupervisorStore.open(dbPath)) {
            seed.migrate();
            String r = seed.createChild(tmpDir.toString(), "running", null, null);
            seed.updateStatus(r, ChildStatus.RUNNING);
        }
        SupervisorProcess proc = new SupervisorProcess(dbPath);
        proc.start();
        try {
            // Find the running child and check its event log.
            SupervisorStore verify = SupervisorProcessReflection.openStore(proc);
            try {
                List<ChildRecord> rows = verify.listChildren(ChildStatus.RUNNING);
                assertEquals(1, rows.size());
                List<ChildEventRecord> events = verify.listEvents(rows.get(0).id(), 0, 100);
                assertTrue(events.stream().anyMatch(e ->
                        e.type().equals(ChildEventRecord.TYPE_STATUS_CHANGE)
                                && e.payloadJson().contains("RESPAWN")),
                        "expected a RESPAWN status_change event, got: " + events);
            } finally {
                verify.close();
            }
        } finally {
            proc.stop();
        }
    }
}
