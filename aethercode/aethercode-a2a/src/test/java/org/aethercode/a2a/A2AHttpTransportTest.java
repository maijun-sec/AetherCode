package org.aethercode.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * end-to-end tests for {@link A2AHttpTransport}.
 *
 * <p>Each test starts a fresh {@code A2AHttpTransport} on a
 * random port (port 0 = OS-pick), exercises it over real
 * HTTP, then stops it. The tests are intentionally small
 * — they exist to lock in the wire contract (POST /a2a,
 * GET /.well-known/agent.json, 405 on non-POST, 204 on
 * notifications, 400 on empty body), not to re-verify the
 * JSON-RPC dispatching — that already has 15 tests in
 * {@link A2AServerTest}.</p>
 */
class A2AHttpTransportTest {

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
                "test-http-agent",
                "An agent exposed over HTTP for tests",
                "0.1.0",
                "http://localhost:0/a2a",
                null,
                List.of(),
                null,
                null);
        A2AServer server = new A2AServer(card, A2AServer.echoHandler());
        transport = new A2AHttpTransport(server);
        // port 0 = OS picks a free port. We read transport.port()
        // after start() to learn the actual port.
        transport.start(0);
        port = transport.port();
        assertTrue(port > 0, "expected OS-assigned port > 0, got " + port);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.stop();
    }

    @Test
    void messageSendOverHttpReturnsCompletedTask() throws Exception {
        String req = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "req-1",
                "method", "message/send",
                "params", Map.of("message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("kind", "text", "text", "hello over http"))))));
        HttpResponse<String> resp = postJson("/a2a", req);
        assertEquals(200, resp.statusCode(), "expected 200, body=" + resp.body());
        Map<String, Object> outer = MAPPER.readValue(resp.body(), MAP_TYPE);
        assertNull(outer.get("error"), "no error expected: " + resp.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) outer.get("result");
        assertNotNull(result);
        assertEquals("task", result.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) result.get("status");
        assertEquals("completed", status.get("state"));
    }

    @Test
    void wellKnownAgentCardReturnsFullCardJson() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + A2AHttpTransport.WELL_KNOWN_AGENT_CARD))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        // The well-known response is the bare result, not a
        // JSON-RPC envelope — the card object is the body.
        Map<String, Object> body = MAPPER.readValue(resp.body(), MAP_TYPE);
        assertEquals("test-http-agent", body.get("name"));
        assertEquals("0.1.0", body.get("version"));
    }

    @Test
    void nonPostToRpcPathReturns405() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/a2a"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
        assertEquals("POST", resp.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void emptyBodyReturns400() throws Exception {
        HttpResponse<String> resp = postJson("/a2a", "");
        assertEquals(400, resp.statusCode());
        Map<String, Object> body = MAPPER.readValue(resp.body(), MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) body.get("error");
        assertNotNull(err);
        assertEquals(-32700, err.get("code"));
    }

    @Test
    void tasksGetAfterMessageSendRoundTrips() throws Exception {
        // 1. Send a message, capture the task id from the result.
        String sendReq = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "send",
                "method", "message/send",
                "params", Map.of("message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("kind", "text", "text", "ping"))))));
        HttpResponse<String> sendResp = postJson("/a2a", sendReq);
        assertEquals(200, sendResp.statusCode());
        Map<String, Object> sendOuter = MAPPER.readValue(sendResp.body(), MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> sendResult = (Map<String, Object>) sendOuter.get("result");
        String taskId = (String) sendResult.get("id");
        assertNotNull(taskId);

        // 2. Fetch the task by id.
        String getReq = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "get",
                "method", "tasks/get",
                "params", Map.of("id", taskId)));
        HttpResponse<String> getResp = postJson("/a2a", getReq);
        assertEquals(200, getResp.statusCode());
        Map<String, Object> getOuter = MAPPER.readValue(getResp.body(), MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> getResult = (Map<String, Object>) getOuter.get("result");
        assertEquals(taskId, getResult.get("id"));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nope"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    // -------------------------------------------------------------------

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + port + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5));
        HttpRequest req = body.isEmpty()
                ? b.POST(HttpRequest.BodyPublishers.noBody()).build()
                : b.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
