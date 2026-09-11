package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Read-only in-app Debug Console modal.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.debug_console}. The
 * Python module is a {@code ModalScreen[None]} shown with {@code Ctrl+\}
 * that displays a live session/runtime snapshot plus a tail of recent
 * {@code deepagents_code.*} log records. The Java port preserves the
 * public surface (snapshot field rows, log buffer hook, copy-to-clipboard
 * metadata) and the dismiss contract.</p>
 */
public class DebugConsole extends ModalScreen<Void> {

    /** Textual key name for the {@code Ctrl+\} chord that toggles the console. */
    public static final String DEBUG_TOGGLE_KEY = "ctrl+backslash";

    /** Seconds between log-tail refresh ticks. */
    public static final double REFRESH_INTERVAL = 0.5;

    /** Maximum records retained per level by an open debug console view. */
    public static final int RECORD_LIMIT = 200;

    /** Filter values for the level filter dropdown. */
    public enum Filter {
        ALL, MIN_DEBUG, MIN_INFO, MIN_WARNING, MIN_ERROR
    }

    /** A single row in the console's session snapshot. */
    public record SnapshotField(String label, String value,
                                boolean copyable, String threadId) {
        public SnapshotField(String label, String value) {
            this(label, value, false, null);
        }
    }

    /** A log record in the in-memory buffer. */
    public record LogRecord(String level, double timestamp, String logger, String message) {}

    private final List<SnapshotField> snapshot;
    private Filter filter;
    private boolean clickToCopy;

    public DebugConsole(List<SnapshotField> snapshot) {
        super("", "debug-console-screen");
        this.snapshot = snapshot == null ? List.of() : List.copyOf(snapshot);
        this.filter = Filter.ALL;
        this.clickToCopy = false;
    }

    public List<SnapshotField> snapshot() { return snapshot; }
    public Filter filter() { return filter; }
    public void setFilter(Filter filter) { this.filter = filter; }
    public boolean clickToCopy() { return clickToCopy; }
    public void setClickToCopy(boolean v) { this.clickToCopy = v; }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new java.util.ArrayList<>();
        for (SnapshotField f : snapshot) {
            rows.add(new WidgetNode.Row(f.label(),
                    copyableSpan(f) ? CopySpans.span(f.value(), f.label())
                                    : new WidgetNode.Static(f.value())));
        }
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                new WidgetNode.Static("Debug Console",
                        WidgetNode.Role.PRIMARY, false, true, false),
                new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows),
                new WidgetNode.Static("filter: " + filter + " · click-to-copy: " + clickToCopy,
                        WidgetNode.Role.MUTED)
        ), "debug-console-screen");
    }

    private static boolean copyableSpan(SnapshotField f) {
        return f.copyable() && f.value() != null && !f.value().isEmpty();
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "close", "Close"),
                KeyBinding.of("c", "toggle_click_to_copy", "Click to copy"));
    }

    public void actionClose() { dismiss(null); }
    public void actionToggleClickToCopy() { this.clickToCopy = !this.clickToCopy; }
}
