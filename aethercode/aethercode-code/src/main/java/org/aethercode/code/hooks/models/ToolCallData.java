package org.aethercode.code.hooks.models;

import java.util.Map;

/**
 * Native tool-call data used by hook lifecycle owners.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.ToolCallData} record.</p>
 */
public record ToolCallData(
        String id,
        String name,
        Map<String, Object> args,
        String mcpServer) {
    public ToolCallData {
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
