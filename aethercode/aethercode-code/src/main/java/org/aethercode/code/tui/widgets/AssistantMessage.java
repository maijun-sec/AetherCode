package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Assistant-message streaming widget.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.messages.AssistantMessage}.
 * The Python class is a Textual {@code Static} subclass that streams
 * content from a token iterator, applies markdown rendering on flush,
 * and handles link clicks. The Java port preserves the public
 * {@code update_content} / {@code finalize} / {@code cancel} API.</p>
 */
public class AssistantMessage extends Widget {

    private final StringBuilder buffer = new StringBuilder();
    private boolean streaming = true;
    private boolean cancelled;
    private String finalContent;

    public AssistantMessage() {
        super("", "assistant-message");
    }

    public boolean streaming() { return streaming; }
    public boolean cancelled() { return cancelled; }
    public String content() { return finalContent != null ? finalContent : buffer.toString(); }

    /** Append a delta to the streamed content. */
    public void updateContent(String delta) {
        if (delta == null || cancelled) return;
        buffer.append(delta);
    }

    /** Replace the entire content (e.g. after a regenerate). */
    public void setContent(String content) {
        buffer.setLength(0);
        if (content != null) buffer.append(content);
    }

    /** Finalize the message: no more deltas will arrive. */
    public void complete() {
        streaming = false;
        finalContent = buffer.toString();
    }

    /** Cancel the message; the buffer is frozen at its current state. */
    public void cancel() {
        cancelled = true;
        streaming = false;
        finalContent = buffer.toString();
    }

    @Override
    public WidgetNode render() {
        String body = content();
        if (body == null) body = "";
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Markdown(body, Messages.ASSISTANT_MESSAGE_CLASS));
        if (streaming) {
            rows.add(new WidgetNode.Static("…", WidgetNode.Role.MUTED, true, false, true));
        }
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                Messages.ASSISTANT_MESSAGE_CLASS);
    }
}
