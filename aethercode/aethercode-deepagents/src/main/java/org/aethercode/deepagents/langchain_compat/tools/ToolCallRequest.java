package org.aethercode.deepagents.langchain_compat.tools;

import java.util.Map;
import java.util.Objects;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * LangChain-compatible tool call request.
 *
 * <p>Java-native port of
 * {@code langchain.tools.tool_node.ToolCallRequest}. Carries the
 * tool name, the parsed arguments, and the tool call id; the
 * runtime converts this to a {@code ToolMessage} after invoking
 * the tool. Optionally carries the {@code type} discriminator
 * (e.g. {@code "tool_call"} or {@code "invalid_tool_call"}).</p>
 */
public record ToolCallRequest(
        String toolName,
        Map<String, Object> args,
        String toolCallId,
        String type) {

    public ToolCallRequest {
        Objects.requireNonNull(toolName, "toolName");
        args = args == null ? Map.of() : Map.copyOf(args);
        type = type == null ? "tool_call" : type;
    }

    public ToolCallRequest(String toolName, Map<String, Object> args, String toolCallId) {
        this(toolName, args, toolCallId, "tool_call");
    }

    public boolean isInvalid() {
        return "invalid_tool_call".equals(type);
    }
}
