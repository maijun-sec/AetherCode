package org.aethercode.a2a;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.aethercode.a2a.schema.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * HTTP transport for {@link A2AServer}. Built on top of the JDK's built-in
 * {@link HttpServer}, it adds no new dependencies and follows the same
 * style as BankServer.
 *
 * <h2>Path contract</h2>
 * <ul>
 *   <li>{@code POST /a2a} — synchronous JSON-RPC, one request, one response.</li>
 *   <li>{@code POST /a2a/stream} — {@code message/sendSubscribe} streaming response, framed as {@code text/event-stream}.</li>
 *   <li>{@code GET /.well-known/agent.json} — AgentCard discovery endpoint, fetched by clients before issuing tasks.</li>
 *   <li>Other paths return 404; non-POST requests on {@code /a2a} and {@code /a2a/stream} return 405.</li>
 * </ul>
 *
 * <h2>Threading model</h2>
 * <p>A shared {@link Executor} (a cached thread pool by default) handles requests concurrently, relying on the thread safety of the {@code ConcurrentHashMap} maintained internally by {@link A2AServer}.</p>
 */
public class A2AHttpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(A2AHttpTransport.class);

    /** A2A spec: AgentCard discovery path. */
    public static final String WELL_KNOWN_AGENT_CARD = "/.well-known/agent.json";

    /** A2A spec: tasks are delivered via POST; the path is declared by the AgentCard. Default: {@code /a2a}. */
    public static final String DEFAULT_RPC_PATH = "/a2a";

    /** Streaming endpoint that shares the RPC path prefix; clients distinguish sync from streaming by the path suffix. */
    public static final String STREAM_RPC_PATH = "/a2a/stream";

    private final A2AServer server;
    private final String rpcPath;
    private final Executor executor;
    private HttpServer http;
    private int boundPort = -1;

    /**
     * @param server   the in-process A2A server to expose.
     * @param rpcPath  path that accepts JSON-RPC POSTs. Defaults to {@link #DEFAULT_RPC_PATH}.
     * @param executor the handler thread pool. Defaults to a cached thread pool.
     */
    public A2AHttpTransport(A2AServer server, String rpcPath, Executor executor) {
        this.server = server;
        this.rpcPath = rpcPath == null ? DEFAULT_RPC_PATH : rpcPath;
        this.executor = executor == null ? Executors.newCachedThreadPool() : executor;
        // The streaming path shares the rpcPath prefix and is fixed by the A2A spec, so it is not configurable.
    }

    public A2AHttpTransport(A2AServer server) {
        this(server, DEFAULT_RPC_PATH, null);
    }

    public A2AServer server() { return server; }

    /** Port the server is bound to. -1 before {@link #start(int)}. */
    public int port() { return boundPort; }

    public boolean isRunning() { return http != null; }

    /**
     * Bind to {@code 0.0.0.0:port} and start accepting
     * connections. Idempotent: a second call is a no-op while
     * the server is running.
     */
    public synchronized void start(int port) throws IOException {
        if (http != null) {
            LOG.warn("A2AHttpTransport already running on port {}", boundPort);
            return;
        }
        HttpServer h = HttpServer.create(new InetSocketAddress(port), 0);
        h.createContext(rpcPath, new RpcHandler());
        // The streaming endpoint coexists with the sync one; clients pick based on need.
        h.createContext(STREAM_RPC_PATH, new SseHandler());
        h.createContext(WELL_KNOWN_AGENT_CARD, new AgentCardHandler());
        h.setExecutor(executor);
        h.start();
        this.http = h;
        this.boundPort = h.getAddress().getPort();
        LOG.info("A2AHttpTransport listening on http://0.0.0.0:{} (rpc={}, stream={}, well-known={})",
                boundPort, rpcPath, STREAM_RPC_PATH, WELL_KNOWN_AGENT_CARD);
    }

    public synchronized void stop() {
        if (http == null) return;
        http.stop(0);
        http = null;
        boundPort = -1;
        LOG.info("A2AHttpTransport stopped");
    }

    // HTTP handlers

    private final class RpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    // The A2A spec requires the task endpoint to accept POST only; the Allow header
                    // must be set before sendResponseHeaders, since the JDK HttpServer flushes
                    // response headers at that point.
                    exchange.getResponseHeaders().add("Allow", "POST");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String body = readBody(exchange.getRequestBody());
                if (body.isEmpty()) {
                    sendJson(exchange, 400,
                            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":"
                                    + "{\"code\":-32700,\"message\":\"empty body\"}}");
                    return;
                }
                String reply = server.handleLine(body);
                if (reply == null) {
                    // JSON-RPC 2.0 specifies that notifications receive no response; here we return 204 No Content.
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                sendJson(exchange, 200, reply);
            } catch (Exception e) {
                LOG.error("rpc handler error: {}", e.getMessage(), e);
                try {
                    sendJson(exchange, 500,
                            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":"
                                    + "{\"code\":-32603,\"message\":\"internal: "
                                    + e.getMessage().replace("\"", "\\\"") + "\"}}");
                } catch (IOException ignored) {
                    // exchange already closed
                }
            }
        }
    }

    /**
     * Server-Sent Events handler for {@code message/sendSubscribe}, parallel to
     * {@link RpcHandler} but writes a stream of SSE frames instead of a single JSON-RPC response.
     *
     * <p>Each frame is shaped as {@code event: ...\nid: ...\ndata: ...\n\n}. The {@code id:} field is a
     * monotonically increasing counter within a request, which clients may use with
     * {@code Last-Event-ID} to resume (this service only assigns ids; reconnect semantics are
     * handled by the client).</p>
     *
     * <p>Error events are signaled with {@code event: error}; the response is terminal and closed
     * after that frame is written.</p>
     */
    private final class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().add("Allow", "POST");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String body = readBody(exchange.getRequestBody());
                if (body.isEmpty()) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                // Switch to chunked transfer (Content-Length 0 / -1) so the response stays open
                // while the streaming handler keeps emitting.
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
                exchange.sendResponseHeaders(200, 0);
                // Wrap the response stream in a BufferedWriter and flush after each frame so
                // emissions reach the wire immediately.
                com.fasterxml.jackson.databind.ObjectMapper m =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                try (OutputStream os = exchange.getResponseBody();
                     java.io.BufferedWriter w = new java.io.BufferedWriter(
                             new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                    final long[] counter = {0};
                    server.handleStreamSubscribe(body, update -> {
                        try {
                            counter[0]++;
                            String eventName = (String) update.get("event");
                            Object data = update.get("data");
                            byte[] dataBytes = m.writeValueAsBytes(data);
                            w.write("event: " + (eventName == null ? "message" : eventName));
                            w.write("\n");
                            w.write("id: " + counter[0]);
                            w.write("\n");
                            w.write("data: ");
                            w.write(new String(dataBytes, StandardCharsets.UTF_8));
                            w.write("\n\n");
                            w.flush();
                        } catch (IOException ioe) {
                            // Client disconnected mid-stream; unrecoverable, so rethrow as
                            // UncheckedIOException to let the outer scope close the exchange.
                            throw new java.io.UncheckedIOException(ioe);
                        }
                    });
                } catch (java.io.UncheckedIOException uioe) {
                    LOG.debug("SSE client disconnected: {}", uioe.getCause().getMessage());
                } catch (Exception e) {
                    LOG.error("SSE handler error: {}", e.getMessage(), e);
                    try {
                        exchange.sendResponseHeaders(500, -1);
                    } catch (IOException ignored) {
                        // exchange already closed
                    }
                }
            }
        }
    }

    private final class AgentCardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().add("Allow", "GET");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                // Route through `agent/authenticatedExtendedCard` so the well-known and JSON-RPC
                // paths share a single rendering pipeline.
                String body = server.handleLine(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"discovery\","
                                + "\"method\":\"agent/authenticatedExtendedCard\"}");
                if (body == null) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                // Strip the outer JSON-RPC envelope and return just the result; round-trip through
                // ObjectMapper to avoid hand-rolling JSON.
                com.fasterxml.jackson.databind.ObjectMapper m =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<?, ?> outer = m.readValue(body,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<?, ?>>() {});
                Object result = outer.get("result");
                byte[] out = m.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (Exception e) {
                LOG.error("agent card handler error: {}", e.getMessage(), e);
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    // helpers

    private static String readBody(InputStream in) throws IOException {
        byte[] buf = in.readAllBytes();
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
