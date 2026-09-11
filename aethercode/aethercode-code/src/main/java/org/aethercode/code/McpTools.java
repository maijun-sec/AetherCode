package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * MCP tool surface (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.mcp_tools}
 * module. The Java port exposes the small surface used by the agent
 * graph factory when binding MCP tools; the full implementation lands
 * with the MCP runtime port.</p>
 */
public final class McpTools {
    private McpTools() {}

    private static final Logger LOG = LoggerFactory.getLogger(McpTools.class);

    /** Tool info from an MCP server. */
    public record McpToolInfo(String server, String name, String description) {}

    /** Per-server connection info. */
    public record McpServerInfo(String name, String transport, boolean connected) {}

    /** Load tools from a configuration map. */
    public static CompletionStage<List<McpToolInfo>> loadTools(Map<String, Object> config) {
        LOG.info("MCP loadTools (stub)");
        return CompletableFuture.completedFuture(List.of());
    }
}
