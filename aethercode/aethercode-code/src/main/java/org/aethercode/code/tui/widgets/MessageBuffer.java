package org.aethercode.code.tui.widgets;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Streaming content-block buffer.
 *
 * <p>Java port of the streaming content buffer that backs assistant
 * message rendering. The Python module keeps a per-message deque of
 * content blocks (text deltas, tool-use deltas, etc.) and exposes
 * helpers for merging consecutive text blocks and for collapsing
 * trailing whitespace.</p>
 *
 * <p>The Java port preserves the public surface
 * ({@link #append}, {@link #finalize}, {@link #cancel}, {@link #blocks()}).</p>
 */
public class MessageBuffer {

    /** A single content block in the buffer. */
    public record Block(String kind, String content) {
        public static Block text(String text) { return new Block("text", text); }
        public static Block toolUse(String toolUse) { return new Block("tool_use", toolUse); }
    }

    private final Deque<Block> blocks = new ArrayDeque<>();
    private boolean streaming = true;
    private boolean cancelled;

    public List<Block> blocks() {
        return List.copyOf(blocks);
    }

    public boolean streaming() { return streaming; }
    public boolean cancelled() { return cancelled; }

    /** Append a text delta. Merges into the trailing text block when present. */
    public void append(String delta) {
        if (delta == null || cancelled) return;
        Block last = blocks.peekLast();
        if (last != null && "text".equals(last.kind())) {
            blocks.pollLast();
            blocks.addLast(new Block("text", last.content() + delta));
        } else {
            blocks.addLast(Block.text(delta));
        }
    }

    /** Append a tool-use block (e.g. a tool call the model wants to make). */
    public void appendToolUse(String toolUse) {
        if (toolUse == null || cancelled) return;
        blocks.addLast(Block.toolUse(toolUse));
    }

    /** Complete the buffer: no more deltas will arrive. */
    public void complete() { streaming = false; }

    /** Cancel the buffer; the contents are frozen at their current state. */
    public void cancel() { streaming = false; cancelled = true; }

    /** Render the buffer as a flat string for fallback display. */
    public String flatten() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) sb.append(b.content());
        return sb.toString();
    }
}
