package org.aethercode.core.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1:1 ports of Python
 * {@code test_middleware.py::TestExtractTextFromMessage}.
 *
 * <p>Tests the Java port's
 * {@link ContentBlock#flattenText(List)} helper (Python's
 * {@code _extract_text_from_message}). The Java port uses
 * {@link ContentBlock.TextBlock} for text segments; image blocks
 * contribute nothing to the flattened text.</p>
 */
@DisplayName("TestExtractTextFromMessagePort (test_middleware.py::TestExtractTextFromMessage)")
class TestExtractTextFromMessagePort {

    @Test
    @DisplayName("test_string_content: single text block → its text")
    void stringContent() {
        // Python: ToolMessage(content="hello"). The Java port stores
        // content as a list of ContentBlock. A "string" content
        // becomes a single TextBlock.
        Message.ToolMessage msg = new Message.ToolMessage(
                "tm-1", "t1", List.of(ContentBlock.text("hello")),
                java.util.Optional.of("tool"),
                java.util.Optional.of("success"),
                java.util.Optional.empty(),
                Map.of(), Map.of());
        assertThat(ContentBlock.flattenText(msg.content())).isEqualTo("hello");
    }

    @Test
    @DisplayName("test_single_text_block: one TextBlock → its text")
    void singleTextBlock() {
        Message.ToolMessage msg = new Message.ToolMessage(
                "tm-1", "t1", List.of(ContentBlock.text("hello")),
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), Map.of(), Map.of());
        assertThat(ContentBlock.flattenText(msg.content())).isEqualTo("hello");
    }

    @Test
    @DisplayName("test_multiple_text_blocks_joined: two text blocks joined with newline")
    void multipleTextBlocksJoined() {
        Message.ToolMessage msg = new Message.ToolMessage(
                "tm-1", "t1",
                List.of(ContentBlock.text("first"), ContentBlock.text("second")),
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), Map.of(), Map.of());
        assertThat(ContentBlock.flattenText(msg.content())).isEqualTo("first\nsecond");
    }

    @Test
    @DisplayName("test_text_and_image_extracts_text_only: image blocks contribute empty")
    void textAndImageExtractsTextOnly() {
        Message.ToolMessage msg = new Message.ToolMessage(
                "tm-1", "t1",
                List.of(
                        ContentBlock.text("description"),
                        new ContentBlock.ImageBlock(new byte[]{1, 2, 3}, "image/png")),
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), Map.of(), Map.of());
        // Only the text block contributes.
        assertThat(ContentBlock.flattenText(msg.content())).isEqualTo("description");
    }

    @Test
    @DisplayName("test_image_only_returns_empty: only image → empty string")
    void imageOnlyReturnsEmpty() {
        Message.ToolMessage msg = new Message.ToolMessage(
                "tm-1", "t1",
                List.of(new ContentBlock.ImageBlock(new byte[]{1, 2, 3}, "image/png")),
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), Map.of(), Map.of());
        assertThat(ContentBlock.flattenText(msg.content())).isEmpty();
    }
}
