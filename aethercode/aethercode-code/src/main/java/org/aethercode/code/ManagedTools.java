package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Managed-tools surface (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.managed_tools}
 * module. The Java port exposes the surface used by the agent graph
 * when run in managed mode; the full implementation lands with the
 * deepagents-core middleware port.</p>
 */
public final class ManagedTools {
    private ManagedTools() {}

    /** A managed-tool entry. */
    public record ManagedTool(
            String name,
            String description,
            boolean requiresApproval) {
    }

    /** The set of managed tools. */
    public static final Map<String, ManagedTool> TOOLS = Map.of(
            "execute", new ManagedTool("execute", "Execute a shell command", true),
            "web_search", new ManagedTool("web_search", "Search the web", false),
            "fetch_url", new ManagedTool("fetch_url", "Fetch a URL", false));
}
