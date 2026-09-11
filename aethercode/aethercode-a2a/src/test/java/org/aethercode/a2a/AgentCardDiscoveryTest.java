package org.aethercode.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.aethercode.a2a.schema.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Card discovery tests. Boots a tiny in-process
 * HTTP server that serves the {@code .well-known/agent-card.json}
 * document, then runs {@link AgentCardDiscovery} against it.
 */
class AgentCardDiscoveryTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/agent-card.json", ex -> {
            hits.incrementAndGet();
            byte[] body = SAMPLE_CARD_V03.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/.well-known/agent.json", ex -> {
            hits.incrementAndGet();
            byte[] body = SAMPLE_CARD_V01.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static final String SAMPLE_CARD_V03 = "{\n" +
            "  \"name\": \"research-agent\",\n" +
            "  \"description\": \"Does deep research.\",\n" +
            "  \"version\": \"1.2.3\",\n" +
            "  \"url\": \"https://research.example.com/a2a\",\n" +
            "  \"provider\": {\n" +
            "    \"organization\": \"Example Labs\",\n" +
            "    \"url\": \"https://example.com\"\n" +
            "  },\n" +
            "  \"skills\": [\n" +
            "    { \"id\": \"summarize\", \"name\": \"Summarize\",\n" +
            "      \"description\": \"Returns a TL;DR.\",\n" +
            "      \"inputModes\": [\"text\"], \"outputModes\": [\"text\"] }\n" +
            "  ],\n" +
            "  \"capabilities\": {\n" +
            "    \"streaming\": true,\n" +
            "    \"pushNotifications\": false,\n" +
            "    \"stateTransitionHistory\": true\n" +
            "  },\n" +
            "  \"authentication\": { \"schemes\": [\"bearer\"] }\n" +
            "}";

    private static final String SAMPLE_CARD_V01 = "{\n" +
            "  \"name\": \"legacy-agent\",\n" +
            "  \"description\": \"v0.1-style card\",\n" +
            "  \"url\": \"https://legacy.example.com/a2a\"\n" +
            "}";

    @Test
    void fetchV03Card() {
        AgentCardDiscovery d = new AgentCardDiscovery();
        Optional<AgentCard> got = d.fetch(baseUrl);
        assertTrue(got.isPresent());
        AgentCard c = got.get();
        assertEquals("research-agent", c.name());
        assertEquals("1.2.3", c.version());
        assertEquals("https://research.example.com/a2a", c.url());
        assertEquals(1, c.skills().size());
        assertEquals("summarize", c.skills().get(0).id());
        assertEquals(Boolean.TRUE, c.capabilities().streaming());
        assertEquals(Boolean.FALSE, c.capabilities().pushNotifications());
        assertEquals(1, c.authentication().schemes().size());
        assertEquals("bearer", c.authentication().schemes().get(0));
    }

    @Test
    void fetchFallsBackToV01Path() throws Exception {
        // Restart a server that ONLY serves the v0.1 path.
        server.stop(0);
        hits.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/agent.json", ex -> {
            hits.incrementAndGet();
            byte[] body = SAMPLE_CARD_V01.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AgentCardDiscovery d = new AgentCardDiscovery();
        Optional<AgentCard> got = d.fetch(baseUrl);
        assertTrue(got.isPresent());
        assertEquals("legacy-agent", got.get().name());
    }

    @Test
    void fetchFailsGracefully() {
        AgentCardDiscovery d = new AgentCardDiscovery();
        // Port 1 is almost certainly not bound; the connection
        // should fail and the discovery returns empty.
        Optional<AgentCard> got = d.fetch("http://127.0.0.1:1");
        assertFalse(got.isPresent());
    }

    @Test
    void fetchRejectsBadUrl() {
        AgentCardDiscovery d = new AgentCardDiscovery();
        assertFalse(d.fetch("").isPresent());
        assertFalse(d.fetch(null).isPresent());
    }
}
