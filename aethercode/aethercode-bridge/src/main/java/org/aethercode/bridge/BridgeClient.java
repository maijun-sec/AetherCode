package org.aethercode.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.tool.Tool;
import org.aethercode.sdk.AetherCodeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * bridge client with full auth + reconnect. Builds on the prior round wire protocol
 * and adds:
 *
 * <ul>
 *   <li>Challenge / response auth (see {@link BridgeAuth})</li>
 *   <li>Exponential-backoff reconnect (see {@link ReconnectStrategy})</li>
 *   <li>401 retry: when the server returns {@code auth-fail}, the client rotates
 *       the token and reconnects</li>
 * </ul>
 */
public class BridgeClient {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String orchestratorUrl;
    private volatile String sessionToken;
    private final BridgeAuth auth = new BridgeAuth();
    private final ReconnectStrategy reconnect = new ReconnectStrategy();
    private final Map<String, AetherCodeEngine> localEngines = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private WebSocket ws;
    private Thread heartbeatThread;
    private Thread reconnectThread;

    public BridgeClient(String orchestratorUrl, String sessionToken) {
        this.orchestratorUrl = orchestratorUrl;
        this.sessionToken = sessionToken;
        this.auth.setToken(sessionToken);
        this.reconnect.onGiveUp(n -> {
            LOG.error("bridge: gave up reconnecting after {} attempts", n);
        });
    }

    public void connect() {
        doConnect();
    }

    private void doConnect() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(orchestratorUrl), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket webSocket) {
                        connected.set(true);
                        reconnect.reset();
                        send(webSocket, auth.helloFrame());
                    }
                    @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        handleFrame(webSocket, data.toString());
                        return null;
                    }
                    @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        connected.set(false);
                        scheduleReconnect();
                        return null;
                    }
                    @Override public void onError(WebSocket webSocket, Throwable error) {
                        LOG.warn("bridge websocket error: {}", error.getMessage());
                        connected.set(false);
                        scheduleReconnect();
                    }
                })
                .join();
        running.set(true);
        if (heartbeatThread == null || !heartbeatThread.isAlive()) {
            heartbeatThread = startHeartbeat();
        }
    }

    private Thread startHeartbeat() {
        Thread t = new Thread(() -> {
            while (running.get()) {
                try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }
                if (connected.get()) {
                    try { send(ws, Map.of("type", "heartbeat")); } catch (Exception e) { /* drop */ }
                }
            }
        }, "bridge-heartbeat");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        if (reconnectThread != null && reconnectThread.isAlive()) return;
        reconnectThread = new Thread(() -> {
            while (running.get() && !connected.get()) {
                int delay = reconnect.nextDelayMs();
                if (delay < 0) return;
                LOG.info("bridge: reconnecting in {} ms (attempt {})", delay, reconnect.attempts());
                try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
                if (!running.get() || connected.get()) return;
                try { doConnect(); }
                catch (Exception e) { LOG.warn("bridge reconnect failed: {}", e.getMessage()); }
            }
        }, "bridge-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void handleFrame(WebSocket ws, String text) {
        try {
            JsonNode msg = MAPPER.readTree(text);
            String type = msg.path("type").asText();
            switch (type) {
                case "challenge" -> {
                    String nonce = msg.path("nonce").asText();
                    auth.onChallenge(nonce);
                    send(ws, auth.authFrame());
                }
                case "auth-ok" -> {
                    auth.onAuthOk();
                    LOG.info("bridge: authenticated");
                }
                case "auth-fail" -> {
                    auth.onAuthFailed();
                    LOG.warn("bridge: auth failed — rotating token and retrying");
                    // try again with a fresh challenge next time
                    auth.reset();
                    scheduleReconnect();
                }
                case "session-start" -> {
                    String sessionId = msg.path("session_id").asText();
                    AetherCodeEngine engine = AetherCodeEngine.builder().build();
                    localEngines.put(sessionId, engine);
                    send(ws, Map.of("type", "session-ack", "session_id", sessionId));
                }
                case "tool-call" -> {
                    String sessionId = msg.path("session_id").asText();
                    String toolName = msg.path("tool").asText();
                    JsonNode args = msg.path("arguments");
                    AetherCodeEngine engine = localEngines.get(sessionId);
                    if (engine == null) return;
                    engine.appState().toolPool().stream()
                            .filter(t -> t.name().equals(toolName))
                            .findFirst()
                            .ifPresent(t -> {
                                try {
                                    java.util.Map<String, Object> input = MAPPER.convertValue(args,
                                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                                    Tool.ToolResult res = t.call(input, Tool.CallContext.of(sessionId)).get();
                                    String output = res == null ? "" : String.valueOf(res.output());
                                    boolean isError = res != null && res.isError();
                                    send(ws, Map.of(
                                            "type", "tool-result",
                                            "session_id", sessionId,
                                            "tool", toolName,
                                            "output", output,
                                            "is_error", isError
                                    ));
                                } catch (Exception e) {
                                    send(ws, Map.of(
                                            "type", "tool-error",
                                            "session_id", sessionId,
                                            "tool", toolName,
                                            "error", e.getMessage()
                                    ));
                                }
                            });
                }
                case "shutdown" -> close();
                default -> LOG.debug("unhandled bridge frame: {}", type);
            }
        } catch (Exception e) {
            LOG.warn("frame parse failed: {}", e.getMessage());
        }
    }

    private void send(WebSocket ws, Map<String, Object> payload) {
        try {
            ws.sendText(MAPPER.writeValueAsString(payload), true);
        } catch (Exception e) {
            LOG.warn("send failed: {}", e.getMessage());
        }
    }

    public void rotateToken(String newToken) {
        this.sessionToken = newToken;
        this.auth.setToken(newToken);
        this.auth.reset();
    }

    public boolean isConnected() { return connected.get(); }
    public ReconnectStrategy reconnect() { return reconnect; }
    public BridgeAuth auth() { return auth; }

    public void close() {
        running.set(false);
        if (heartbeatThread != null) heartbeatThread.interrupt();
        if (reconnectThread != null) reconnectThread.interrupt();
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }
}
