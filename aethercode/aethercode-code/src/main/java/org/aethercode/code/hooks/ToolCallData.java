package org.aethercode.code.hooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Description of one tool call projected to the hook transport.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.ToolCallData} record. The
 * <code>args</code> map is a JSON-compatible carrier (string keys, primitive
 * or nested values) so it can be passed unchanged to handlers that expect
 * Claude-style tool input.</p>
 */
public record ToolCallData(
        String id,
        String name,
        Map<String, Object> args,
        String mcpServer) {

    public ToolCallData {
        if (id == null) {
            id = "";
        }
        if (name == null) {
            name = "";
        }
        if (args == null) {
            args = new LinkedHashMap<>();
        } else {
            args = new LinkedHashMap<>(args);
        }
        if (mcpServer != null && mcpServer.isEmpty()) {
            mcpServer = null;
        }
    }
}
