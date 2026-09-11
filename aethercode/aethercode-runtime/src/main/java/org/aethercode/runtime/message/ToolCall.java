package org.aethercode.runtime.message;

import java.util.Map;
import java.util.Objects;

/**
 * A tool call request from the LLM.
 *
 * <p>This is the simplified wire form (id, name, args) — distinct from
 * {@link ToolUseBlock} which is the content-block form. The agent runtime
 * converts between them.</p>
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> args
) {
    public ToolCall {
        Objects.requireNonNull(id, "ToolCall.id");
        Objects.requireNonNull(name, "ToolCall.name");
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public static ToolCall of(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, args);
    }
}
