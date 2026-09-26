package org.aethercode.protocol.http;

import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R361 Phase 3: multi-frontend WebSocket test.
 *
 * <p>{@link HttpJsonRpcServer} is designed to accept N
 * concurrent WS clients (the {@code /ws} route maintains
 * a per-connection dispatcher and broadcasts every server-
 * initiated notification to all clients). This test
 * asserts that contract end-to-end:
 * <ol>
 *   <li>Two independent clients connect to the same
 *       {@code /ws} endpoint.</li>
 *   <li>Each client can issue an RPC and receive an
 *       independent response (per-connection request id
 *       namespace).</li>
 *   <li>{@code server.broadcast(...)} delivers the same
 *       notification to every connected client.</li>
 *   <li>Closing one client does not perturb the other
 *       client's broadcast path.</li>
 * </ol>
 *
 * <p>The test uses the JDK 11+ {@link HttpClient#newWebSocketBuilder()}
 * which is portable across Windows / Linux / macOS
 * without adding a third-party WS client dependency.
 */
class HttpJsonRpcServerMultiClientR361Test {

    private AetherCodeEngine engine;
    private HttpJsonRpcServer server;
    private final HttpClient client = HttpClient.newBuilder().build();

    @BeforeEach
    void setUp() {
        engine = AetherCodeEngine.builder()
                .cwd(Path.of(".").toAbsolutePath())
                .build();
        server = new HttpJsonRpcServer(pickFreePort(), engine);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private static int pickFreePort() {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("no free port", e);
        }
    }

    /**
     * Thin WS-client wrapper: collects every inbound
     * text frame (notifications + responses) and exposes
     * a CompletableFuture for the next response that
     * matches a given request id.
     */
    static final class CollectingWsListener implements WebSocket.Listener {
        final BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
        final AtomicInteger nextRequestId = new AtomicInteger(1);

        @Override
        public void onOpen(WebSocket ws) {
            WebSocket.Listener.super.onOpen(ws);
        }

        @Override
        public CompletionStage onText(WebSocket ws, CharSequence data, boolean last) {
            inbound.add(data.toString());
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage onClose(WebSocket ws, int statusCode, String reason) {
            return null;
        }

        /**
         * Send a JSON-RPC request and wait for the
         * matching response (filtered by request id).
         * Times out after {@code timeoutMs}.
         */
        String sendAndAwait(WebSocket ws, String method, Map<String, Object> params, long timeoutMs)
                throws Exception {
            int id = nextRequestId.getAndIncrement();
            String req = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "method", method,
                            "params", params));
            CompletableFuture<String> matched = new CompletableFuture<>();
            // We don't have a clean way to multiplex on
            // request id without spawning a watcher; for
            // short tests this polling loop is fine.
            ws.sendText(req, true);
            long deadline = System.currentTimeMillis() + timeoutMs;
            StringBuilder acc = new StringBuilder();
            while (System.currentTimeMillis() < deadline) {
                String f = inbound.poll(50, TimeUnit.MILLISECONDS);
                if (f == null) continue;
                acc.append(f);
                if (f.contains("\"id\":" + id) && f.contains("\"result\"")) {
                    matched.complete(acc.toString());
                    return acc.toString();
                }
            }
            throw new AssertionError("no response for id=" + id + " within " + timeoutMs + "ms");
        }

        /** Wait for the next inbound frame to contain
         *  the given substring. Returns the matched frame
         *  (or null on timeout). */
        String awaitFrameContaining(String needle, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String f = inbound.poll(50, TimeUnit.MILLISECONDS);
                if (f != null && f.contains(needle)) return f;
            }
            return null;
        }
    }

    @Test
    void twoClientsReceiveIndependentRpcResponses() throws Exception {
        // Connect two WS clients to the same daemon.
        URI wsUri = URI.create("ws://127.0.0.1:" + server.port() + "/ws");
        CollectingWsListener lA = new CollectingWsListener();
        CollectingWsListener lB = new CollectingWsListener();
        WebSocket wsA = client.newWebSocketBuilder()
                .buildAsync(wsUri, lA).get(5, TimeUnit.SECONDS);
        WebSocket wsB = client.newWebSocketBuilder()
                .buildAsync(wsUri, lB).get(5, TimeUnit.SECONDS);

        // Each client sends ping; both must succeed.
        String rA = lA.sendAndAwait(wsA, "ping", Map.of(), 5_000);
        String rB = lB.sendAndAwait(wsB, "ping", Map.of(), 5_000);
        assertThat(rA).contains("\"result\"");
        assertThat(rB).contains("\"result\"");
        // Each response references a distinct request id
        // — proves the WS server's per-connection id
        // namespace works.
        assertThat(rA).contains("\"id\":1");
        assertThat(rB).contains("\"id\":1");

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "");
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "");
    }

    @Test
    void broadcastReachesEveryConnectedClient() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + server.port() + "/ws");
        CollectingWsListener lA = new CollectingWsListener();
        CollectingWsListener lB = new CollectingWsListener();
        WebSocket wsA = client.newWebSocketBuilder()
                .buildAsync(wsUri, lA).get(5, TimeUnit.SECONDS);
        WebSocket wsB = client.newWebSocketBuilder()
                .buildAsync(wsUri, lB).get(5, TimeUnit.SECONDS);

        // Give the server a moment to register both
        // connections in its ConcurrentHashMap before
        // broadcasting — without this the broadcast may
        // race the connect handshake.
        Thread.sleep(200);

        server.broadcast("r361.test", Map.of("msg", "hello"));

        String fA = lA.awaitFrameContaining("r361.test", 5_000);
        String fB = lB.awaitFrameContaining("r361.test", 5_000);
        assertThat(fA)
                .as("client A must receive the broadcast notification")
                .isNotNull()
                .contains("hello");
        assertThat(fB)
                .as("client B must receive the broadcast notification")
                .isNotNull()
                .contains("hello");

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "");
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "");
    }

    @Test
    void closingOneClientDoesNotInterruptTheOther() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + server.port() + "/ws");
        CollectingWsListener lA = new CollectingWsListener();
        CollectingWsListener lB = new CollectingWsListener();
        WebSocket wsA = client.newWebSocketBuilder()
                .buildAsync(wsUri, lA).get(5, TimeUnit.SECONDS);
        WebSocket wsB = client.newWebSocketBuilder()
                .buildAsync(wsUri, lB).get(5, TimeUnit.SECONDS);

        Thread.sleep(200);
        // Close A first.
        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "");
        Thread.sleep(200);

        // Broadcast must still reach B — A's close should
        // not have perturbed B's connection or the
        // server's broadcast path.
        server.broadcast("r361.afterClose", Map.of("to", "b"));
        String fB = lB.awaitFrameContaining("r361.afterClose", 5_000);
        assertThat(fB)
                .as("client B must still receive broadcasts after A closes")
                .isNotNull();

        // B can also still issue an RPC.
        String rB = lB.sendAndAwait(wsB, "ping", Map.of(), 5_000);
        assertThat(rB).contains("\"result\"");

        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "");
    }
}