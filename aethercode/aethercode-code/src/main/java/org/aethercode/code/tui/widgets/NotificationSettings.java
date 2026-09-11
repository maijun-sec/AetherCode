package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.Map;

/**
 * Warning-toggle definitions for the notification hub's settings section.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.notification_settings}.
 * The Python module is a thin registry of {@code (key, label)} pairs that
 * drive the notification-center settings panel. The Java port exposes
 * the same registry as an immutable record list.</p>
 *
 * <p>Constants like {@code YOLO_WARNING_KEY} and {@code COLD_CACHE_WARNING_KEY}
 * are referenced from the Python {@code deepagents_code.approval_mode} and
 * {@code deepagents_code.cold_cache} modules. They are surfaced here as
 * {@code String} constants so other widgets in this package can refer to
 * them without depending on those modules.</p>
 */
public final class NotificationSettings {

    /** Warning key for YOLO mode acknowledgement (from {@code deepagents_code.approval_mode}). */
    public static final String YOLO_WARNING_KEY = "yolo_mode";

    /** Warning key for cold-cache prompt-cost warning (from {@code deepagents_code.cold_cache}). */
    public static final String COLD_CACHE_WARNING_KEY = "cold_cache_warning";

    /** Warning key for missing {@code ripgrep} binary. */
    public static final String RIPGREP_WARNING_KEY = "ripgrep";

    /** Warning key for missing TAVILY API key. */
    public static final String TAVILY_WARNING_KEY = "tavily";

    /**
     * A single toggle row in the notification settings panel.
     *
     * @param warningKey Stable identifier; the host stores the user's choice
     *                   against this key.
     * @param label      Human-readable label rendered in the panel.
     */
    public record Toggle(String warningKey, String label) {}

    /**
     * The static list of warning toggles, in display order.
     *
     * <p>Mirrors the Python {@code WARNING_TOGGLES} module-level constant.
     * The order is the render order in the settings panel.</p>
     */
    public static final List<Toggle> WARNING_TOGGLES = List.of(
            new Toggle(COLD_CACHE_WARNING_KEY, "Warn before expensive cold prompt-cache turns"),
            new Toggle(RIPGREP_WARNING_KEY, "Warn when ripgrep is not installed"),
            new Toggle(TAVILY_WARNING_KEY, "Warn when TAVILY_API_KEY is not set (web search)"),
            new Toggle(YOLO_WARNING_KEY, "Warn when YOLO mode is active (no approval review)")
    );

    /** Convenience: look up a toggle's label by warning key. */
    public static String labelFor(String key) {
        for (Toggle t : WARNING_TOGGLES) {
            if (t.warningKey().equals(key)) return t.label();
        }
        return key;
    }

    /** Convenience: render the registry as a {@link WidgetNode} section. */
    public static WidgetNode render() {
        WidgetNode.Section section = new WidgetNode.Section("Notification settings");
        for (Toggle t : WARNING_TOGGLES) {
            section = section.withChild(
                    new WidgetNode.Row(t.warningKey(), new WidgetNode.Static(t.label())));
        }
        return section;
    }

    private NotificationSettings() {
        // No instances; this is a registry module.
    }

    // Unused: keeps imports of Map/WidgetNode warnings clean for future
    // expansion (e.g., per-toggle copy-text/label maps for click-to-copy).
    @SuppressWarnings("unused")
    private static Map<String, String> labelOverrides() {
        return Map.of();
    }
}
