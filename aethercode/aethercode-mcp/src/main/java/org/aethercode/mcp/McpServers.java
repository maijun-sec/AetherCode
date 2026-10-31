/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.tool.Tool;
import org.aethercode.mcp.socket.SocketMcpClient;
import org.aethercode.mcp.sse.SseMcpClient;
import org.aethercode.mcp.stdio.StdioMcpClient;
import org.aethercode.mcp.websocket.WebSocketMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads MCP server configurations from {@code .aethercode/mcp.json} and brings up
 * the configured transports. The resulting list of {@link Tool}s is appended to
 * the engine tool pool.
 *
 * <p>Format:
 * <pre>
 *   { "mcpServers": {
 *       "filesystem":  { "type": "stdio",  "command": "npx", "args": ["-y", "@mcp/filesystem", "."] },
 *       "remote-ide":  { "type": "sse",    "url": "https://example.com/mcp/sse" },
 *       "local-sock":  { "type": "socket", "host": "127.0.0.1", "port": 7000 },
 *       "ws-bridge":   { "type": "ws",     "url": "wss://example.com/mcp" }
 *     } }
 * </pre>
 */
public final class McpServers {

    private static final Logger LOG = LoggerFactory.getLogger(McpServers.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpServers() {}

    @SuppressWarnings("unchecked")
    public static List<Tool> loadFrom(Path file) {
        if (!Files.exists(file)) return List.of();
        List<Tool> all = new ArrayList<>();
        try {
            Map<String, Object> root = MAPPER.readValue(Files.readString(file), Map.class);
            Map<String, Object> servers = (Map<String, Object>) root.get("mcpServers");
            if (servers == null) return List.of();
            for (Map.Entry<String, Object> entry : servers.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> cfg = (Map<String, Object>) entry.getValue();
                String type = str(cfg.getOrDefault("type", "stdio"));
                try {
                    List<Tool> tools = switch (type) {
                        case "sse"    -> startSse(cfg);
                        case "socket" -> startSocket(cfg);
                        case "ws"     -> startWebSocket(cfg);
                        default       -> startStdio(cfg);
                    };
                    all.addAll(tools);
                    LOG.info("loaded {} tools from MCP server '{}' (type={})",
                            tools.size(), entry.getKey(), type);
                } catch (Exception e) {
                    LOG.warn("failed to start MCP server '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("failed to read MCP config {}: {}", file, e.getMessage());
        }
        return all;
    }

    private static List<Tool> startStdio(Map<String, Object> cfg) throws Exception {
        String cmd = str(cfg.get("command"));
        if (cmd == null) throw new IllegalArgumentException("stdio server requires 'command'");
        List<String> args = (List<String>) cfg.getOrDefault("args", List.of());
        @SuppressWarnings("unchecked") Map<String, String> env =
                (Map<String, String>) cfg.getOrDefault("env", Map.of());
        StdioMcpClient client = new StdioMcpClient(cmd, args, env);
        client.connect();
        return client.listTools();
    }

    private static List<Tool> startSse(Map<String, Object> cfg) throws Exception {
        String url = str(cfg.get("url"));
        if (url == null) throw new IllegalArgumentException("sse server requires 'url'");
        SseMcpClient client = new SseMcpClient(url);
        client.connect();
        return client.listTools();
    }

    private static List<Tool> startSocket(Map<String, Object> cfg) throws Exception {
        String host = str(cfg.get("host"));
        Object portObj = cfg.get("port");
        if (host == null || portObj == null) {
            throw new IllegalArgumentException("socket server requires 'host' and 'port'");
        }
        int port = portObj instanceof Number n ? n.intValue() : Integer.parseInt(portObj.toString());
        SocketMcpClient client = new SocketMcpClient(host, port);
        client.connect();
        return client.listTools();
    }

    private static List<Tool> startWebSocket(Map<String, Object> cfg) throws Exception {
        String url = str(cfg.get("url"));
        if (url == null) throw new IllegalArgumentException("ws server requires 'url'");
        WebSocketMcpClient client = new WebSocketMcpClient(url);
        client.connect();
        return client.listTools();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
