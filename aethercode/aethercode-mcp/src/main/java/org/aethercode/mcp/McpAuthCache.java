package org.aethercode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aethercode.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP authentication cache. Modelled on the TS
 * {@code src/services/mcp/auth.ts} auth cache.
 *
 * <p>When an MCP request returns 401/403, we record the server in a local cache so the
 * next 15 minutes of calls are short-circuited to {@code needs-auth} instead of
 * hammering the auth dance. This prevents the "auth stampede" pattern where a
 * single 401 triggers a flood of OAuth refresh attempts from sibling tool calls.
 *
 * <p>The cache is a small JSON file at {@code ~/.aethercode/mcp-needs-auth-cache.json}
 * keyed by server id.
 */
public class McpAuthCache {

    private static final Logger LOG = LoggerFactory.getLogger(McpAuthCache.class);
    private static final long TTL_MS = 15 * 60 * 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, Long> cache = new HashMap<>();

    public McpAuthCache(Path file) {
        this.file = file;
        load();
    }

    public static McpAuthCache defaultCache() {
        String home = System.getProperty("user.home");
        return new McpAuthCache(Path.of(home, ".aethercode", "mcp-needs-auth-cache.json"));
    }

    public synchronized boolean isAuthNeeded(String serverId) {
        Long ts = cache.get(serverId);
        if (ts == null) return false;
        return System.currentTimeMillis() - ts < TTL_MS;
    }

    public synchronized void markAuthNeeded(String serverId) {
        cache.put(serverId, System.currentTimeMillis());
        persist();
    }

    public synchronized void clear(String serverId) {
        cache.remove(serverId);
        persist();
    }

    public synchronized void clearAll() {
        cache.clear();
        persist();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, Object> raw = MAPPER.readValue(Files.readString(file), Map.class);
            for (var e : raw.entrySet()) {
                Object ts = e.getValue();
                if (ts instanceof Number n) cache.put(e.getKey(), n.longValue());
            }
        } catch (Exception e) {
            LOG.warn("failed to read auth cache: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            cache.forEach(root::put);
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("failed to persist auth cache: {}", e.getMessage());
        }
    }
}
