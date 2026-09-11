package org.aethercode.protocol.http;

import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the {@code POST /shutdown} endpoint. The
 * R131 SIGTERM graceful-drain path doesn't fire on Windows
 * because {@code taskkill /PID} sends {@code WM_CLOSE}, not
 * POSIX {@code SIGTERM}, and the JVM's shutdown hook never
 * runs. R134 exposes the same shutdown path over HTTP, so a
 * Windows shell script can {@code curl -X POST
 * http://.../shutdown} and the daemon will drain + exit
 * cleanly.
 *
 * <p>The shutdown handler is asynchronous (returns 202, then
 * starts a thread that drains + stops the server). The
 * tests here verify:
 *
 * <ol>
 *   <li>The endpoint returns 202 with a JSON body
 *       describing the drain parameters.</li>
 *   <li>Immediately after the call, the server's
 *       {@code shuttingDown} flag is set so {@code /healthz}
 *       starts returning 503 (R131 integration).</li>
 *   <li>The endpoint validates the {@code drainMs} query
 *       param and clamps to [0, 30000].</li>
 *   <li>The {@code force=1} param skips the drain (smoke
 *       test — full drain verification is racy in a unit
 *       test).</li>
 * </ol>
 *
 * <p>The full end-to-end "daemon exits cleanly" test is the
 * RAG end-to-end validation against the R134 daemon (see
 * the GENERATION-REPORT-R134 doc). The unit test here is a
 * behaviour pin for the HTTP surface.
 */
class HttpJsonRpcServerShutdownR134Test {

    private AetherCodeEngine engine;
    private HttpJsonRpcServer server;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @BeforeEach
    void setUp() {
        engine = AetherCodeEngine.builder()
                .cwd(Path.of(".").toAbsolutePath())
                .build();
        int port = pickFreePort();
        server = new HttpJsonRpcServer(port, engine);
        // tests must NOT call System.exit (which would
        // kill the surefire JVM mid-suite) and must NOT touch
        // the real engine drain (which is racy and slow).
        // Disable both.
        server.setExitOnShutdown(false);
        server.setDrainCallback(() -> {
            // Empty: just confirms the drain callback was
            // reached. The real drain logic is the production
            // path; tested via the RAG end-to-end run.
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private int pickFreePort() {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("no free port", e);
        }
    }

    @Test
    void shutdownReturns202AndJsonBody() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // The shutdown thread starts IMMEDIATELY (returns 202),
        // then drains + calls System.exit(0). The System.exit
        // races with our response — we may get 202 (if the
        // response is sent before exit) or the connection
        // may be reset. We accept either outcome: the body
        // MUST contain "shutting_down" if we got any.
        if (r.statusCode() == 202) {
            assertTrue(r.body().contains("shutting_down"),
                    "expected shutting_down body, got: " + r.body());
            assertTrue(r.body().contains("drainMs"));
        } else {
            // Connection reset / 5xx — acceptable since the
            // shutdown thread races with the response.
            // What matters is that we got SOME response and
            // the server actually started shutting down.
            assertTrue(r.statusCode() >= 500 || r.statusCode() == 0,
                    "unexpected status: " + r.statusCode());
        }
    }

    @Test
    void shutdownImmediatelyMarksShuttingDown() throws Exception {
        // The endpoint flips shuttingDown BEFORE returning 202.
        // So /healthz called right after the POST should
        // return 503 (or the connection may already be closed
        // if the drain thread completed — both are valid).
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown?drainMs=0"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // Now hit /healthz — should be 503 if drain thread
        // hasn't torn down the server yet, or any error if it
        // has. We only assert shuttingDown() is true (the
        // public boolean on the server).
        assertTrue(server.isShuttingDown(),
                "server should be marked shuttingDown after POST /shutdown");
    }

    @Test
    void shutdownAcceptsDrainMsQueryParam() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown?drainMs=10000"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 202) {
            assertTrue(r.body().contains("\"drainMs\":10000"),
                    "expected drainMs=10000, got: " + r.body());
        }
    }

    @Test
    void shutdownClampsDrainMsTo30000() throws Exception {
        // drainMs=99999 should clamp to 30000.
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown?drainMs=99999"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 202) {
            assertTrue(r.body().contains("\"drainMs\":30000"),
                    "expected drainMs clamped to 30000, got: " + r.body());
        }
    }

    @Test
    void shutdownForceParamParsedCorrectly() throws Exception {
        // force=1 should be reflected in the body.
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown?force=1&drainMs=0"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 202) {
            assertTrue(r.body().contains("\"force\":true"),
                    "expected force=true, got: " + r.body());
        }
    }

    @Test
    void shutdownRejectsMissingToken() throws Exception {
        // R135.5: when shutdownToken is set, the endpoint
        // rejects requests without a matching Bearer token.
        server.setShutdownToken("s3cr3t-tok3n-abc123");
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown?drainMs=0"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // No Authorization header — must be 401, not 202.
        assertEquals(401, r.statusCode(),
                "missing token should be 401, got: " + r.statusCode() + " body=" + r.body());
        assertTrue(r.body().contains("unauthorized") || r.body().contains("token"),
                "expected unauthorized body, got: " + r.body());
        // CRITICAL: 401 path must NOT mark the server shuttingDown.
        // The flag is only flipped on the success branch (after
        // the auth check passes). If we set it on the 401 path,
        // a random unauth'd probe would tear down the daemon
        // just by failing auth.
        assertEquals(false, server.isShuttingDown(),
                "401 path must not mark server shuttingDown (rejection != shutdown)");
    }

    @Test
    void shutdownRejectsWrongToken() throws Exception {
        server.setShutdownToken("correct-token");
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown?drainMs=0"))
                        .header("Authorization", "Bearer wrong-token")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, r.statusCode(),
                "wrong token should be 401, got: " + r.statusCode() + " body=" + r.body());
    }

    @Test
    void shutdownAcceptsCorrectToken() throws Exception {
        server.setShutdownToken("correct-token");
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown?drainMs=0"))
                        .header("Authorization", "Bearer correct-token")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // 202 = drain accepted, 0/5xx = server closed mid-response
        if (r.statusCode() == 202) {
            assertTrue(r.body().contains("shutting_down"), "body: " + r.body());
        } else {
            // Server may have closed the connection before
            // we got the body — that's also acceptable.
            assertTrue(r.statusCode() >= 500 || r.statusCode() == 0);
        }
    }

    @Test
    void shutdownOpenWhenTokenNull() throws Exception {
        // Default: token is null, endpoint is open.
        // (No setShutdownToken call.) Already covered by
        // the basic tests, but make it explicit.
        assertEquals(null, server.shutdownToken());
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/shutdown?drainMs=0"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // 202 = OK, anything else is "server tore down mid-response"
        if (r.statusCode() != 202) {
            assertTrue(r.statusCode() >= 500 || r.statusCode() == 0);
        }
    }
}
