package org.aethercode.runtime.message;

import java.util.List;
import java.util.Optional;

/**
 * A message produced by the LLM.
 *
 * <p>Mirror of langchain's <code>AIMessage</code>. Carries text content
 * (or {@link ContentBlock}s for multimodal output), zero-or-more
 * {@link ToolCall}s, an optional reasoning string (for Anthropic extended
 * thinking), and optional token {@link Usage}.</p>
 */
public record AIMessage(
        String id,
        Optional<String> name,
        Object content,                          // String | List<ContentBlock>
        List<ContentBlock> contentBlocks,
        List<ToolCall> toolCalls,
        Optional<String> reasoning,
        Optional<Usage> usage
) implements Message {

    public AIMessage {
        contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
        toolCalls     = toolCalls     == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AIMessage of(String content) {
        return new AIMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                content,
                List.of(new TextBlock(content)),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    public static AIMessage of(String content, List<ToolCall> toolCalls) {
        return new AIMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                content == null ? "" : content,
                content == null ? List.of() : List.of(new TextBlock(content)),
                toolCalls,
                Optional.empty(),
                Optional.empty());
    }

    public static AIMessage withToolCalls(List<ToolCall> toolCalls) {
        return new AIMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                "",
                List.of(),
                toolCalls,
                Optional.empty(),
                Optional.empty());
    }

    public static AIMessage withReasoning(String reasoning, String content, List<ToolCall> toolCalls) {
        return new AIMessage(
                java.util.UUID.randomUUID().toString(),
                Optional.empty(),
                content == null ? "" : content,
                content == null ? List.of() : List.of(new TextBlock(content)),
                toolCalls == null ? List.of() : toolCalls,
                Optional.ofNullable(reasoning),
                Optional.empty());
    }

    @Override
    public String role() {
        return "ai";
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
