package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Structured description of a widget's render tree.
 *
 * <p>The Textual framework used by the Python implementation composes widgets
 * from a tree of reactive components, each with their own CSS, focus, and
 * event handling. The Java port cannot reuse Textual, so each widget's
 * {@code render()} method returns a {@code WidgetNode} tree — a value
 * describing the structural shape of the widget's output (titles, body rows,
 * input slots, option lists, help footers, etc.) for any host TUI to project
 * onto a real terminal.</p>
 *
 * <p>The variants mirror the Textual primitives most commonly used in the
 * Python source: containers (vertical, horizontal, scroll), static text
 * rows, markdown bodies, code blocks, option lists, input fields, status
 * lines, link spans, and copy-to-clipboard spans.</p>
 *
 * <p>All variants are immutable records. Style is keyed by a small set of
 * stable theme names ("primary", "muted", "warning", "error", "success",
 * "accent", "tool") rather than concrete colors so a host can apply its
 * active palette.</p>
 */
public sealed interface WidgetNode
        permits WidgetNode.Container,
                WidgetNode.Static,
                WidgetNode.Markdown,
                WidgetNode.Code,
                WidgetNode.OptionList,
                WidgetNode.Option,
                WidgetNode.Input,
                WidgetNode.StatusLine,
                WidgetNode.Spinner,
                WidgetNode.Progress,
                WidgetNode.Log,
                WidgetNode.Link,
                WidgetNode.CopySpan,
                WidgetNode.HRule,
                WidgetNode.KeyHint,
                WidgetNode.Placeholder,
                WidgetNode.BarChart,
                WidgetNode.Toolbar,
                WidgetNode.Section,
                WidgetNode.Row {

    /** Theme role for a styled span. Stable across all hosts. */
    enum Role { PRIMARY, MUTED, WARNING, ERROR, SUCCESS, ACCENT, TOOL, TEXT, BACKGROUND }

    /** Optional positional weight (1fr) for grid-ish layout hosts. */
    record Size(int rows, int cols) {
        public static final Size AUTO = new Size(-1, -1);
    }

    /** A layout container. Children are rendered in the order they appear. */
    record Container(Layout layout, List<WidgetNode> children, String cssClass) implements WidgetNode {
        public Container(Layout layout, List<WidgetNode> children) {
            this(layout, children, "");
        }
    }

    enum Layout { VERTICAL, HORIZONTAL, VERTICAL_SCROLL, HORIZONTAL_SCROLL, GRID }

    /** A single line of styled text. The most common node in the tree. */
    record Static(String text, Role role, boolean dim, boolean bold, boolean italic) implements WidgetNode {
        public Static(String text) {
            this(text, Role.TEXT, false, false, false);
        }
        public Static(String text, Role role) {
            this(text, role, false, false, false);
        }
    }

    /** Markdown body. Hosts are responsible for rendering. */
    record Markdown(String source, String cssClass) implements WidgetNode {
        public Markdown(String source) {
            this(source, "");
        }
    }

    /** A fenced code block. */
    record Code(String language, String body, boolean truncated) implements WidgetNode {}

    /** List of selectable options (Textual {@code OptionList}). */
    record OptionList(List<Option> options, int highlighted, String id) implements WidgetNode {
        public OptionList(List<Option> options, String id) {
            this(options, 0, id);
        }
    }

    /** A single option row inside an {@link OptionList}. */
    record Option(String id, String label, String description, boolean primary, boolean selected) implements WidgetNode {}

    /** A free-text input field. */
    record Input(String id, String placeholder, String value, boolean password) implements WidgetNode {
        public Input(String id, String placeholder) {
            this(id, placeholder, "", false);
        }
    }

    /** A status line that may carry a spinner (animated glyph + elapsed). */
    record StatusLine(String text, boolean animated, Optional<Role> role) implements WidgetNode {
        public StatusLine(String text) {
            this(text, false, Optional.empty());
        }
    }

    /** An animated spinner with status text and an elapsed-time label. */
    record Spinner(String status, long elapsedSeconds, boolean paused) implements WidgetNode {
        public Spinner(String status) {
            this(status, 0L, false);
        }
    }

    /** A progress bar with absolute or percentage progress. */
    record Progress(double fraction, String label) implements WidgetNode {}

    /** An append-only log tail (Textual {@code Log}). */
    record Log(List<String> lines, int maxLines, boolean autoScroll) implements WidgetNode {}

    /** A clickable link span. */
    record Link(String text, String href, Role role) implements WidgetNode {}

    /** A click-to-copy span: the text is copied, the label words the toast. */
    record CopySpan(String text, String label) implements WidgetNode {}

    /** A horizontal rule. */
    record HRule() implements WidgetNode {
        public static final HRule INSTANCE = new HRule();
    }

    /** A keyboard hint chip rendered in a help footer. */
    record KeyHint(String key, String description) implements WidgetNode {}

    /** A toolbar row: typically a horizontal strip of {@link KeyHint}s. */
    record Toolbar(List<KeyHint> hints) implements WidgetNode {}

    /** A section header. */
    record Section(String title, List<WidgetNode> children) implements WidgetNode {
        public Section(String title) {
            this(title, List.of());
        }
        public Section withChild(WidgetNode child) {
            java.util.ArrayList<WidgetNode> next = new java.util.ArrayList<>(children);
            next.add(child);
            return new Section(title, List.copyOf(next));
        }
    }

    /** A labeled value row (e.g. "model: anthropic:claude-opus-4-8"). */
    record Row(String label, WidgetNode value) implements WidgetNode {}

    /**
     * A bar-chart-style row used by the context-usage visualization.
     * {@code segments} maps a category name to a 0..1 fraction of the bar.
     */
    record BarChart(List<BarChartSegment> segments, long total, long scale) implements WidgetNode {}

    /** A single segment of a {@link BarChart}. */
    record BarChartSegment(String label, double fraction, Role role) {}

    /** A placeholder node used when the port knows a widget exists but does
     * not (yet) implement its render. The string describes the omission. */
    record Placeholder(String description) implements WidgetNode {}

    /** Convenience: empty container. */
    static Container empty(Layout layout) {
        return new Container(layout, List.of());
    }

    /** Convenience: vertical stack of children. */
    static Container vertical(WidgetNode... children) {
        return new Container(Layout.VERTICAL, List.of(children));
    }

    /** Convenience: horizontal row of children. */
    static Container horizontal(WidgetNode... children) {
        return new Container(Layout.HORIZONTAL, List.of(children));
    }

    /** Render with no children. */
    default WidgetNode withChildren(List<WidgetNode> kids) {
        if (this instanceof Container c) {
            return new Container(c.layout(), kids, c.cssClass());
        }
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no children");
    }

    /** Convert a {@link Map}-based theme override to a {@link Role}. */
    static Role role(String name) {
        if (name == null) return Role.TEXT;
        return switch (name.toLowerCase()) {
            case "primary" -> Role.PRIMARY;
            case "muted", "text-muted" -> Role.MUTED;
            case "warning" -> Role.WARNING;
            case "error" -> Role.ERROR;
            case "success" -> Role.SUCCESS;
            case "accent" -> Role.ACCENT;
            case "tool" -> Role.TOOL;
            default -> Role.TEXT;
        };
    }
}
