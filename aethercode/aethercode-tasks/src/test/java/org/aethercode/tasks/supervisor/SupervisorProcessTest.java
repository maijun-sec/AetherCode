package org.aethercode.tasks.supervisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-310..T-314): end-to-end supervisor test. The
 * supervisor listens on a real TCP loopback socket; the test
 * uses {@link SupervisorClient} to call the task RPCs and
 * verifies the full path: spawn → list → events → kill.
 *
 * <p>The lock file is overridden per-test via
 * {@link SupervisorSocketAddress#override} so concurrent tests
 * don't collide.
 */
class SupervisorProcessTest {

    private Path tmpDir;
    private Path dbPath;
    private Path lockFile;
    private SupervisorProcess process;
    private SupervisorClient client;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("aethercode-supervisor-test-");
        // Each test uses its own home + lock file.
        SupervisorHome.override(tmpDir);
        // Pick a unique Unix path / pipe name so two tests don't race.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SupervisorSocketAddress.override(
                tmpDir.resolve("supervisor-" + suffix + ".sock"),
                "aethercode-supervisor-test-" + suffix);
        dbPath = tmpDir.resolve("sessions.db");
        process = new SupervisorProcess(dbPath);
        process.start();
        // Resolve the actual lock file produced by the supervisor.
        // The default home is what we set above; the lock file is
        // <home>/supervisor.sock.
        lockFile = SupervisorHome.dir().resolve("supervisor.sock");
        client = new SupervisorClient(lockFile);
        client.connect();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (process != null && process.isRunning()) process.stop();
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
    void spawnAndListRoundTrip() throws Exception {
        Map<String, Object> spawn = client.callMap("task/spawn", Map.of(
                "prompt", "summarise this file",
                "cwd", tmpDir.toString(),
                "parentSessionId", "s1"));
        assertNotNull(spawn.get("childId"));
        assertEquals("QUEUED", spawn.get("status"));

        List<Map<String, Object>> rows = client.callList("task/list", Map.of());
        assertEquals(1, rows.size());
        assertEquals(spawn.get("childId"), rows.get(0).get("id"));
    }

    @Test
    void spawnThenGetReturnsFullRecord() throws Exception {
        Map<String, Object> spawn = client.callMap("task/spawn", Map.of(
                "prompt", "explain the diff",
                "cwd", tmpDir.toString()));
        String childId = (String) spawn.get("childId");
        Map<String, Object> got = client.callMap("task/get", Map.of("childId", childId));
        assertEquals("QUEUED", got.get("status"));
        assertEquals(tmpDir.toString(), got.get("cwd"));
        assertEquals("explain the diff", got.get("prompt"));
    }

    @Test
    void killTransitionsStatusAndEmitsEvent() throws Exception {
        Map<String, Object> spawn = client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString()));
        String childId = (String) spawn.get("childId");
        Map<String, Object> killReply = client.callMap("task/kill",
                Map.of("childId", childId, "reason", "user aborted"));
        assertEquals(Boolean.TRUE, killReply.get("ok"));
        Map<String, Object> got = client.callMap("task/get", Map.of("childId", childId));
        assertEquals("KILLED", got.get("status"));
        assertEquals("user aborted", got.get("error"));
        // Replay events; the status_change should be present.
        List<Map<String, Object>> events = client.callList("task/events",
                Map.of("childId", childId, "limit", 100));
        assertTrue(events.stream().anyMatch(e ->
                "status_change".equals(e.get("type"))
                        && e.get("payload").toString().contains("KILLED")));
    }

    @Test
    void appendEventRoundTripsPayload() throws Exception {
        Map<String, Object> spawn = client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString()));
        String childId = (String) spawn.get("childId");
        Map<String, Object> r = client.callMap("task/appendEvent", Map.of(
                "childId", childId,
                "type", ChildEventRecord.TYPE_TOOL_CALL,
                "payload", "{\"name\":\"bash\"}"));
        long eventId = ((Number) r.get("eventId")).longValue();
        assertTrue(eventId > 0);
        List<Map<String, Object>> events = client.callList("task/events",
                Map.of("childId", childId, "since", 0L, "limit", 10));
        assertTrue(events.stream().anyMatch(e -> ((Number) e.get("id")).longValue() == eventId));
    }

    @Test
    void awaitReturnsTerminalStatus() throws Exception {
        Map<String, Object> spawn = client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString()));
        String childId = (String) spawn.get("childId");
        // Kill it from the service side directly so the awaiter
        // sees a terminal state without having to wait for the
        // real worker.
        client.callMap("task/kill", Map.of("childId", childId));
        Map<String, Object> result = client.callMap("task/await", Map.of(
                "childId", childId, "timeoutMs", 2000L));
        assertEquals("KILLED", result.get("status"));
    }

    @Test
    void resumeOnlyTransitionsPausedOrRunning() throws Exception {
        Map<String, Object> spawn = client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString()));
        String childId = (String) spawn.get("childId");
        // QUEUED cannot be resumed; expect INVALID_PARAMS.
        try {
            client.callMap("task/resume", Map.of("childId", childId));
            fail("resume on QUEUED should fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("INVALID_PARAMS")
                    || expected.getMessage().contains("not PAUSED"),
                    "expected INVALID_PARAMS, got: " + expected.getMessage());
        }
    }

    @Test
    void pingReturnsTimestamp() throws Exception {
        Map<String, Object> r = client.callMap("task/ping", Map.of());
        assertEquals(Boolean.TRUE, r.get("pong"));
        assertNotNull(r.get("ts"));
    }

    @Test
    void supervisorStopsCleanly() throws Exception {
        assertTrue(process.isRunning());
        process.stop();
        assertFalse(process.isRunning());
        // The lock file is removed on stop.
        assertFalse(Files.exists(SupervisorHome.dir().resolve("supervisor.sock")));
    }
}
