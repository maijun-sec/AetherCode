package org.aethercode.mcp.sse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.mcp.stdio.McpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * MCP client over Server-Sent Events. The flow is two-step (per the MCP SSE spec):
 *
 * <ol>
 *   <li>Open a long-lived GET to the server's SSE endpoint. Receive a session id and an
 *       "endpoint" URL the client should POST JSON-RPC requests to.</li>
 *   <li>Send each JSON-RPC request as an HTTP POST to that endpoint. The server streams the
 *       response back over the same SSE channel as one or more {@code data:} events.</li>
 * </ol>
 *
 * <p>This is the "MCP over HTTP+SSE" transport. We deliberately keep the protocol surface
 * narrow — {@code initialize}, {@code tools/list}, {@code tools/call} — so the rest of the
 * framework can plug any transport in via {@link org.aethercode.mcp.stdio.McpProtocol}.
 */
public class SseMcpClient {

    private static final Logger LOG = LoggerFactory.getLogger(SseMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String sseUrl;
    private final OkHttpClient http;
    private final AtomicLong idGen = new AtomicLong();
    private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
    private volatile String postEndpoint;
    private volatile String sessionId;
    private EventSource es;

    public SseMcpClient(String sseUrl) {
        this.sseUrl = sseUrl;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(0)) // infinite — SSE is long-lived
                .build();
    }

    public boolean isConnected() { return sessionId != null; }
    public String sessionId() { return sessionId; }

    /** Open the SSE channel and complete the MCP handshake. */
    public void connect() throws Exception {
        Request req = new Request.Builder().url(sseUrl).header("Accept", "text/event-stream").build();
        EventSourceListener listener = new EventSourceListener() {
            @Override public void onOpen(EventSource es, Response r) {}
            @Override public void onEvent(EventSource es, String id, String type, String data) {
                if (data == null || data.isBlank()) return;
                try {
                    JsonNode n = MAPPER.readTree(data);
                    // The first server event typically carries endpoint + session id
                    if (n.has("endpoint") && postEndpoint == null) {
                        postEndpoint = n.get("endpoint").asText();
                    }
                    if (n.has("sessionId") && sessionId == null) {
                        sessionId = n.get("sessionId").asText();
                    }
                    incoming.offer(n);
                } catch (Exception e) {
                    LOG.warn("SSE parse failed: {}", e.getMessage());
                }
            }
            @Override public void onClosed(EventSource es) {}
            @Override public void onFailure(EventSource es, Throwable t, Response r) {
                if (t != null) LOG.warn("SSE failure: {}", t.getMessage());
            }
        };
        this.es = EventSources.createFactory(http).newEventSource(req, listener);
        // Wait up to 5s for the endpoint URL.
        long deadline = System.currentTimeMillis() + 5_000;
        while (postEndpoint == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (postEndpoint == null) {
            throw new IllegalStateException("MCP SSE server did not return an endpoint");
        }
        // Send initialize
        sendAndWait("initialize", MAPPER.createObjectNode()
                .put("protocolVersion", "2025-06-18")
                .putObject("clientInfo").put("name", "aethercode").put("version", "0.1.0"));
    }

    public void close() {
        if (es != null) es.cancel();
    }

    /** List the tools exposed by the server. */
    public List<Tool> listTools() {
        if (!isConnected()) throw new IllegalStateException("not connected");
        JsonNode resp = sendAndWait("tools/list", MAPPER.createObjectNode());
        JsonNode tools = resp.path("result").path("tools");
        List<Tool> out = new ArrayList<>();
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                String name = t.path("name").asText();
                String desc = t.path("description").asText("");
                Map<String, Object> schema = MAPPER.convertValue(
                        t.path("inputSchema"), new TypeReference<>() {});
                ToolDef def = new ToolDef(
                        "mcp__" + safeName(sseUrl) + "__" + name,
                        "[mcp-sse] " + desc,
                        schema,
                        (input, ctx) -> CompletableFuture.completedFuture(callTool(name, input))
                );
                out.add(Tools.build(def));
            }
        }
        return out;
    }

    private Tool.ToolResult callTool(String name, Map<String, Object> input) {
        try {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", name);
            params.set("arguments", MAPPER.valueToTree(input));
            JsonNode resp = sendAndWait("tools/call", params);
            JsonNode result = resp.path("result");
            boolean isError = result.path("isError").asBoolean(false);
            StringBuilder text = new StringBuilder();
            for (JsonNode c : result.path("content")) {
                if ("text".equals(c.path("type").asText())) {
                    text.append(c.path("text").asText(""));
                } else {
                    text.append(MAPPER.writeValueAsString(c));
                }
            }
            return isError
                    ? Tool.ToolResult.error(text.length() == 0 ? "tool error" : text.toString())
                    : Tool.ToolResult.of(text.toString());
        } catch (Exception e) {
            return Tool.ToolResult.error("mcp sse call failed: " + e.getMessage());
        }
    }

    private JsonNode sendAndWait(String method, ObjectNode params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        long id = idGen.incrementAndGet();
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);

        try {
            String url = resolvePostUrl();
            Request post = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .post(RequestBody.create(MAPPER.writeValueAsString(req), JSON))
                    .build();
            // POST returns either 202 Accepted (response will arrive via SSE) or the response directly.
            try (Response resp = http.newCall(post).execute()) {
                if (resp.code() == 202) {
                    // wait for SSE delivery
                } else if (resp.isSuccessful()) {
                    String body = resp.body() == null ? "" : resp.body().string();
                    if (!body.isBlank()) {
                        JsonNode n = MAPPER.readTree(body);
                        if (n.path("id").asLong() == id) return n;
                    }
                } else {
                    throw new RuntimeException("MCP SSE POST failed: HTTP " + resp.code());
                }
            }
            // Wait for the matching SSE event.
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode ev = incoming.poll(1, TimeUnit.SECONDS);
                if (ev == null) continue;
                if (ev.has("id") && ev.path("id").asLong() == id) {
                    if (ev.has("error")) throw new RuntimeException("MCP error: " + ev.path("error"));
                    return ev;
                }
            }
            throw new RuntimeException("MCP SSE timeout waiting for response id=" + id);
        } catch (Exception e) {
            throw new RuntimeException("MCP SSE send failed: " + e.getMessage(), e);
        }
    }

    private String resolvePostUrl() {
        if (postEndpoint.startsWith("http://") || postEndpoint.startsWith("https://")) {
            return postEndpoint;
        }
        // relative endpoint — resolve against the SSE URL
        try {
            URL base = new URL(sseUrl);
            return new URL(base, postEndpoint).toString();
        } catch (Exception e) {
            return sseUrl;
        }
    }

    /** Generic JSON-RPC method. R3 callers use this for prompts/resources. */
    public JsonNode rpc(String method, Map<String, Object> params) {
        ObjectNode p = MAPPER.createObjectNode();
        if (params != null) {
            p.setAll((com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.valueToTree(params));
        }
        return sendAndWait(method, p);
    }

    private static String safeName(String s) {
        if (s == null) return "anon";
        try {
            String host = new URL(s).getHost();
            return host == null ? "anon" : host.replace('.', '-');
        } catch (Exception e) {
            return "anon";
        }
    }
}
