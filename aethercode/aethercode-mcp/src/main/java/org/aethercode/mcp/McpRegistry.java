package org.aethercode.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * a small, hand-curated catalog of well-known MCP servers. Modelled on the
 * TS {@code services/mcp/officialRegistry.ts}. The catalog is in-process — for a
 * real production deployment, this would be a JSON file on disk or a remote
 * URL, but for prior round the four entries below cover the common cases (filesystem,
 * git, web fetch, SQLite).
 *
 * <p>Each entry is a {@link Entry} with the connection fields the user would
 * otherwise have to hand-write in mcp.json. {@link #toMcpJson(String, Map)}
 * renders an entry into the shape {@link McpServers#loadFrom} expects.
 */
public final class McpRegistry {

    public record Entry(
            String name,
            String description,
            String type,            // "stdio" | "sse" | "socket" | "ws"
            String command,         // for stdio
            List<String> args,      // for stdio
            String url,             // for sse/ws
            String host,            // for socket
            Integer port,           // for socket
            String homepage
    ) {
        public Map<String, Object> toConfig() {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("type", type);
            if ("stdio".equals(type)) {
                cfg.put("command", command);
                if (args != null && !args.isEmpty()) cfg.put("args", args);
            } else if ("sse".equals(type) || "ws".equals(type)) {
                cfg.put("url", url);
            } else if ("socket".equals(type)) {
                cfg.put("host", host);
                cfg.put("port", port);
            }
            return cfg;
        }
    }

    private static final List<Entry> CATALOG = new ArrayList<>();
    static {
        CATALOG.add(new Entry(
                "filesystem", "Local filesystem read/write — stdio bridge to @modelcontextprotocol/server-filesystem",
                "stdio", "npx", List.of("-y", "@modelcontextprotocol/server-filesystem", "."),
                null, null, null,
                "https://github.com/modelcontextprotocol/servers"));
        CATALOG.add(new Entry(
                "git", "Read git history, branches, diffs — stdio bridge to @modelcontextprotocol/server-git",
                "stdio", "npx", List.of("-y", "@modelcontextprotocol/server-git", "--repository", "."),
                null, null, null,
                "https://github.com/modelcontextprotocol/servers"));
        CATALOG.add(new Entry(
                "fetch", "Web fetch over HTTP — stdio bridge to @modelcontextprotocol/server-fetch",
                "stdio", "npx", List.of("-y", "@modelcontextprotocol/server-fetch"),
                null, null, null,
                "https://github.com/modelcontextprotocol/servers"));
        CATALOG.add(new Entry(
                "sqlite", "Local SQLite read/query — stdio bridge to @modelcontextprotocol/server-sqlite",
                "stdio", "uvx", List.of("mcp-server-sqlite", "--db-path", "./aethercode.db"),
                null, null, null,
                "https://github.com/modelcontextprotocol/servers"));
        CATALOG.add(new Entry(
                "remote-fetch", "Generic SSE-based remote MCP server — connect via URL",
                "sse", null, null, "https://example.com/mcp/sse",
                null, null,
                null));
    }

    private McpRegistry() {}

    public static List<Entry> list() { return List.copyOf(CATALOG); }

    public static Entry get(String name) {
        for (Entry e : CATALOG) if (e.name().equals(name)) return e;
        return null;
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Entry e : CATALOG) out.add(e.name());
        return out;
    }

    /**
     * Render an entry into the mcpServers map shape used by {@link McpServers#loadFrom}.
     * The {@code extra} map is merged in last so callers can override e.g. {@code command}
     * for a local binary.
     */
    public static Map<String, Object> toMcpJson(String name, Map<String, Object> extra) {
        Entry e = get(name);
        if (e == null) throw new IllegalArgumentException("unknown registry entry: " + name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(e.toConfig());
        if (extra != null) out.putAll(extra);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put(name, out);
        wrapper.put("mcpServers", inner);
        return wrapper;
    }
}
