package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Message widgets — high-level dispatch.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.messages}. The Python
 * module is the largest in the package (240 KB / 5800 lines) and bundles
 * message renderers, link hover handling, and assistant-message
 * streaming. Rather than replicate the full surface, the Java port is
 * split into smaller files:</p>
 *
 * <ul>
 *   <li>{@link MessageType} — the message-kind enum.</li>
 *   <li>{@link MessageRenderer} — the per-type render strategy.</li>
 *   <li>{@link MessageStore} — the virtualization data shapes.</li>
 *   <li>{@link AssistantMessage} — streaming assistant-message widget.</li>
 *   <li>{@link ToolMessage} — tool-call message widget.</li>
 *   <li>{@link MessageBuffer} — content-block buffer.</li>
 * </ul>
 *
 * <p>This {@code Messages} class is the package-level entry point: it
 * exposes the {@link #render(WidgetNode, MessageData)} dispatch and the
 * common widget CSS classes.</p>
 */
public final class Messages {

    private Messages() {}

    /** Common CSS class names referenced throughout the package. */
    public static final String USER_MESSAGE_CLASS = "user-message";
    public static final String ASSISTANT_MESSAGE_CLASS = "assistant-message";
    public static final String TOOL_MESSAGE_CLASS = "tool-message";
    public static final String TOOL_GROUP_CLASS = "tool-group";
    public static final String SKILL_MESSAGE_CLASS = "skill-message";
    public static final String ERROR_MESSAGE_CLASS = "error-message";
    public static final String APP_MESSAGE_CLASS = "app-message";
    public static final String RUBRIC_MESSAGE_CLASS = "rubric-message";
    public static final String SUMMARIZATION_MESSAGE_CLASS = "summarization-message";
    public static final String DIFF_MESSAGE_CLASS = "diff-message";

    /** Render a message as a {@link WidgetNode}. */
    public static WidgetNode render(MessageStore.MessageData data) {
        return switch (data.type()) {
            case USER -> MessageRenderer.renderUser(data);
            case ASSISTANT -> MessageRenderer.renderAssistant(data);
            case TOOL -> MessageRenderer.renderTool(data);
            case TOOL_GROUP -> MessageRenderer.renderToolGroup(data);
            case SKILL -> MessageRenderer.renderSkill(data);
            case ERROR -> MessageRenderer.renderError(data);
            case APP -> MessageRenderer.renderApp(data);
            case RUBRIC -> MessageRenderer.renderRubric(data);
            case SUMMARIZATION -> MessageRenderer.renderSummarization(data);
            case DIFF -> MessageRenderer.renderDiff(data);
        };
    }

    /** A small {@code ASK_USER_ANSWERED_SUMMARY} placeholder. */
    public static final String ASK_USER_ANSWERED_SUMMARY = "AskUser answered";
    public static final String ASK_USER_FAILED_SUMMARY = "AskUser failed";

    /**
     * Strip LLM-appended trailing annotations like {@code - optional},
     * {@code (optional)}, or {@code [required]} from question text.
     */
    public static String stripTrailingAnnotation(String text) {
        if (text == null) return "";
        return text.replaceAll(
                "\\s*(?:[-–—]\\s*(?:optional|required)|\\((?:optional|required)[.!?]?\\)|"
                        + "\\[(?:optional|required)[.!?]?\\])[.!?]*\\s*$",
                "");
    }

    /**
     * A simple ask-user row summary, mirroring the
     * {@code AskUserRowSummary} dataclass in
     * {@code deepagents_code._ask_user_types}.
     */
    public record AskUserRowSummary(String question, String answer, boolean answered) {}

    /** Render a row summary as a {@link WidgetNode}. */
    public static WidgetNode renderAskUserRow(AskUserRowSummary summary) {
        String prefix = summary.answered() ? "✓ " : "✗ ";
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static(prefix + summary.question(),
                        summary.answered() ? WidgetNode.Role.SUCCESS : WidgetNode.Role.MUTED),
                new WidgetNode.Static("    " + summary.answer(),
                        WidgetNode.Role.MUTED, true, false, false)));
    }

    /** Render a list of ask-user row summaries. */
    public static WidgetNode renderAskUserRows(List<AskUserRowSummary> rows) {
        if (rows == null || rows.isEmpty()) return new WidgetNode.Static("");
        List<WidgetNode> children = new ArrayList<>(rows.size());
        for (AskUserRowSummary r : rows) children.add(renderAskUserRow(r));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, children,
                "ask-user-rows");
    }

    /** Stub: render the entire ask-user transcript. Hosts extend as needed. */
    public static WidgetNode renderAskUserTranscriptForDisplay(List<AskUserRowSummary> rows) {
        return renderAskUserRows(rows);
    }

    /**
     * Render the per-tool header (e.g. "Running execute" or "Ran execute").
     *
     * @param toolName Name of the tool.
     * @param isRunning {@code true} for the in-progress header.
     */
    public static String renderToolHeader(String toolName, boolean isRunning) {
        String verb = isRunning ? "Running" : "Ran";
        return verb + " " + toolName;
    }

    /**
     * Render a markdown body, with an untrusted-rendering safety check.
     *
     * <p>The Python module routes every rendered string through
     * {@code sanitize_control_chars} which strips control/escape/bidi
     * characters. The Java port is a thin wrapper that delegates the
     * sanitization to {@code deepagents_core.unicode_security} via the
     * host TUI; this method just builds a {@link WidgetNode.Markdown}.</p>
     */
    public static WidgetNode renderMarkdown(String body, String cssClass) {
        if (body == null) return new WidgetNode.Static("");
        return new WidgetNode.Markdown(body, cssClass == null ? "" : cssClass);
    }

    /** A typed render key, mapping a tool call to its render. */
    public record RenderKey(String toolName, Map<String, Object> args) {}
}
