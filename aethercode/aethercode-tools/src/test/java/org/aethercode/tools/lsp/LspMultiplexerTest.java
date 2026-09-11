package org.aethercode.tools.lsp;

import java.util.List;
import java.util.Map;
import org.aethercode.tools.lsp.LspMultiplexer.Handler;
import org.aethercode.tools.lsp.LspMultiplexer.Request;
import org.aethercode.tools.lsp.LspMultiplexer.Response;
import org.aethercode.tools.lsp.LspMultiplexer.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspMultiplexerTest {

    @Test
    void register_addsServer() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "file:///x"), (method, p) -> "ok");
        assertEquals(1, m.size());
    }

    @Test
    void register_rejectsNullServer() {
        LspMultiplexer m = new LspMultiplexer();
        try {
            m.register(null, (method, p) -> null);
        } catch (NullPointerException e) {
            assertNotNull(e);
        }
    }

    @Test
    void unregister_removes() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "ok");
        m.unregister("s1");
        assertEquals(0, m.size());
    }

    @Test
    void contains_findsServer() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "ok");
        assertTrue(m.contains("s1"));
        assertFalse(m.contains("s2"));
    }

    @Test
    void route_returnsResponseFromOneServer() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "result1");
        Response r = m.route("s1", Request.of("textDocument/definition")).orElse(null);
        assertNotNull(r);
        assertEquals("result1", r.result());
        assertEquals("s1", r.serverId());
    }

    @Test
    void route_returnsEmptyForUnknownServer() {
        LspMultiplexer m = new LspMultiplexer();
        assertFalse(m.route("missing", Request.of("x")).isPresent());
    }

    @Test
    void broadcast_callsAllServers() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "r1");
        m.register(new Server("s2", "kotlin", "uri"), (method, p) -> "r2");
        List<Response> out = m.broadcast(Request.of("textDocument/hover"));
        assertEquals(2, out.size());
    }

    @Test
    void broadcast_recordsLastMethod() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "x");
        m.broadcast(Request.of("textDocument/completion"));
        assertEquals("textDocument/completion", m.lastBroadcastMethod());
    }

    @Test
    void routeByLanguage_findsMatch() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "java-result");
        m.register(new Server("s2", "kotlin", "uri"), (method, p) -> "kotlin-result");
        Response r = m.routeByLanguage("kotlin", Request.of("x")).orElse(null);
        assertNotNull(r);
        assertEquals("kotlin-result", r.result());
    }

    @Test
    void routeByLanguage_returnsEmptyForNoMatch() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "x");
        assertFalse(m.routeByLanguage("python", Request.of("x")).isPresent());
    }

    @Test
    void exceptionInHandler_returnsErrorResponse() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> {
            throw new RuntimeException("boom");
        });
        Response r = m.route("s1", Request.of("x")).orElse(null);
        assertTrue(r.isError());
        assertEquals("boom", r.error());
    }

    @Test
    void history_recordsAllResponses() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "a");
        m.register(new Server("s2", "kotlin", "uri"), (method, p) -> "b");
        m.broadcast(Request.of("x"));
        assertEquals(2, m.history().size());
    }

    @Test
    void requestCount_increments() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "x");
        m.route("s1", Request.of("a"));
        m.route("s1", Request.of("b"));
        m.broadcast(Request.of("c"));
        assertEquals(3, m.requestCount());
    }

    @Test
    void allServers_listsAll() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "x");
        m.register(new Server("s2", "kotlin", "uri"), (method, p) -> "y");
        assertEquals(2, m.allServers().size());
    }

    @Test
    void clear_resetsAll() {
        LspMultiplexer m = new LspMultiplexer();
        m.register(new Server("s1", "java", "uri"), (method, p) -> "x");
        m.clear();
        assertEquals(0, m.size());
        assertEquals(0, m.history().size());
    }

    @Test
    void request_immutableParams() {
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("k", "v");
        Request r = new Request("method", params);
        params.put("k", "modified");
        assertEquals("v", r.params().get("k"));
    }

    @Test
    void response_isError() {
        Response r1 = Response.ok("s", "result");
        Response r2 = Response.err("s", "err");
        assertFalse(r1.isError());
        assertTrue(r2.isError());
    }

    @Test
    void server_handlesNullId() {
        Server s = new Server(null, "java", "uri");
        assertEquals("", s.id());
    }
}
