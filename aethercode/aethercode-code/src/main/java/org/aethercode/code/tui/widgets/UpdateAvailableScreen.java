package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated modal for the update-available notification.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.update_available}. The
 * Python {@code UpdateAvailableScreen} is a {@code ModalScreen[ActionId | None]}
 * that surfaces a single update notification with a "View changelog" row
 * and one row per configured action. The Java port preserves the public
 * action dispatch and the changelog-click contract.</p>
 */
public class UpdateAvailableScreen extends ModalScreen<String> {

    /** Identifier of a {@code NotificationAction}. The host injects the
     *  concrete value type from {@code deepagents_code.notifications}. */
    public record NotificationAction(String actionId, String label, boolean primary) {}

    public record PendingNotification(String title, String body, List<NotificationAction> actions) {}

    /** URL of the changelog, surfaced by the modal's "View changelog" row. */
    public static final String CHANGELOG_URL = "https://github.com/langchain-ai/deepagents/blob/main/libs/code/CHANGELOG.md";

    private final PendingNotification entry;
    private final List<OptionEntry> options = new ArrayList<>();
    private int selected = 0;

    public UpdateAvailableScreen(PendingNotification entry) {
        super("", "update-available-screen");
        this.entry = entry;
    }

    public PendingNotification entry() { return entry; }
    public int selected() { return selected; }
    public List<OptionEntry> options() { return List.copyOf(options); }

    /** A row in the option list. */
    public static final class OptionEntry {
        public final String label;
        public final String actionId;
        public final boolean changelog;
        public boolean isSelected;
        public OptionEntry(String label, String actionId, boolean changelog) {
            this.label = label;
            this.actionId = actionId;
            this.changelog = changelog;
        }
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static(entry.title(), WidgetNode.Role.SUCCESS, false, true, false));
        if (entry.body() != null && !entry.body().isEmpty()) {
            rows.add(new WidgetNode.Static(entry.body(), WidgetNode.Role.MUTED));
        }
        // Changelog row.
        options.add(new OptionEntry("View changelog", null, true));
        // Action rows.
        for (int idx = 0; idx < entry.actions().size(); idx++) {
            NotificationAction a = entry.actions().get(idx);
            options.add(new OptionEntry(a.label(), a.actionId(), false));
        }
        for (int i = 0; i < options.size(); i++) {
            OptionEntry o = options.get(i);
            String prefix = i == selected ? "▶ " : "  ";
            rows.add(new WidgetNode.Static(prefix + o.label,
                    o.changelog ? WidgetNode.Role.MUTED : WidgetNode.Role.TEXT));
        }
        rows.add(new WidgetNode.Static("↑/↓ navigate · Enter select · Esc close",
                WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                "update-available-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Close"),
                KeyBinding.priority("up", "move_up", "Up"),
                KeyBinding.priority("k", "move_up", "Up"),
                KeyBinding.priority("down", "move_down", "Down"),
                KeyBinding.priority("j", "move_down", "Down"),
                KeyBinding.priority("tab", "move_down", "Next"),
                KeyBinding.priority("shift+tab", "move_up", "Previous"),
                KeyBinding.priority("enter", "activate", "Select"));
    }

    public void actionMoveUp() {
        if (options.isEmpty()) return;
        selected = (selected - 1 + options.size()) % options.size();
    }

    public void actionMoveDown() {
        if (options.isEmpty()) return;
        selected = (selected + 1) % options.size();
    }

    public void actionActivate() {
        if (options.isEmpty()) { dismiss(null); return; }
        OptionEntry o = options.get(selected);
        if (o.changelog) {
            openChangelog();
            return;
        }
        dismiss(o.actionId);
    }

    public void actionCancel() { dismiss(null); }

    /** Open {@link #CHANGELOG_URL} in the user's browser. */
    public void openChangelog() {
        // The host TUI is responsible for the actual browser launch.
    }
}
