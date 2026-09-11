package org.aethercode.protocol.http;

import org.aethercode.protocol.jsonrpc.JsonRpcCodec;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for inbound WebSocket notification routing. The prior round
 * {@link HttpJsonRpcServer#handleMessage} dropped inbound
 * {@code JsonRpcNotification} messages after a {@code LOG.debug} —
 * the comment said "the engine never expects inbound notifications",
 * which was true for the TUI/Desktop clients (they use
 * {@code call()} which sends a request-with-id) but wrong for the
 * headless Python driver, which sends {@code permissionResponse} as
 * a notification (fire-and-forget, no id) per the JSON-RPC 2.0 spec.
 *
 * <p>The symptom was: any medium-risk tool call
 * ({@code file_write}, {@code file_edit}, {@code todo_write})
 * triggered a {@code permission_request} notification, the client
 * replied with a {@code permissionResponse} notification, the
 * daemon logged it at DEBUG and dropped it, the prompter's
 * {@code CompletableFuture} never resolved, the tool never
 * executed, the model never got a {@code tool_result}, and after
 * ~1000s of waiting the model hit {@code loop_detected}.
 *
 * <p>The R133 fix: route inbound notifications through
 * {@link HttpJsonRpcServer#dispatch} just like requests. The
 * switch already has cases for every inbound notification method
 * we know about ({@code permissionResponse}, {@code loopAck},
 * {@code setAutoApproveLowRisk}, {@code setAutoApproveMediumHigh}).
 *
 * <p>Test strategy: open a real {@link WebSocket} (JDK 11+
 * {@code HttpClient.WebSocket.Builder}), trigger a permission ask
 * via {@code askPermission}, send a {@code permissionResponse}
 * notification from the client, verify the future resolves.
 */
class HttpJsonRpcServerR133Test {

    private AetherCodeEngine engine;
    private HttpJsonRpcServer server;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final JsonRpcCodec codec = new JsonRpcCodec();

    @BeforeEach
    void setUp() {
        engine = AetherCodeEngine.builder()
                .cwd(Path.of(".").toAbsolutePath())
                .build();
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

    /**
     * The exact bug from the RAG end-to-end test (R132 session):
     * 1. Open a WebSocket
     * 2. Trigger a permission ask (sends a permission_request
     *    notification to the client)
     * 3. Capture the requestId from the notification
     * 4. Send permissionResponse as a notification (no id)
     * 5. Verify the future resolves within 2s (before, it would
     *    hang until the default timeout)
     */
    @Test
    void permissionResponseNotificationResolvesFuture() throws Exception {
        LinkedBlockingQueue<JsonRpcMessage> inbox = new LinkedBlockingQueue<>();
        AtomicReference<String> lastError = new AtomicReference<>();
        CompletableFuture<WebSocket> ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage onText(WebSocket webSocket, CharSequence data, boolean last) {
                                try {
                                    JsonRpcMessage m = codec.decode(data.toString());
                                    inbox.add(m);
                                } catch (Exception e) {
                                    lastError.set("decode: " + e.getMessage());
                                }
                                webSocket.request(1);
                                return null;
                            }
                        });
        WebSocket socket = ws.get(3, TimeUnit.SECONDS);
        // Drain the welcome.
        for (int i = 0; i < 5; i++) {
            JsonRpcMessage m = inbox.poll(2, TimeUnit.SECONDS);
            if (m == null) break;
        }
        // Trigger a permission ask.
        CompletableFuture<AetherCodeMethods.PermissionDecision> fut = server.methods().askPermission(
                "test-run-1", "file_write",
                Map.of("file_path", "src/test/scratch_r133.txt", "content", "hi"),
                "test permission", "medium");
        // Wait for the permission_request notification.
        String requestId = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && requestId == null) {
            JsonRpcMessage m = inbox.poll(500, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            if (m instanceof org.aethercode.protocol.jsonrpc.JsonRpcNotification n
                    && "permission_request".equals(n.method())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) n.params();
                requestId = (String) p.get("requestId");
            }
        }
        assertNotNull(requestId,
                "should have received permission_request notification; lastError=" + lastError.get());
        // Build the permissionResponse notification (no id, fire-and-forget).
        String responseJson = codec.encode(new org.aethercode.protocol.jsonrpc.JsonRpcNotification(
                org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                "permissionResponse",
                Map.of("requestId", requestId, "decision", "allow", "reason", "R133 test allow")));
        socket.sendText(responseJson, true).get(2, TimeUnit.SECONDS);
        // The future should resolve within 2s.
        AetherCodeMethods.PermissionDecision decision = fut.get(2, TimeUnit.SECONDS);
        assertNotNull(decision);
        assertTrue(decision.isAllow(),
                "decision should be allow, got: " + decision.decision() + " reason=" + decision.reason());
    }

    /**
     * Sanity: an inbound request (with id) also works, so we know
     * the WS path itself isn't broken — only the notification
     * branch was dropped.
     */
    @Test
    void pingRequestReturnsPong() throws Exception {
        LinkedBlockingQueue<JsonRpcMessage> inbox = new LinkedBlockingQueue<>();
        CompletableFuture<WebSocket> ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage onText(WebSocket webSocket, CharSequence data, boolean last) {
                                try {
                                    inbox.add(codec.decode(data.toString()));
                                } catch (Exception ignored) {}
                                webSocket.request(1);
                                return null;
                            }
                        });
        WebSocket socket = ws.get(3, TimeUnit.SECONDS);
        for (int i = 0; i < 5; i++) {
            if (inbox.poll(500, TimeUnit.MILLISECONDS) == null) break;
        }
        JsonRpcRequest req = new JsonRpcRequest(
                org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                1, "ping", Map.of());
        socket.sendText(codec.encode(req), true).get(2, TimeUnit.SECONDS);
        JsonRpcMessage reply = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (reply == null && System.currentTimeMillis() < deadline) {
            reply = inbox.poll(500, TimeUnit.MILLISECONDS);
        }
        assertNotNull(reply, "no reply received");
        assertTrue(reply instanceof JsonRpcResponse, "reply should be a response");
        JsonRpcResponse resp = (JsonRpcResponse) reply;
        assertEquals(1, ((Number) resp.id()).intValue());
    }

    /**
     * an inbound notification for an UNKNOWN method is
     * logged and dropped (no crash). This guards against a future
     * where the server adds a new outbound notification that the
     * client doesn't know about.
     */
    @Test
    void unknownNotificationIsIgnored() throws Exception {
        LinkedBlockingQueue<JsonRpcMessage> inbox = new LinkedBlockingQueue<>();
        CompletableFuture<WebSocket> ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage onText(WebSocket webSocket, CharSequence data, boolean last) {
                                try {
                                    inbox.add(codec.decode(data.toString()));
                                } catch (Exception ignored) {}
                                webSocket.request(1);
                                return null;
                            }
                        });
        WebSocket socket = ws.get(3, TimeUnit.SECONDS);
        for (int i = 0; i < 5; i++) {
            if (inbox.poll(500, TimeUnit.MILLISECONDS) == null) break;
        }
        // Send an unknown notification — server should not crash
        // and should not produce a response.
        String n = codec.encode(new org.aethercode.protocol.jsonrpc.JsonRpcNotification(
                org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                "this_method_does_not_exist",
                Map.of("foo", "bar")));
        socket.sendText(n, true).get(2, TimeUnit.SECONDS);
        // Give the server a moment to process (and silently drop).
        Thread.sleep(500);
        // No response should have come back (notifications don't
        // generate responses; if the server crashed, the WS would
        // be closed, not just idle).
        assertTrue(inbox.isEmpty(),
                "unknown notification should not produce any message, got: " + inbox);
        // Sanity: server is still alive — ping works.
        socket.sendText(codec.encode(new JsonRpcRequest(
                org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                99, "ping", Map.of())), true).get(2, TimeUnit.SECONDS);
        JsonRpcMessage reply = inbox.poll(2, TimeUnit.SECONDS);
        assertNotNull(reply, "server should still be alive after unknown notification");
    }
}
