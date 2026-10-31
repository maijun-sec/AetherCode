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
