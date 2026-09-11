package org.aethercode.mcp.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.mcp.stdio.McpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client over a WebSocket transport. Distinct from {@code SseMcpClient}
 * (which is HTTP+SSE for the Anthropic-style MCP-over-HTTP) and
 * {@code BridgeClient} (which is AetherCode's own agent-to-agent protocol).
 *
 * <p>Used for MCP servers that expose their JSON-RPC interface over a raw
 * WebSocket — typically a thin wrapper over the stdio protocol for browsers
 * and services that prefer duplex WebSocket over server-sent events.
 */
public class WebSocketMcpClient implements McpProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final AtomicLong idGen = new AtomicLong();
    private final Map<Long, JsonNode> pending = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
    private WebSocket ws;
    private boolean initialized = false;

    public WebSocketMcpClient(String url) { this.url = url; }

    public void connect() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        try {
                            JsonNode n = MAPPER.readTree(data.toString());
                            if (n.has("id")) {
                                pending.put(n.path("id").asLong(), n);
                            } else {
                                incoming.offer(n);
                            }
                        } catch (Exception e) {
                            LOG.warn("ws frame parse failed: {}", e.getMessage());
                        }
                        return null;
                    }
                })
                .join();
        ObjectNode req = baseRequest("initialize");
        req.putObject("params")
                .put("protocolVersion", "2025-06-18")
                .putObject("clientInfo")
                    .put("name", "aethercode")
                    .put("version", "0.1.0");
        send(req);
        initialized = true;
    }

    public boolean isConnected() { return initialized && ws != null; }

    public void close() {
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        initialized = false;
    }

    public List<Tool> listTools() {
        if (!isConnected()) throw new IllegalStateException("not connected");
        try {
            JsonNode resp = rpc("tools/list", Map.of());
            JsonNode tools = resp.path("result").path("tools");
            List<Tool> out = new ArrayList<>();
            if (tools.isArray()) {
                for (JsonNode t : tools) {
                    String name = t.path("name").asText();
                    String desc = t.path("description").asText("");
                    Map<String, Object> schema = MAPPER.convertValue(
                            t.path("inputSchema"), new TypeReference<>() {});
                    ToolDef def = new ToolDef(
                            "mcp_ws__" + safeName(url) + "__" + name,
                            "[mcp-ws] " + desc,
                            schema,
                            (input, ctx) -> CompletableFuture.completedFuture(callTool(name, input))
                    );
                    out.add(Tools.build(def));
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("mcp ws listTools failed: " + e.getMessage(), e);
        }
    }

    public Tool.ToolResult callTool(String name, Map<String, Object> input) {
        try {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", name);
            params.set("arguments", MAPPER.valueToTree(input));
            JsonNode resp = rpc("tools/call", (ObjectNode) params);
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
            return Tool.ToolResult.error("mcp ws call failed: " + e.getMessage());
        }
    }

    public JsonNode rpc(String method, Map<String, Object> params) {
        try {
            return rpc(method, (ObjectNode) MAPPER.valueToTree(params));
        } catch (Exception e) {
            throw new RuntimeException("mcp ws rpc failed: " + e.getMessage(), e);
        }
    }

    public JsonNode rpc(String method, ObjectNode params) {
        try {
            ObjectNode req = baseRequest(method);
            if (params != null) req.set("params", params);
            return send(req);
        } catch (Exception e) {
            throw new RuntimeException("mcp ws rpc failed: " + e.getMessage(), e);
        }
    }

    private ObjectNode baseRequest(String method) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", idGen.incrementAndGet());
        n.put("method", method);
        return n;
    }

    private synchronized JsonNode send(ObjectNode req) {
        try {
            long id = req.get("id").asLong();
            ws.sendText(MAPPER.writeValueAsString(req), true);
            // Wait for the response. Sibling notifications go into incoming.
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode n = pending.remove(id);
                if (n != null) {
                    if (n.has("error")) throw new RuntimeException("mcp error: " + n.path("error"));
                    return n;
                }
                JsonNode notif = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (notif != null) LOG.debug("mcp ws notification: {}", notif.path("method").asText());
            }
            throw new RuntimeException("mcp ws timeout waiting for response id=" + id);
        } catch (Exception e) {
            throw new RuntimeException("mcp ws send failed: " + e.getMessage(), e);
        }
    }

    private static String safeName(String s) {
        if (s == null) return "anon";
        try {
            String host = new java.net.URL(s).getHost();
            return host == null ? "anon" : host.replace('.', '-');
        } catch (Exception e) {
            return "anon";
        }
    }
}
