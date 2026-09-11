package org.aethercode.acp;

import org.aethercode.acp.schema.ContentBlock;
import org.aethercode.acp.schema.ContentBlocks;
import org.aethercode.acp.schema.SessionUpdate;
import org.aethercode.acp.schema.ToolCallTypes;
import org.aethercode.acp.schema.ToolCallTypes.ToolCallContent;

import java.util.List;
import java.util.Map;

/**
 * Factory functions for ACP content blocks, session updates, and
 * tool-call helpers.
 *
 * <p>Mirrors the {@code acp} Python module's factory exports:
 * {@code text_block}, {@code image_block}, {@code audio_block},
 * {@code start_tool_call}, {@code start_edit_tool_call},
 * {@code update_tool_call}, {@code update_agent_message},
 * {@code tool_content}, {@code tool_diff_content}. The Java
 * port keeps the same call shape so the server code reads
 * the same way as the Python port.</p>
 */
public final class AcpBlock {
    private AcpBlock() {}

    /** Build a {@link ContentBlocks.TextContentBlock}. */
    public static ContentBlocks.TextContentBlock textBlock(String text) {
        return new ContentBlocks.TextContentBlock(text);
    }

    /** Build an {@link ContentBlocks.ImageContentBlock}. */
    public static ContentBlocks.ImageContentBlock imageBlock(
            String data, String mimeType, String uri) {
        return new ContentBlocks.ImageContentBlock(
                data, mimeType, java.util.Optional.ofNullable(uri),
                java.util.Optional.empty());
    }

    /** Build an {@link ContentBlocks.AudioContentBlock}. */
    public static ContentBlocks.AudioContentBlock audioBlock(
            String data, String mimeType) {
        return new ContentBlocks.AudioContentBlock(
                data, mimeType, java.util.Optional.empty());
    }

    /** Build a tool-call start update record. Mirrors
     *  {@code acp.start_tool_call}. */
    public static SessionUpdate.ToolCallStart startToolCall(
            String toolCallId,
            String title,
            String kind,
            String status,
            Map<String, Object> rawInput) {
        return new SessionUpdate.ToolCallStart(
                toolCallId, title, kind, status, rawInput);
    }

    /** Build an edit tool-call start update record. Mirrors
     *  {@code acp.start_edit_tool_call}. */
    public static SessionUpdate.ToolCallStart startEditToolCall(
            String toolCallId,
            String title,
            String path,
            ToolCallContent content,
            List<ToolCallContent> extraOptions) {
        // Preserve the Python port's behavior: stash the diff in
        // the rawInput so that even if the consumer ignores the
        // content list the diff is still available.
        Map<String, Object> raw = Map.of(
                "path", path,
                "content", content,
                "extra", extraOptions == null ? List.of() : extraOptions);
        return new SessionUpdate.ToolCallStart(
                toolCallId, title,
                org.aethercode.acp.schema.ToolKind.EDIT,
                "pending", raw);
    }

    /** Build a tool-call status update. Mirrors
     *  {@code acp.update_tool_call}. */
    public static SessionUpdate.ToolCallUpdate updateToolCall(
            String toolCallId,
            String status,
            List<ToolCallContent> content) {
        return new SessionUpdate.ToolCallUpdate(
                toolCallId, null, null, status,
                content == null ? null : Map.of("content", content));
    }

    /** Build a streamed agent-message update. Mirrors
     *  {@code acp.update_agent_message}. */
    public static SessionUpdate.AgentMessageChunk updateAgentMessage(
            ContentBlock content) {
        return new SessionUpdate.AgentMessageChunk(null, content);
    }

    /** Wrap a text block as a tool-call content item. */
    public static ToolCallTypes.ToolCallContentText toolContent(
            ContentBlocks.TextContentBlock block) {
        return new ToolCallTypes.ToolCallContentText(block.text());
    }

    /** Build a diff content block. */
    public static ToolCallTypes.ToolCallContentDiff toolDiffContent(
            String path, String newText, String oldText) {
        return new ToolCallTypes.ToolCallContentDiff(path, newText, oldText);
    }
}
