package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tool catalog for the {@code /tools} TUI screen.
 *
 * <p>Java-native port of the Python {@code deepagents_code.tool_catalog}
 * module. The Java port exposes a small registry abstraction; the TUI
 * host populates it with the tools the agent has bound for the current
 * session.</p>
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    /** One registered tool. */
    public record Tool(
            String name,
            String description,
            String category,
            boolean readOnly,
            boolean requiresTrust) {
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Register a tool. */
    public void register(Tool tool) {
        if (tool == null) return;
        tools.put(tool.name(), tool);
    }

    /** Look up a tool by name. */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** All tools in registration order. */
    public Map<String, Tool> all() {
        return Map.copyOf(tools);
    }

    /** All tool names. */
    public Set<String> names() {
        return Set.copyOf(tools.keySet());
    }
}
