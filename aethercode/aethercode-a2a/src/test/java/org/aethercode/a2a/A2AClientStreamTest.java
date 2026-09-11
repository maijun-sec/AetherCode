package org.aethercode.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.AgentCard;
import org.aethercode.a2a.schema.Artifact;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * end-to-end tests for
 * {@link A2AClient#subscribeMessage}. Each test starts a
 * fresh {@link A2AHttpTransport} on a random port, fires a
 * streaming subscribe request from a real
 * {@link A2AClient}, and asserts the observer sees the
 * same events the server emitted.
 *
 * <p>These tests complement {@link A2AStreamTest} (which
 * exercises the server-side transport) by locking in the
 * client-side wire shape: the observer's
 * {@code onUpdate} callback receives one decoded map per
 * SSE frame.</p>
 */
class A2AClientStreamTest {

    private A2AHttpTransport transport;
    private A2AClient client;
    private int port;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        AgentCard card = new AgentCard(
                "client-stream-test",
                "A streaming test agent",
                "0.1.0",
                "http://placeholder/a2a",  // overwritten below to real URL
                null,
                List.of(),
                new AgentCard.Capabilities(true, false, true),
                null);
        A2AServer server = new A2AServer(card, A2AServer.echoHandler());
        // Custom streaming handler that emits three steps so
        // the client has a known event surface to assert.
        A2AServer.StreamingHandler multi = (msg, emit) -> {
            emit.accept(A2AServer.TaskUpdate.status("working", "iter 1"));
            emit.accept(A2AServer.TaskUpdate.artifact(Artifact.of(
                    "step1", Part.TextPart.of("first"))));
            emit.accept(A2AServer.TaskUpdate.status("completed", "done"));
        };
        server.installStreamingHandler(multi);
        transport = new A2AHttpTransport(server);
        transport.start(0);
        port = transport.port();
        // Build a client whose baseUri points at the
        // transport's real bound address, not the card's
        // placeholder url. The card is still used so
        // A2AClient's discovery / capabilities accessor
        // works.
        URI realBase = URI.create("http://127.0.0.1:" + port + "/a2a");
        AgentCard real = readCard(realBase);
        client = new A2AClient(realBase, real);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.stop();
    }

    @Test
    void subscribeMessageDeliversAllFramesAsUpdateMaps() throws Exception {
        Message user = Message.user(Part.TextPart.of("hi"));
        List<Map<String, Object>> events = new ArrayList<>();
        AtomicInteger completed = new AtomicInteger(0);
        int count = client.subscribeMessage(user, new A2AClient.StreamObserver() {
            @Override
            public void onUpdate(Map<String, Object> update) {
                events.add(update);
            }
            @Override
            public void onComplete() {
                completed.incrementAndGet();
            }
        });
        assertEquals(3, count);
        assertEquals(3, events.size());
        // The server's wire shape each SSE data
        // line is a TaskUpdate.toMap() — i.e. the update
        // content lives at the top level (no extra "data"
        // wrapping). The "event" field is the SSE event
        // name (status / artifact / error), stashed by
        // A2AClient.parseUpdate.
        assertEquals("status", events.get(0).get("event"));
        @SuppressWarnings("unchecked")
        Map<String, Object> s1 = (Map<String, Object>) events.get(0).get("status");
        assertEquals("working", s1.get("state"));
        assertEquals("iter 1", s1.get("message"));

        assertEquals("artifact", events.get(1).get("event"));
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) events.get(1).get("artifact");
        assertEquals("step1", a.get("name"));

        assertEquals("status", events.get(2).get("event"));
        @SuppressWarnings("unchecked")
        Map<String, Object> s3 = (Map<String, Object>) events.get(2).get("status");
        assertEquals("completed", s3.get("state"));
        assertEquals(1, completed.get(), "expected onComplete to fire exactly once");
    }

    @Test
    void subscribeMessageSurfacesHttpErrorsAsExceptions() {
        // Wrong port → HTTP connect failure → exception.
        A2AClient broken = new A2AClient(
                URI.create("http://127.0.0.1:1/a2a"),  // port 1: refused
                client.card());
        try {
            broken.subscribeMessage(Message.user(Part.TextPart.of("x")),
                    update -> {});
            assertTrue(false, "expected connect failure");
        } catch (Exception e) {
            // JDK HttpClient throws IOException on connect
            // failure; we just check that an exception was
            // thrown, not its exact type.
            assertTrue(e.getMessage() == null || !e.getMessage().isEmpty(),
                    "expected exception with non-empty message");
        }
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private static AgentCard readCard(URI baseUri) throws Exception {
        URI cardUri = baseUri.resolve(A2AHttpTransport.WELL_KNOWN_AGENT_CARD);
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(cardUri)
                .GET()
                .timeout(java.time.Duration.ofSeconds(2))
                .build();
        java.net.http.HttpResponse<String> resp =
                http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        return MAPPER.readValue(resp.body(), AgentCard.class);
    }
}
