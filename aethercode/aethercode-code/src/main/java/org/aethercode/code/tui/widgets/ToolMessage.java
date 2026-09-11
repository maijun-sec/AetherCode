package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Tool-message widget.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.messages}'s tool
 * message row. The Python {@code ToolMessage} is a Textual {@code Static}
 * subclass that renders a tool call's header, args, status, and
 * output. The Java port preserves the public data shape and renders
 * the row as a {@link WidgetNode} container.</p>
 */
public class ToolMessage extends Widget {

    /** Tool execution status. */
    public enum Status { RUNNING, DONE, ERROR, REJECTED, CANCELLED }

    /** Tool call data. */
    public record ToolData(String name, java.util.Map<String, Object> args, String description) {}

    private final ToolData data;
    private Status status = Status.RUNNING;
    private String output;
    private Long durationMs;
    private String rejectReason;
    private boolean expanded;

    public ToolMessage(ToolData data) {
        super("", "tool-message");
        this.data = data == null ? new ToolData("tool", java.util.Map.of(), "") : data;
    }

    public ToolData data() { return data; }
    public Status status() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String output() { return output; }
    public void setOutput(String output) { this.output = output; }
    public Long durationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String rejectReason() { return rejectReason; }
    public void setRejectReason(String reason) { this.rejectReason = reason; }
    public boolean expanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }

    @Override
    public WidgetNode render() {
        String header = switch (status) {
            case RUNNING -> "Running " + data.name();
            case DONE -> "Ran " + data.name();
            case ERROR -> "Error: " + data.name();
            case REJECTED -> "Rejected: " + data.name();
            case CANCELLED -> "Cancelled: " + data.name();
        };
        var rows = new java.util.ArrayList<WidgetNode>();
        rows.add(new WidgetNode.Static(header,
                WidgetNode.Role.PRIMARY, false, true, false));
        if (durationMs != null) {
            rows.add(new WidgetNode.Static(
                    "elapsed: " + Loading.formatDuration(durationMs / 1000L),
                    WidgetNode.Role.MUTED));
        }
        if (output != null && !output.isEmpty()) {
            rows.add(new WidgetNode.Code("text", output, false));
        }
        if (rejectReason != null && !rejectReason.isEmpty()) {
            rows.add(new WidgetNode.Static("reject: " + rejectReason,
                    WidgetNode.Role.ERROR));
        }
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                Messages.TOOL_MESSAGE_CLASS);
    }
}
