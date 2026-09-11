package org.aethercode.runtime.message;

import java.util.List;
import java.util.Optional;

/**
 * A tool result message — the LLM's view of a tool invocation.
 *
 * <p>Mirror of langchain's <code>ToolMessage</code>. <code>toolCallId</code>
 * matches a {@link ToolCall} from the preceding {@link AIMessage};
 * <code>content</code> is the stringified result the model sees (or a
 * list of {@link ContentBlock} for multimodal results); <code>artifact</code>
 * is an optional machine-readable payload that does not enter the model
 * context (e.g. an {@code ExecuteResponse} from a sandbox shell).</p>
 */
public record ToolMessage(
        String id,
        Optional<String> name,
        String toolCallId,
        Object content,                          // String | List<ContentBlock>
        List<ContentBlock> contentBlocks,
        boolean isError,
        Object artifact
) implements Message {

    public ToolMessage {
        if (toolCallId == null || toolCallId.isEmpty()) {
            throw new IllegalArgumentException("ToolMessage.toolCallId is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("ToolMessage.content is required");
        }
        contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
    }

    public static ToolMessage of(String toolCallId, String content) {
        return new ToolMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                toolCallId,
                content,
                List.of(new TextBlock(content)),
                false,
                null);
    }

    public static ToolMessage error(String toolCallId, String content) {
        return new ToolMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                toolCallId,
                content,
                List.of(new TextBlock(content)),
                true,
                null);
    }

    public static ToolMessage of(String toolCallId, String content, Object artifact) {
        return new ToolMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                toolCallId,
                content,
                List.of(new TextBlock(content)),
                false,
                artifact);
    }

    @Override
    public String role() {
        return "tool";
    }
}
