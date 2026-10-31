/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Notification hub for pending notices and warning preferences.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.notification_center}.
 * The Python {@code NotificationCenterScreen} is a
 * {@code ModalScreen[NotificationActionResult | None]} that surfaces
 * {@code PendingNotification} entries as single-line rows plus an
 * expandable settings section. The Java port preserves the public
 * surface and the dismiss contract.</p>
 */
public class NotificationCenterScreen extends ModalScreen<NotificationCenterScreen.ActionResult> {

    /** Identifier of a {@code NotificationAction}. The host injects the
     *  concrete value type from {@code deepagents_code.notifications}. */
    public record ActionId(String value) {}

    public record NotificationAction(ActionId actionId, String label, boolean primary) {}

    public record PendingNotification(String key, String title, String body,
                                      List<NotificationAction> actions) {}

    /** Dismissal payload identifying which action the user picked. */
    public record ActionResult(String key, ActionId actionId) {}

    private final List<PendingNotification> entries;
    private final List<ToggleRow> settings;
    private int selectedRow = 0;
    private boolean settingsExpanded;

    public NotificationCenterScreen(List<PendingNotification> entries) {
        super("", "notification-center-screen");
        this.entries = entries == null ? List.of() : List.copyOf(entries);
        this.settings = new ArrayList<>();
        for (NotificationSettings.Toggle t : NotificationSettings.WARNING_TOGGLES) {
            settings.add(new ToggleRow(t.warningKey(), t.label(), true));
        }
    }

    public List<PendingNotification> entries() { return entries; }
    public List<ToggleRow> settings() { return settings; }
    public int selectedRow() { return selectedRow; }
    public boolean settingsExpanded() { return settingsExpanded; }

    public static final class ToggleRow {
        public final String key;
        public final String label;
        public boolean enabled;
        public ToggleRow(String key, String label, boolean enabled) {
            this.key = key;
            this.label = label;
            this.enabled = enabled;
        }

        public String key() { return key; }
        public String label() { return label; }
        public boolean enabled() { return enabled; }
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static("Notification Center",
                WidgetNode.Role.PRIMARY, false, true, false));
        if (entries.isEmpty()) {
            rows.add(new WidgetNode.Static("No pending notifications.",
                    WidgetNode.Role.MUTED));
        }
        for (int i = 0; i < entries.size(); i++) {
            PendingNotification p = entries.get(i);
            String prefix = i == selectedRow ? "▶ " : "  ";
            rows.add(new WidgetNode.Static(prefix + p.title(),
                    p.title() == null || p.title().isEmpty() ? WidgetNode.Role.MUTED : WidgetNode.Role.TEXT));
        }
        rows.add(new WidgetNode.Static(""));
        rows.add(new WidgetNode.Static((settingsExpanded ? "▼ " : "▶ ")
                + "Notification settings",
                WidgetNode.Role.PRIMARY, false, true, false));
        if (settingsExpanded) {
            for (ToggleRow t : settings) {
                rows.add(new WidgetNode.Static(
                        "  " + (t.enabled ? "[x]" : "[ ]") + " " + t.label(),
                        WidgetNode.Role.TEXT));
            }
        }
        rows.add(new WidgetNode.Static(
                "↑/↓ navigate · Enter select · Esc close",
                WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                "notification-center-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Close"),
                KeyBinding.priority("up", "move_up", "Up"),
                KeyBinding.priority("k", "move_up", "Up"),
                KeyBinding.priority("down", "move_down", "Down"),
                KeyBinding.priority("j", "move_down", "Down"),
                KeyBinding.priority("enter", "select", "Select"),
                KeyBinding.priority("tab", "toggle_settings", "Settings"));
    }

    public void actionMoveUp() {
        if (selectedRow > 0) selectedRow--;
    }

    public void actionMoveDown() {
        if (selectedRow < entries.size() - 1) selectedRow++;
    }

    public void actionSelect() {
        if (selectedRow < 0 || selectedRow >= entries.size()) {
            dismiss(null);
            return;
        }
        PendingNotification p = entries.get(selectedRow);
        if (!p.actions().isEmpty()) {
            dismiss(new ActionResult(p.key(), p.actions().get(0).actionId()));
        }
    }

    public void actionCancel() { dismiss(null); }
    public void actionToggleSettings() { settingsExpanded = !settingsExpanded; }
}
