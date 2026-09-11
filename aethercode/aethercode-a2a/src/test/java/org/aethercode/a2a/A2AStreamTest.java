package org.aethercode.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.AgentCard;
import org.aethercode.a2a.schema.Artifact;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@code POST /a2a/stream}. Each test starts a fresh
 * {@code A2AHttpTransport} on a random port, fires a single
 * {@code message/sendSubscribe} JSON-RPC, then reads the response stream as a
 * list of {@code SseEvent}s.
 *
 * <p>These tests include a minimal SSE parser that only recognizes the
 * {@code event:} / {@code id:} / {@code data:} + blank-line format emitted
 * by this service; real clients should use a mature SSE library.</p>
 */
class A2AStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private A2AHttpTransport transport;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        AgentCard card = new AgentCard(
                "test-stream-agent",
                "An agent that streams over SSE for tests",
                "0.1.0",
                "http://localhost:0/a2a",
                null,
                List.of(),
                new AgentCard.Capabilities(true, false, true),
                null);
        A2AServer server = new A2AServer(card, A2AServer.echoHandler());
        transport = new A2AHttpTransport(server);
        transport.start(0);
        port = transport.port();
        assertTrue(port > 0, "expected OS-assigned port > 0, got " + port);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.stop();
    }

    // /a2a/stream endpoint cases

    @Test
    void streamEndpointEchoesSingleArtifactAsThreeEventStream() throws Exception {
        // Default fallback: with no streaming handler installed,
        // message/sendSubscribe falls back to the sync handler which emits
        // working / artifact / completed as three events.
        String req = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "stream-1",
                "method", "message/sendSubscribe",
                "params", Map.of("message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("kind", "text", "text", "ping stream"))))));
        List<SseEvent> events = sendAndCollect(req, Duration.ofSeconds(5));
        assertEquals(3, events.size(), "expected 3 events, got " + events.size());
        assertEquals("status", events.get(0).eventName);
        assertEquals(Map.of("state", "working"),
                events.get(0).data.get("status"));
        assertEquals("artifact", events.get(1).eventName);
        @SuppressWarnings("unchecked")
        Map<String, Object> art = (Map<String, Object>) events.get(1).data.get("artifact");
        assertEquals("echo", art.get("name"));
        assertEquals("status", events.get(2).eventName);
        assertEquals(Map.of("state", "completed"),
                events.get(2).data.get("status"));
        // ids are per-request monotonic
        assertEquals("1", events.get(0).id);
        assertEquals("2", events.get(1).id);
        assertEquals("3", events.get(2).id);
    }

    @Test
    void streamEndpointWithCustomHandlerEmitsOrderedMultiStepEvents() throws Exception {
        // Replace the default echo handler with a multi-step
        // streaming handler. The handler emits two status
        // updates and two artifacts before terminating.
        AgentCard card = new AgentCard(
                "multi-step-agent",
                "Streams three steps",
                "0.1.0",
                "http://localhost:0/a2a",
                null, List.of(), null, null);
        A2AServer server = new A2AServer(card, A2AServer.echoHandler());
        A2AHttpTransport local = new A2AHttpTransport(server);
        // Custom streaming handler — emits step1, step2, then completed.
        A2AServer.StreamingHandler multi = (msg, emit) -> {
            emit.accept(A2AServer.TaskUpdate.status("working", "step 1"));
            emit.accept(A2AServer.TaskUpdate.artifact(Artifact.of(
                    "step1", Part.TextPart.of("first step"))));
            emit.accept(A2AServer.TaskUpdate.status("working", "step 2"));
            emit.accept(A2AServer.TaskUpdate.artifact(Artifact.of(
                    "step2", Part.TextPart.of("second step"))));
            emit.accept(A2AServer.TaskUpdate.status("completed", "all done"));
        };
        server.installStreamingHandler(multi);
        try {
            local.start(0);
            int p = local.port();
            String req = MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", "stream-2",
                    "method", "message/sendSubscribe",
                    "params", Map.of("message", Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("kind", "text", "text", "go"))))));
            List<SseEvent> events = postStream(p, req, Duration.ofSeconds(5));
            assertEquals(5, events.size(), "expected 5 events, got " + events.size());
            assertEquals("status", events.get(0).eventName);
            assertEquals("working", ((Map<?, ?>) events.get(0).data.get("status")).get("state"));
            assertEquals("artifact", events.get(1).eventName);
            assertEquals("step1", ((Map<?, ?>) events.get(1).data.get("artifact")).get("name"));
            assertEquals("status", events.get(2).eventName);
            assertEquals("working", ((Map<?, ?>) events.get(2).data.get("status")).get("state"));
            assertEquals("artifact", events.get(3).eventName);
            assertEquals("step2", ((Map<?, ?>) events.get(3).data.get("artifact")).get("name"));
            assertEquals("status", events.get(4).eventName);
            assertEquals("completed", ((Map<?, ?>) events.get(4).data.get("status")).get("state"));
        } finally {
            local.stop();
        }
    }

    @Test
    void streamEndpointContentTypeIsEventStream() throws Exception {
        String req = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "stream-3",
                "method", "message/sendSubscribe",
                "params", Map.of("message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("kind", "text", "text", "x"))))));
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + A2AHttpTransport.STREAM_RPC_PATH))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(req))
                .build();
        HttpResponse<String> resp = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        // The text/event-stream content type is the load-bearing
        // signal — clients decide between streaming and
        // single-shot based on this header.
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        assertTrue(ct.startsWith("text/event-stream"),
                "expected text/event-stream, got: " + ct);
        // Cache-Control: no-cache is required for SSE proxies.
        assertEquals("no-cache",
                resp.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void streamEndpointNonPostReturns405() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + A2AHttpTransport.STREAM_RPC_PATH))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
        assertEquals("POST", resp.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void streamEndpointEmptyBodyReturns400() throws Exception {
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + A2AHttpTransport.STREAM_RPC_PATH))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
    }

    @Test
    void streamEndpointRejectsMessageSendMethodWithErrorFrame() throws Exception {
        // /a2a/stream is for sendSubscribe only. Sending
        // message/send must produce a single error event
        // and close the stream.
        String req = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "stream-4",
                "method", "message/send",
                "params", Map.of("message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("kind", "text", "text", "x"))))));
        List<SseEvent> events = sendAndCollect(req, Duration.ofSeconds(5));
        assertEquals(1, events.size(), "expected 1 error event, got " + events.size());
        assertEquals("error", events.get(0).eventName);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) events.get(0).data;
        assertEquals(JsonRpcSupport.Codes.METHOD_NOT_FOUND, err.get("code"));
    }

    @Test
    void streamEndpointRejectsMalformedJsonWithErrorFrame() throws Exception {
        List<SseEvent> events = sendAndCollectRaw("not json {", Duration.ofSeconds(5));
        assertEquals(1, events.size());
        assertEquals("error", events.get(0).eventName);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) events.get(0).data;
        assertEquals(-32700, err.get("code"));
    }

    @Test
    void streamEndpointMissingMessageParamYieldsErrorFrame() throws Exception {
        String req = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "stream-5",
                "method", "message/sendSubscribe",
                "params", Map.of()));
        List<SseEvent> events = sendAndCollect(req, Duration.ofSeconds(5));
        assertEquals(1, events.size());
        assertEquals("error", events.get(0).eventName);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) events.get(0).data;
        assertEquals(JsonRpcSupport.Codes.INVALID_PARAMS, err.get("code"));
    }

    @Test
    void inProcessStreamSubscribeFallsBackToSingleStep() throws Exception {
        // Direct call (not over HTTP) — useful to confirm the
        // server-side fallback path without a network round-trip.
        A2AServer s = new A2AServer(
                new AgentCard("in-proc", "x", "0.1.0", "http://localhost:0/a2a",
                        null, List.of(), null, null),
                A2AServer.echoHandler());
        List<Map<String, Object>> events = new ArrayList<>();
        s.handleStreamSubscribe(
                MAPPER.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", "direct-1",
                        "method", "message/sendSubscribe",
                        "params", Map.of("message", Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("kind", "text", "text", "hi")))))),
                events::add);
        assertEquals(3, events.size());
        assertEquals("status", events.get(0).get("event"));
        assertEquals("artifact", events.get(1).get("event"));
        assertEquals("status", events.get(2).get("event"));
    }

    @Test
    void installStreamingHandlerReturnsSameInstanceForChaining() throws Exception {
        A2AServer s = new A2AServer(
                new AgentCard("chain", "x", "0.1.0", "http://localhost:0/a2a",
                        null, List.of(), null, null),
                A2AServer.echoHandler());
        AtomicInteger n = new AtomicInteger(0);
        A2AServer back = s.installStreamingHandler((msg, emit) -> {
            n.incrementAndGet();
            emit.accept(A2AServer.TaskUpdate.status("completed", null));
        });
        assertEquals(s, back, "expected chainable install");
        List<Map<String, Object>> events = new ArrayList<>();
        s.handleStreamSubscribe(
                MAPPER.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", "chain-1",
                        "method", "message/sendSubscribe",
                        "params", Map.of("message", Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("kind", "text", "text", "x")))))),
                events::add);
        assertEquals(1, n.get());
        assertEquals(1, events.size());
        // data shape: { "status": { "state": "completed" } }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) events.get(0).get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) data.get("status");
        assertEquals("completed", status.get("state"));
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private List<SseEvent> sendAndCollect(String body, Duration timeout) throws Exception {
        return postStream(port, body, timeout);
    }

    private List<SseEvent> sendAndCollectRaw(String body, Duration timeout) throws Exception {
        return postStreamRaw(port, body, timeout);
    }

    private static List<SseEvent> postStream(int port, String body, Duration timeout) throws Exception {
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + A2AHttpTransport.STREAM_RPC_PATH))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<java.io.InputStream> resp = HTTP.send(httpReq,
                HttpResponse.BodyHandlers.ofInputStream());
        return parseSseStream(resp.body());
    }

    private static List<SseEvent> postStreamRaw(int port, String body, Duration timeout) throws Exception {
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + A2AHttpTransport.STREAM_RPC_PATH))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<java.io.InputStream> resp = HTTP.send(httpReq,
                HttpResponse.BodyHandlers.ofInputStream());
        return parseSseStream(resp.body());
    }

    /** Minimal SSE frame parser. Reads {@code event:}, {@code id:},
     *  and {@code data:} lines, terminated by a blank line. The
     *  {@code data:} line's payload is JSON-parsed to a map. */
    private static List<SseEvent> parseSseStream(java.io.InputStream raw) throws Exception {
        List<SseEvent> out = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(raw, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            String eventName = null;
            String id = null;
            StringBuilder dataBuf = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    // End of frame. Flush if we have any data.
                    if (dataBuf.length() > 0 || eventName != null || id != null) {
                        out.add(new SseEvent(eventName, id, parseData(dataBuf.toString())));
                    }
                    eventName = null;
                    id = null;
                    dataBuf.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    // SSE comment line — ignore.
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) continue; // malformed
                String field = line.substring(0, colon);
                String value = line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                switch (field) {
                    case "event" -> eventName = value;
                    case "id" -> id = value;
                    case "data" -> {
                        if (dataBuf.length() > 0) dataBuf.append('\n');
                        dataBuf.append(value);
                    }
                    default -> { /* ignore retry:, etc. */ }
                }
            }
            // Final flush if stream ended without trailing blank line.
            if (dataBuf.length() > 0 || eventName != null || id != null) {
                out.add(new SseEvent(eventName, id, parseData(dataBuf.toString())));
            }
        }
        return out;
    }

    private static Map<String, Object> parseData(String s) {
        if (s == null || s.isEmpty()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(s, MAP_TYPE);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("_raw", s);
            m.put("_error", e.getMessage());
            return m;
        }
    }

    private record SseEvent(String eventName, String id, Map<String, Object> data) {}
}
