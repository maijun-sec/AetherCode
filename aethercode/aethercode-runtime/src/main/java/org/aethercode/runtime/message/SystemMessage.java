package org.aethercode.runtime.message;

import java.util.List;
import java.util.Optional;

/**
 * A system / developer message.
 *
 * <p>Mirror of langchain's <code>SystemMessage</code>. The content can be
 * either a plain string (most common) or a list of {@link ContentBlock}.</p>
 */
public record SystemMessage(
        String id,
        Optional<String> name,
        Object content,                  // String | List<ContentBlock>
        List<ContentBlock> contentBlocks
) implements Message {

    public SystemMessage {
        if (content == null) {
            throw new IllegalArgumentException("SystemMessage.content is required");
        }
        contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
    }

    public SystemMessage(String content) {
        this(java.util.UUID.randomUUID().toString(),
             Optional.empty(),
             content,
             List.of(new TextBlock(content)));
    }

    public SystemMessage(List<ContentBlock> blocks) {
        this(java.util.UUID.randomUUID().toString(),
             Optional.empty(),
             blocks,
             blocks);
    }

    @Override
    public String role() {
        return "system";
    }

    /** Helper: returns the plain-text content if all blocks are {@link TextBlock}. */
    public Optional<String> text() {
        if (content instanceof String s) {
            return Optional.of(s);
        }
        if (!contentBlocks.isEmpty() && contentBlocks.stream().allMatch(b -> b instanceof TextBlock)) {
            return Optional.of(contentBlocks.stream()
                    .map(b -> ((TextBlock) b).text())
                    .reduce("", (a, b) -> a + b));
        }
        return Optional.empty();
    }
}
