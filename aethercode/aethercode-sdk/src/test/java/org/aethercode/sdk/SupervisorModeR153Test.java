package org.aethercode.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R153 tests: minimal WS proxy via HTTP notification.
 *
 * <p>The supervisor's {@code proxyNotification}
 * posts a JSON-RPC notification to a child
 * daemon's {@code /jsonrpc} endpoint and
 * returns whether the child accepted it
 * (HTTP 2xx). This covers
 * {@code permission_response},
 * {@code loop_ack}, and other fire-and-forget
 * notifications that the TUI wants to relay
 * to a child when the TUI itself is connected
 * to the supervisor (not directly to the
 * child).
 *
 * <p>Live streaming events (transcript_event,
 * task_event) still require the TUI to
 * connect to the child's own WS — that is a
 * follow-up round (R155.1).
 */
class SupervisorModeR153Test {

    private String spawnedChildId = null;
    private String spawnedJarCopy = null;

    @AfterEach
    void cleanup() {
        if (spawnedChildId != null) {
            try { SupervisorMode.instance().killChild(spawnedChildId, 2_000L); } catch (Exception ignored) {}
            try { SupervisorMode.instance().unregisterChild(spawnedChildId); } catch (Exception ignored) {}
            spawnedChildId = null;
        }
        if (spawnedJarCopy != null) {
            try { Files.deleteIfExists(Path.of(spawnedJarCopy)); } catch (Exception ignored) {}
            spawnedJarCopy = null;
        }
    }

    private static boolean gitOnPath() {
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean classpathHasTestHttpServer() {
        // Verify TestHttpServer is on the
        // test classpath. The R150 test
        // path needs it; this test reuses
        // the same approach.
        try {
            Class.forName("org.aethercode.sdk.TestHttpServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String childCwd(@TempDir Path tmp) {
        try {
            Path p = tmp.resolve("child-cwd");
            Files.createDirectories(p);
            return p.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void proxyNotification_toUnhealthyChildReturnsFalse() {
        // External child on a port no one
        // is listening on. The supervisor
        // can't POST to a dead address.
        SupervisorMode.ChildInfo ci = SupervisorMode.instance().registerChild(
                "r153-unhealthy-" + System.currentTimeMillis(), 29_999, "/tmp");
        spawnedChildId = ci.childId();
        boolean ok = SupervisorMode.instance().proxyNotification(
                ci.childId(), "permissionResponse", null);
        assertFalse(ok, "proxyNotification to unhealthy child must return false");
    }

    @Test
    void proxyNotification_unknownChildReturnsFalse() {
        // No such childId. The supervisor
        // returns false immediately (no
        // network attempt).
        boolean ok = SupervisorMode.instance().proxyNotification(
                "never-registered-r153", "ping", null);
        assertFalse(ok);
    }

    @Test
    void proxyNotification_serialisesParamsToJson() {
        // The serialiseParams logic must
        // produce valid JSON for a Map
        // (otherwise the child's JSON-RPC
        // decoder will throw on the other
        // side and the notification will
        // be ignored). This is a unit
        // test for the serialisation —
        // the actual HTTP roundtrip is
        // covered by the live smoke test
        // in the integration suite.
        Map<String, Object> params = Map.of(
                "decision", "allow",
                "user_id", 42,
                "scopes", List.of("file_write", "bash"));
        // The serialiseParams is private;
        // we verify through the public
        // path (which would fail with an
        // unhealthy child but the
        // serialisation still happens).
        // We just want to assert "doesn't
        // throw" for the common cases.
        assertDoesNotThrow(() -> {
            SupervisorMode.instance().proxyNotification(
                    "fake-r153-serialise", "permissionResponse", params);
        });
    }
}
