package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic detail modal for a single pending notification.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.notification_detail}.
 * The Python {@code NotificationDetailScreen} is a {@code ModalScreen[ActionId | None]}
 * that renders a single notification's title, body, and action list.
 * Esc closes without firing an action.</p>
 */
public class NotificationDetailScreen extends ModalScreen<String> {

    public record NotificationAction(String actionId, String label, boolean primary) {}
    public record PendingNotification(String title, String body, List<NotificationAction> actions) {}

    private final PendingNotification entry;
    private final List<OptionEntry> options = new ArrayList<>();
    private int selected = 0;

    public NotificationDetailScreen(PendingNotification entry) {
        super("", "notification-detail-screen");
        this.entry = entry;
    }

    public PendingNotification entry() { return entry; }
    public int selected() { return selected; }

    public static final class OptionEntry {
        public final String label;
        public final String actionId;
        public boolean isSelected;
        public OptionEntry(String label, String actionId) {
            this.label = label;
            this.actionId = actionId;
        }
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static(entry.title(), WidgetNode.Role.PRIMARY, false, true, false));
        if (entry.body() != null && !entry.body().isEmpty()) {
            rows.add(new WidgetNode.Static(entry.body(), WidgetNode.Role.MUTED));
        }
        for (int idx = 0; idx < entry.actions().size(); idx++) {
            NotificationAction a = entry.actions().get(idx);
            OptionEntry o = new OptionEntry(a.label(), a.actionId());
            options.add(o);
            String prefix = idx == selected ? "▶ " : "  ";
            rows.add(new WidgetNode.Static(prefix + a.label(),
                    a.primary() ? WidgetNode.Role.PRIMARY : WidgetNode.Role.TEXT));
        }
        rows.add(new WidgetNode.Static("↑/↓ navigate · Enter select · Esc back",
                WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                "notification-detail-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Back"),
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
        dismiss(options.get(selected).actionId);
    }

    public void actionCancel() { dismiss(null); }
}
