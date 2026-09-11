package org.aethercode.acp.schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ACP tool-call start / update records and their
 * {@code content} / {@code content_diff} helpers.
 *
 * <p>Mirrors {@code acp.schema.{ToolCallStart,ToolCallUpdate,
 * ToolCallContent,ToolCallContentDiff}} and the
 * {@code start_tool_call}, {@code start_edit_tool_call},
 * {@code update_tool_call}, {@code tool_content},
 * {@code tool_diff_content} factory helpers.</p>
 */
public final class ToolCallTypes {
    private ToolCallTypes() {}

    /** Tool-call status field. Wire values: {@code pending},
     *  {@code in_progress}, {@code completed}, {@code failed}. */
    public record ToolCallStart(
            String toolCallId,
            String title,
            String kind,
            String status,
            Map<String, Object> rawInput,
            List<ToolCallContent> content) {
        public ToolCallStart {
            Objects.requireNonNull(toolCallId, "toolCallId");
            kind = kind == null ? ToolKind.OTHER : kind;
            status = status == null ? "pending" : status;
            rawInput = rawInput == null ? Map.of() : Map.copyOf(rawInput);
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    /** Update record for an in-flight tool call. */
    public record ToolCallUpdate(
            String toolCallId,
            String title,
            String kind,
            String status,
            Map<String, Object> rawInput,
            List<ToolCallContent> content) {
        public ToolCallUpdate {
            Objects.requireNonNull(toolCallId, "toolCallId");
            kind = kind == null ? null : kind;
            status = status == null ? null : status;
            rawInput = rawInput == null ? null : Map.copyOf(rawInput);
            content = content == null ? null : List.copyOf(content);
        }
    }

    /** Sealed content variant for a tool call. */
    public sealed interface ToolCallContent
            permits ToolCallContentText, ToolCallContentDiff {
    }

    /** A text content block in a tool call. */
    public record ToolCallContentText(String text) implements ToolCallContent {
        public ToolCallContentText {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A diff content block in a tool call. */
    public record ToolCallContentDiff(
            String path,
            String newText,
            String oldText) implements ToolCallContent {
        public ToolCallContentDiff {
            Objects.requireNonNull(path, "path");
            newText = newText == null ? "" : newText;
            oldText = oldText == null ? "" : oldText;
        }
    }

    /** Build a {@code pending} tool-call start record. */
    public static ToolCallStart startToolCall(
            String toolCallId, String title, String kind, String status,
            Map<String, Object> rawInput) {
        return new ToolCallStart(
                toolCallId, title, kind, status, rawInput, List.of());
    }

    /** Build a {@code pending} edit tool-call start record. */
    public static ToolCallStart startEditToolCall(
            String toolCallId, String title, String path,
            ToolCallContent content,
            List<ToolCallContent> extraOptions) {
        List<ToolCallContent> all = new java.util.ArrayList<>();
        all.add(content);
        if (extraOptions != null) all.addAll(extraOptions);
        return new ToolCallStart(
                toolCallId, title, ToolKind.EDIT, "pending",
                Map.of(), List.copyOf(all));
    }

    /** Wrap a text content block as a tool-call content item. */
    public static ToolCallContentText toolContent(
            ContentBlocks.TextContentBlock block) {
        return new ToolCallContentText(block.text());
    }

    /** Build a diff content block. */
    public static ToolCallContentDiff toolDiffContent(
            String path, String newText, String oldText) {
        return new ToolCallContentDiff(path, newText, oldText);
    }

    /** Build a status update for an existing tool call. */
    public static ToolCallUpdate updateToolCall(
            String toolCallId, String status, List<ToolCallContent> content) {
        return new ToolCallUpdate(
                toolCallId, null, null, status, null, content);
    }
}
