package org.aethercode.mcp.socket;

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client over a raw TCP socket. Modelled on the TS
 * {@code src/services/mcp/socket.ts}.
 *
 * <p>Wire format: line-delimited JSON-RPC 2.0 over a TCP socket. Each line is
 * one JSON object; responses are matched by id. Heartbeats are not required —
 * the TCP keep-alive is sufficient for liveness.
 *
 * <p>Used when the MCP server is reachable over a local UNIX socket or a remote
 * port. The stdio and SSE transports stay around for their use cases.
 */
public class SocketMcpClient implements McpProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(SocketMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String host;
    private final int port;
    private final AtomicLong idGen = new AtomicLong();
    private Socket socket;
    private BufferedReader reader;
    private PrintStream writer;
    private boolean initialized = false;

    public SocketMcpClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(30_000);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new PrintStream(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", idGen.incrementAndGet());
        req.put("method", "initialize");
        req.putObject("params")
                .put("protocolVersion", "2025-06-18")
                .putObject("clientInfo")
                    .put("name", "aethercode")
                    .put("version", "0.1.0");
        send(req);
        initialized = true;
    }

    public boolean isConnected() { return initialized && socket != null && socket.isConnected(); }

    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        initialized = false;
    }

    public List<Tool> listTools() {
        if (!isConnected()) throw new IllegalStateException("not connected");
        try {
            ObjectNode req = baseRequest("tools/list");
            JsonNode resp = send(req);
            JsonNode tools = resp.path("result").path("tools");
            List<Tool> out = new ArrayList<>();
            if (tools.isArray()) {
                for (JsonNode t : tools) {
                    String name = t.path("name").asText();
                    String desc = t.path("description").asText("");
                    Map<String, Object> schema = MAPPER.convertValue(
                            t.path("inputSchema"), new TypeReference<>() {});
                    ToolDef def = new ToolDef(
                            "mcp_socket__" + safeName(host) + "__" + name,
                            "[mcp-socket] " + desc,
                            schema,
                            (input, ctx) -> {
                                try { return CompletableFuture.completedFuture(callTool(name, input)); }
                                catch (Exception e) {
                                    return CompletableFuture.completedFuture(Tool.ToolResult.error(e.getMessage()));
                                }
                            }
                    );
                    out.add(Tools.build(def));
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("mcp socket listTools failed: " + e.getMessage(), e);
        }
    }

    public Tool.ToolResult callTool(String name, Map<String, Object> input) {
        try {
            ObjectNode req = baseRequest("tools/call");
            ObjectNode params = req.putObject("params");
            params.put("name", name);
            params.set("arguments", MAPPER.valueToTree(input));
            JsonNode resp = send(req);
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
            return Tool.ToolResult.error("mcp socket call failed: " + e.getMessage());
        }
    }

    public JsonNode rpc(String method, Map<String, Object> params) {
        ObjectNode req = baseRequest(method);
        if (params != null) {
            req.set("params", MAPPER.valueToTree(params));
        }
        return send(req);
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
            String line = MAPPER.writeValueAsString(req);
            writer.println(line);
            long target = req.get("id").asLong();
            String response = reader.readLine();
            if (response == null) throw new IOException("mcp socket closed");
            JsonNode n = MAPPER.readTree(response);
            if (n.path("id").asLong() == target) {
                if (n.has("error")) throw new RuntimeException("mcp error: " + n.path("error"));
                return n;
            }
            // skip notifications / unrelated
            return send(req);
        } catch (Exception e) {
            throw new RuntimeException("mcp socket send failed: " + e.getMessage(), e);
        }
    }

    private static String safeName(String s) {
        return s == null ? "anon" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // bridge for CompletableFuture.completedFuture in lambda
    private static final class CompletableFuture {
        static <T> java.util.concurrent.CompletableFuture<T> completedFuture(T value) {
            return java.util.concurrent.CompletableFuture.completedFuture(value);
        }
    }
}
