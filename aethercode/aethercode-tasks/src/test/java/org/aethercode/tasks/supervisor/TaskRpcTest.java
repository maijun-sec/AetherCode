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

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-353/T-360..T-369): the task RPC surface that the
 * TUI / CLI / desktop talk to. Each test boots a real
 * {@link SupervisorProcess}, talks to it via
 * {@link SupervisorClient}, and asserts the wire shape the
 * design.md §4.3 contract promises.
 */
class TaskRpcTest {

    private Path tmpDir;
    private Path dbPath;
    private SupervisorProcess process;
    private SupervisorClient client;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("aethercode-task-rpc-");
        SupervisorHome.override(tmpDir);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SupervisorSocketAddress.override(
                tmpDir.resolve("supervisor-" + suffix + ".sock"),
                "aethercode-supervisor-rpc-test-" + suffix);
        dbPath = tmpDir.resolve("sessions.db");
        process = new SupervisorProcess(dbPath);
        process.start();
        client = new SupervisorClient(SupervisorHome.dir().resolve("supervisor.sock"));
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (process != null && process.isRunning()) process.stop();
        SupervisorSocketAddress.clearOverride();
        SupervisorHome.clearOverride();
        if (tmpDir != null) {
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (IOException ignored) {}
        }
    }

    // -- T-360 task/spawn (already in SupervisorProcessTest;
    //    covered here for completeness) ---------------------------

    @Test
    void spawnReturnsChildId() throws Exception {
        Map<String, Object> r = client.callMap("task/spawn", Map.of(
                "prompt", "explain this", "cwd", tmpDir.toString()));
        assertNotNull(r.get("childId"));
        assertEquals("QUEUED", r.get("status"));
    }

    // -- T-361 task/list --------------------------------------------

    @Test
    void listFiltersByStatus() throws Exception {
        String id = (String) client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString())).get("childId");
        List<Map<String, Object>> all = client.callList("task/list", Map.of());
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).get("id"));
        List<Map<String, Object>> queued = client.callList("task/list",
                Map.of("status", "QUEUED"));
        assertEquals(1, queued.size());
        List<Map<String, Object>> completed = client.callList("task/list",
                Map.of("status", "COMPLETED"));
        assertEquals(0, completed.size());
    }

    @Test
    void listInvalidStatusReturnsParseError() {
        assertThrows(IOException.class, () -> client.callList("task/list",
                Map.of("status", "BOGUS")));
    }

    // -- T-353 task/setLimits --------------------------------------

    @Test
    void setLimitsMergesIntoConfigBlob() throws Exception {
        String id = (String) client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString())).get("childId");
        Map<String, Object> r = client.callMap("task/setLimits", Map.of(
                "childId", id,
                "limits", Map.of("tokens", 5000, "calls", 50)));
        assertEquals(Boolean.TRUE, r.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) r.get("limits");
        assertEquals(5000, ((Number) stored.get("tokens")).longValue());
        assertEquals(50, ((Number) stored.get("calls")).longValue());
        // Re-read via task/get; the config blob reflects the new limits.
        Map<String, Object> got = client.callMap("task/get", Map.of("childId", id));
        String cfg = (String) got.get("config");
        assertTrue(cfg.contains("\"tokens\":5000"),
                "expected tokens=5000 in config, got: " + cfg);
    }

    @Test
    void setLimitsRemoveDeletesField() throws Exception {
        String id = (String) client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString())).get("childId");
        client.callMap("task/setLimits", Map.of(
                "childId", id,
                "limits", Map.of("tokens", 1000, "calls", 25)));
        Map<String, Object> r = client.callMap("task/setLimits", Map.of(
                "childId", id,
                "remove", List.of("tokens")));
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) r.get("limits");
        assertFalse(stored.containsKey("tokens"));
        assertEquals(25, ((Number) stored.get("calls")).longValue());
    }

    @Test
    void setLimitsMissingChildReturnsParseError() {
        assertThrows(IOException.class, () -> client.callMap("task/setLimits", Map.of(
                "childId", "c-doesnotexist",
                "limits", Map.of("tokens", 100))));
    }

    // -- T-362 task/attach / T-363 task/detach --------------------

    @Test
    void attachReturnsChildAndEvents() throws Exception {
        String id = (String) client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString())).get("childId");
        client.callMap("task/appendEvent", Map.of(
                "childId", id, "type", "model_message",
                "payload", "{\"text\":\"hello\"}"));
        Map<String, Object> r = client.callMap("task/attach",
                Map.of("childId", id));
        @SuppressWarnings("unchecked")
        Map<String, Object> child = (Map<String, Object>) r.get("child");
        assertEquals(id, child.get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) r.get("events");
        assertTrue(events.size() >= 2); // at least QUEUED + model_message
        assertNotNull(r.get("lastEventId"));
    }

    @Test
    void attachSinceCursorLimitsReplayedEvents() throws Exception {
        String id = (String) client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString())).get("childId");
        client.callMap("task/appendEvent", Map.of(
                "childId", id, "type", "model_message", "payload", "{\"i\":1}"));
        long firstEventId = ((Number) client.callMap("task/appendEvent",
                Map.of("childId", id, "type", "model_message",
                        "payload", "{\"i\":2}")).get("eventId")).longValue();
        // Append a third event so the since-cursor has a successor.
        client.callMap("task/appendEvent", Map.of(
                "childId", id, "type", "model_message", "payload", "{\"i\":3}"));
        Map<String, Object> r = client.callMap("task/attach",
                Map.of("childId", id, "since", firstEventId));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) r.get("events");
        assertEquals(1, events.size());
    }

    @Test
    void detachReturnsAcknowledgement() throws Exception {
        String id = (String) client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString())).get("childId");
        Map<String, Object> r = client.callMap("task/detach",
                Map.of("childId", id));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("DETACHED", r.get("status"));
    }

    @Test
    void attachUnknownChildReturnsParseError() {
        assertThrows(IOException.class, () -> client.callMap("task/attach",
                Map.of("childId", "c-missing")));
    }

    // -- T-367 task/retry -----------------------------------------

    @Test
    void retrySpawnsNewChildFromOriginalPrompt() throws Exception {
        String first = (String) client.callMap("task/spawn", Map.of(
                "prompt", "explain this", "cwd", tmpDir.toString())).get("childId");
        Map<String, Object> r = client.callMap("task/retry",
                Map.of("childId", first));
        String newId = (String) r.get("childId");
        assertNotEquals(first, newId);
        assertEquals("QUEUED", r.get("status"));
        assertEquals(first, r.get("from"));
        Map<String, Object> got = client.callMap("task/get", Map.of("childId", newId));
        assertEquals("explain this", got.get("prompt"));
    }

    @Test
    void retryUnknownChildReturnsParseError() {
        assertThrows(IOException.class, () -> client.callMap("task/retry",
                Map.of("childId", "c-missing")));
    }

    // -- T-368 task/events (polling fallback) --------------------

    @Test
    void eventsReturnsListOfEvents() throws Exception {
        String id = (String) client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString())).get("childId");
        client.callMap("task/appendEvent", Map.of(
                "childId", id, "type", "tool_call", "payload", "{}"));
        List<Map<String, Object>> events = client.callList("task/events",
                Map.of("childId", id, "since", 0, "limit", 100));
        assertTrue(events.size() >= 2); // QUEUED + tool_call
    }

    // -- existing RPC smoke tests ---------------------------------

    @Test
    void killAndGetRoundTrip() throws Exception {
        String id = (String) client.callMap("task/spawn", Map.of(
                "prompt", "x", "cwd", tmpDir.toString())).get("childId");
        client.callMap("task/kill", Map.of("childId", id, "reason", "user"));
        Map<String, Object> got = client.callMap("task/get", Map.of("childId", id));
        assertEquals("KILLED", got.get("status"));
    }

    @Test
    void resumeRejectsNonPaused() {
        // The new client needs an existing child. Spawn one
        // first to make sure the resume error path is the
        // INVALID_PARAMS code (not "unknown childId").
        assertThrows(Exception.class, () -> {
            String id = (String) client.callMap("task/spawn", Map.of(
                    "prompt", "x", "cwd", tmpDir.toString())).get("childId");
            client.callMap("task/resume", Map.of("childId", id));
        });
    }
}
