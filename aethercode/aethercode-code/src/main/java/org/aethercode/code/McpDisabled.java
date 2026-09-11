package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP-disabled server state (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.mcp_disabled}
 * module. The Java port exposes the surface the TUI uses to record and
 * query the per-server "user disabled" state.</p>
 */
public final class McpDisabled {
    private McpDisabled() {}

    private static final Logger LOG = LoggerFactory.getLogger(McpDisabled.class);

    private static final Map<String, Boolean> DISABLED = new LinkedHashMap<>();

    /** Mark a server as disabled. */
    public static void disable(String serverName) {
        if (serverName == null) return;
        DISABLED.put(serverName, true);
    }

    /** Mark a server as enabled (clears the disabled flag). */
    public static void enable(String serverName) {
        if (serverName == null) return;
        DISABLED.remove(serverName);
    }

    /** Whether a server is currently disabled. */
    public static boolean isDisabled(String serverName) {
        return serverName != null && DISABLED.getOrDefault(serverName, false);
    }
}
