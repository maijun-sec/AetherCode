package org.aethercode.protocol.http;

import org.aethercode.protocol.jsonrpc.JsonRpcCodec;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
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
 * tests for {@link HttpJsonRpcServer}. We hit the real HTTP
 * routes with JDK {@code HttpClient} (no extra dependency) and
 * verify the engine info, the registered-methods list, and the
 * /healthz endpoint. WebSocket round-trips are exercised by a
 * separate end-to-end script (out of scope for a unit test — the
 * Javalin WebSocket layer needs a real ws client).
 */
class HttpJsonRpcServerTest {

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
        // Find an open port by binding to 0 and reading the chosen port,
        // then closing the test socket — slightly racy but works for tests.
        int port = pickFreePort();
        server = new HttpJsonRpcServer(port, engine);
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
    void rootReturnsHealthAndEngineInfo() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"status\":\"ok\""), r.body());
        assertTrue(r.body().contains("\"engine\""), r.body());
        assertTrue(r.body().contains("\"sessionId\""), r.body());
        assertTrue(r.body().contains("\"model\""), r.body());
    }

    @Test
    void healthzReturns200() throws Exception {
        // /healthz returns a JSON envelope so a k8s
        // liveness probe / systemd watchdog can parse the
        // structured fields (uptimeMs, version, sessionId)
        // instead of just looking at the body string. The
        // 200 status is the canonical liveness signal.
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/healthz"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"status\":\"ok\""),
                "healthz body should report status=ok, got: " + r.body());
        assertTrue(r.body().contains("\"uptimeMs\":"));
    }

    @Test
    void healthz_returns503WhenShuttingDown() throws Exception {
        // the graceful-shutdown flag flips /healthz
        // into 503 so a k8s liveness probe / systemd watchdog
        // can take the service out of rotation BEFORE the
        // JVM actually exits. The 30s drain window is
        // what gives the upstream load balancer time to
        // detect the 503 and stop sending traffic.
        server.markShuttingDown();
        try {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + server.port() + "/healthz"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, r.statusCode());
            assertTrue(r.body().contains("\"status\":\"shutting_down\""),
                    "expected shutting_down body, got: " + r.body());
        } finally {
            // Reset for subsequent tests in this class.
            // (We don't have a public un-set; the test
            // framework runs each test in a fresh server.)
        }
    }

    @Test
    void apiMethodsListsRegisteredMethods() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/api/methods"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        // A subset of well-known methods.
        for (String m : new String[]{"ping", "getState", "query", "getMetrics", "getTraces", "getTrace"}) {
            assertTrue(r.body().contains("\"" + m + "\""), "missing method " + m + " in: " + r.body());
        }
    }

    @Test
    void apiInfoIncludesMetricsAndTraces() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.port() + "/api/info"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"metrics\""), r.body());
        assertTrue(r.body().contains("\"traces\""), r.body());
        assertTrue(r.body().contains("turnsStarted"), r.body());
    }

    @Test
    void methodsAccessorReturnsSameInstance() {
        // Used by DaemonRunner to wire the permission prompter, so it
        // must be a stable reference (and broadcast to every client
        // via the notifier).
        assertNotNull(server.methods());
        assertEquals(server.methods(), server.methods());
    }
}
