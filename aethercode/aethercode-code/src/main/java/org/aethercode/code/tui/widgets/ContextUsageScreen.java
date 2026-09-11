package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Color-coded context-window visualization for {@code /context}.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.context_usage}. The
 * Python module defines a private {@code _ContextUsage(Static)} that
 * renders a header ({@code Context · <model> <bar> <usage> / <max> <pct>%})
 * and a stacked bar of categories, plus a {@code ContextUsageScreen}
 * modal that hosts it.</p>
 *
 * <p>The Java port preserves the data model (a list of categories
 * with token counts and roles) and renders the result as a
 * {@link WidgetNode.BarChart} wrapped in a screen.</p>
 */
public class ContextUsageScreen extends ModalScreen<Void> {

    /** A stacked-bar category (label, tokens, theme role). */
    public record Category(String label, long tokens, WidgetNode.Role role) {}

    private final Long contextTokens;        // may be null
    private final Long conversationTokens;   // may be null
    private final Long contextLimit;         // may be null
    private final String modelSpec;
    private final boolean approximate;

    public ContextUsageScreen(Long contextTokens, Long conversationTokens,
                              Long contextLimit, String modelSpec, boolean approximate) {
        super("", "context-usage-screen");
        this.contextTokens = contextTokens;
        this.conversationTokens = conversationTokens;
        this.contextLimit = (contextLimit != null && contextLimit > 0) ? contextLimit : null;
        this.modelSpec = modelSpec == null || modelSpec.isEmpty() ? "Unknown model" : modelSpec;
        this.approximate = approximate;
    }

    public Optional<Long> contextTokens() { return Optional.ofNullable(contextTokens); }
    public Optional<Long> conversationTokens() { return Optional.ofNullable(conversationTokens); }
    public Optional<Long> contextLimit() { return Optional.ofNullable(contextLimit); }
    public String modelSpec() { return modelSpec; }
    public boolean approximate() { return approximate; }

    /** Compute the categories rendered as a stacked bar. */
    public List<Category> categories() {
        List<Category> out = new ArrayList<>();
        long total = contextTokens == null ? 0L : Math.max(0, contextTokens);
        long conversation = conversationTokens == null ? 0L : Math.max(0, conversationTokens);
        if (contextTokens == null) {
            if (conversation > 0) {
                out.add(new Category("Conversation estimate", conversation, WidgetNode.Role.PRIMARY));
            }
        } else if (total > 0) {
            if (conversationTokens == null) {
                out.add(new Category("Used context", total, WidgetNode.Role.ACCENT));
            } else {
                long conv = Math.min(conversation, total);
                long overhead = total - conv;
                if (overhead > 0) {
                    out.add(new Category("System prompt + tools", overhead, WidgetNode.Role.WARNING));
                }
                if (conv > 0) {
                    out.add(new Category("Conversation", conv, WidgetNode.Role.PRIMARY));
                }
            }
        }
        if (contextTokens != null && contextLimit != null) {
            long free = Math.max(0, contextLimit - contextTokens);
            out.add(new Category("Free space", free, WidgetNode.Role.MUTED));
        }
        return out;
    }

    public long totalUsage() {
        if (contextTokens != null) return Math.max(0, contextTokens);
        if (conversationTokens != null) return Math.max(0, conversationTokens);
        return 0L;
    }

    public long scale() {
        long max = contextLimit == null ? 0L : contextLimit;
        long sum = 0L;
        for (Category c : categories()) sum += c.tokens();
        return Math.max(max, Math.max(sum, 1L));
    }

    public String formatTokenCount(long tokens) {
        if (tokens < 1_000L) return tokens + " tokens";
        if (tokens < 1_000_000L) return String.format("%.1fk", tokens / 1000.0);
        return String.format("%.1fM", tokens / 1_000_000.0);
    }

    @Override
    public WidgetNode render() {
        long usage = totalUsage();
        long scale = scale();
        String maximum = contextLimit == null ? "unavailable" : formatTokenCount(contextLimit);
        String prefix = (approximate || contextTokens == null) ? "~" : "";
        String right = prefix + formatTokenCount(usage) + " / " + maximum;
        if (contextTokens != null && contextLimit != null && contextLimit > 0) {
            right += String.format("  %.1f%%", contextTokens * 100.0 / contextLimit);
        }
        WidgetNode header = new WidgetNode.Row(
                "Context",
                new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL, List.of(
                        new WidgetNode.Static("Context ", WidgetNode.Role.PRIMARY, false, true, false),
                        new WidgetNode.Static(" · ", WidgetNode.Role.MUTED),
                        new WidgetNode.Static(modelSpec, WidgetNode.Role.TEXT),
                        new WidgetNode.Static(" " + right, WidgetNode.Role.MUTED)
                )));
        List<WidgetNode.BarChartSegment> segments = new ArrayList<>();
        for (Category c : categories()) {
            double fraction = scale == 0 ? 0 : (double) c.tokens() / scale;
            segments.add(new WidgetNode.BarChartSegment(c.label(), fraction, c.role()));
        }
        WidgetNode bar = new WidgetNode.BarChart(segments, usage, scale);
        List<WidgetNode> rows = new ArrayList<>();
        for (Category c : categories()) {
            double percent = scale == 0 ? 0 : c.tokens() * 100.0 / scale;
            String value = formatTokenCount(c.tokens()) + "  ·  " + String.format("%.1f%%", percent);
            rows.add(new WidgetNode.Row(c.label(),
                    new WidgetNode.Static("  " + c.label() + "    " + value, WidgetNode.Role.MUTED)));
        }
        if (rows.isEmpty()) {
            rows.add(new WidgetNode.Static("No context usage reported yet.",
                    WidgetNode.Role.MUTED, true, false, false));
        } else if (contextTokens == null) {
            rows.add(new WidgetNode.Static("Total usage unavailable.",
                    WidgetNode.Role.MUTED, true, false, false));
        }
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(header, bar, new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows)),
                "context-usage-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(KeyBinding.of("escape", "close", "Close"));
    }

    public void actionClose() { dismiss(null); }
}
