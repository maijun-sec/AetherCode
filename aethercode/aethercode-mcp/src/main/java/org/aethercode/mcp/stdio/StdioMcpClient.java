package org.aethercode.mcp.stdio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP (Model Context Protocol) stdio client. Speaks the JSON-RPC 2.0 envelope on
 * stdio. prior round scope: {@code initialize}, {@code tools/list}, {@code tools/call}. SSE transport
 * is a future round.
 *
 * <p>Wire format reference: https://modelcontextprotocol.io/specification/2025-06-18
 */
public class StdioMcpClient {

    private static final Logger LOG = LoggerFactory.getLogger(StdioMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final AtomicLong idGen = new AtomicLong();
    private Process process;
    private BufferedReader reader;
    private OutputStream writer;
    private boolean initialized = false;

    public StdioMcpClient(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args == null ? List.of() : args;
        this.env = env;
    }

    /** Spawn the child process and complete the MCP handshake. */
    public void connect() throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        pb.command(cmd);
        if (env != null) pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        this.process = pb.start();
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = process.getOutputStream();
        // initialize
        ObjectNode req = baseRequest("initialize");
        req.putObject("params")
                .put("protocolVersion", "2025-06-18")
                .putObject("clientInfo")
                    .put("name", "aethercode")
                    .put("version", "0.1.0");
        req.putObject("params").putObject("capabilities");
        send(req);
        // ignore the result body for R1; rely on the server's tools/list reply
        initialized = true;
    }

    public boolean isInitialized() { return initialized; }

    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    /** List the tools this server exposes. */
    @SuppressWarnings("unchecked")
    public List<Tool> listTools() {
        if (!initialized) throw new IllegalStateException("not connected");
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
                        "mcp__" + safeName(command) + "__" + name,
                        "[mcp] " + desc,
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
            if (isError) {
                return Tool.ToolResult.error(text.length() == 0 ? "tool error" : text.toString());
            }
            return Tool.ToolResult.of(text.toString());
        } catch (Exception e) {
            return Tool.ToolResult.error("mcp call failed: " + e.getMessage());
        }
    }

    private ObjectNode baseRequest(String method) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", idGen.incrementAndGet());
        n.put("method", method);
        return n;
    }

    /**
     * Generic JSON-RPC method invocation. R3 uses this to surface the
     * {@code prompts/list} and {@code resources/list} protocol surface uniformly with
     * the SSE transport.
     */
    public com.fasterxml.jackson.databind.JsonNode rpc(String method, Map<String, Object> params) {
        ObjectNode req = baseRequest(method);
        if (params != null && !params.isEmpty()) {
            req.set("params", MAPPER.valueToTree(params));
        }
        return send(req);
    }

    private synchronized JsonNode send(ObjectNode req) {
        try {
            String line = MAPPER.writeValueAsString(req);
            writer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            writer.flush();
            // Read responses until we see one with our id. MCP servers can interleave
            // notifications; we just look for a matching id.
            long targetId = req.get("id").asLong();
            while (true) {
                String response = reader.readLine();
                if (response == null) throw new RuntimeException("mcp server closed the connection");
                if (response.isBlank()) continue;
                JsonNode r = MAPPER.readTree(response);
                if (r.path("id").asLong() == targetId) {
                    if (r.has("error")) {
                        throw new RuntimeException("mcp error: " + r.path("error").toString());
                    }
                    return r;
                }
                // else: notification / unrelated response — skip
            }
        } catch (Exception e) {
            throw new RuntimeException("mcp send failed: " + e.getMessage(), e);
        }
    }

    private static String safeName(String s) {
        if (s == null) return "anon";
        // strip path and extension
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String tail = slash >= 0 ? s.substring(slash + 1) : s;
        int dot = tail.lastIndexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }
}
