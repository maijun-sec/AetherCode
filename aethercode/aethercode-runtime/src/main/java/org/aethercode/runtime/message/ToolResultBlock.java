package org.aethercode.runtime.message;

import java.util.List;
import java.util.Map;

/**
 * Tool-result content block. Carried inside a {@link ToolMessage} to
 * return a tool invocation result.
 *
 * <p>Mirror of langchain
 * <code>{"type": "tool_result", "tool_use_id": ..., "content": [...], "is_error": bool}</code>.</p>
 */
public record ToolResultBlock(
        String toolUseId,
        List<ContentBlock> content,
        boolean isError
) implements ContentBlock {

    public ToolResultBlock {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /** Builder shortcut for the common case of a single text result. */
    public static ToolResultBlock text(String toolUseId, String text) {
        return new ToolResultBlock(toolUseId, List.of(new TextBlock(text)), false);
    }

    public static ToolResultBlock error(String toolUseId, String text) {
        return new ToolResultBlock(toolUseId, List.of(new TextBlock(text)), true);
    }

    @Override
    public String type() {
        return "tool_result";
    }
}
