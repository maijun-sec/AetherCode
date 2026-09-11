package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-message-kind renderers.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.messages}'s per-type
 * rendering helpers. Each renderer produces a {@link WidgetNode} tree
 * for a {@link MessageStore.MessageData} of a given kind. The Java port
 * keeps the renderers data-driven; the actual styling/formatting is
 * the host TUI's job.</p>
 */
public final class MessageRenderer {

    private MessageRenderer() {}

    public static WidgetNode renderUser(MessageStore.MessageData data) {
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static("> " + (data.content() == null ? "" : data.content()),
                        WidgetNode.Role.PRIMARY)),
                Messages.USER_MESSAGE_CLASS);
    }

    public static WidgetNode renderAssistant(MessageStore.MessageData data) {
        String body = data.content() == null ? "" : data.content();
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Markdown(body, Messages.ASSISTANT_MESSAGE_CLASS)),
                Messages.ASSISTANT_MESSAGE_CLASS);
    }

    public static WidgetNode renderTool(MessageStore.MessageData data) {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static(
                Messages.renderToolHeader(toolName(data), "running".equals(data.toolStatus())),
                WidgetNode.Role.PRIMARY, false, true, false));
        if (data.toolOutput() != null) {
            rows.add(new WidgetNode.Code("text", data.toolOutput(), false));
        }
        if (data.toolRejectReason() != null) {
            rows.add(new WidgetNode.Static("reject: " + data.toolRejectReason(),
                    WidgetNode.Role.ERROR));
        }
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                Messages.TOOL_MESSAGE_CLASS);
    }

    public static WidgetNode renderToolGroup(MessageStore.MessageData data) {
        int n = data.toolData() == null ? 0
                : data.toolData().containsKey("count")
                    ? ((Number) data.toolData().get("count")).intValue() : 0;
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static(n + " tool calls", WidgetNode.Role.MUTED, true, false, true)),
                Messages.TOOL_GROUP_CLASS);
    }

    public static WidgetNode renderSkill(MessageStore.MessageData data) {
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static("Skill: " + (data.content() == null ? "" : data.content()),
                        WidgetNode.Role.PRIMARY, false, true, false)),
                Messages.SKILL_MESSAGE_CLASS);
    }

    public static WidgetNode renderError(MessageStore.MessageData data) {
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static("Error: " + (data.content() == null ? "" : data.content()),
                        WidgetNode.Role.ERROR, false, true, false)),
                Messages.ERROR_MESSAGE_CLASS);
    }

    public static WidgetNode renderApp(MessageStore.MessageData data) {
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static(data.content() == null ? "" : data.content(),
                        WidgetNode.Role.MUTED, true, false, true)),
                Messages.APP_MESSAGE_CLASS);
    }

    public static WidgetNode renderRubric(MessageStore.MessageData data) {
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static("Rubric: " + (data.content() == null ? "" : data.content()),
                        WidgetNode.Role.PRIMARY, false, true, false)),
                Messages.RUBRIC_MESSAGE_CLASS);
    }

    public static WidgetNode renderSummarization(MessageStore.MessageData data) {
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static("Summarized earlier messages",
                        WidgetNode.Role.MUTED, true, false, true),
                new WidgetNode.Static(data.content() == null ? "" : data.content(),
                        WidgetNode.Role.MUTED)),
                Messages.SUMMARIZATION_MESSAGE_CLASS);
    }

    public static WidgetNode renderDiff(MessageStore.MessageData data) {
        String diff = data.content() == null ? "" : data.content();
        List<WidgetNode> rows = DiffRender.composeDiffLines(diff, 100, "", "", "", true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                Messages.DIFF_MESSAGE_CLASS);
    }

    private static String toolName(MessageStore.MessageData data) {
        if (data.toolData() == null) return "tool";
        Object name = data.toolData().get("name");
        return name == null ? "tool" : name.toString();
    }
}
