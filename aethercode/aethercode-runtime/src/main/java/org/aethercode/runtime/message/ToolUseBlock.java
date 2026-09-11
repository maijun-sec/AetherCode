package org.aethercode.runtime.message;

import java.util.Map;

/**
 * Tool-use request content block. Carried inside an {@link AIMessage} to
 * ask the runtime to invoke a tool.
 *
 * <p>Mirror of langchain
 * <code>{"type": "tool_use", "id": ..., "name": ..., "input": ...}</code>.</p>
 */
public record ToolUseBlock(
        String id,
        String name,
        Map<String, Object> input
) implements ContentBlock {

    public ToolUseBlock {
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    @Override
    public String type() {
        return "tool_use";
    }
}
