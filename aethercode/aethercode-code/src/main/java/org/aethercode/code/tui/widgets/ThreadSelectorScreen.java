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
import java.util.Map;

/**
 * Interactive thread selector screen for the {@code /threads} command.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.thread_selector}. The
 * Python module exposes a {@code ThreadSelectorScreen} that lists known
 * threads with a fuzzy filter and an optional branch picker, returning
 * the chosen thread id on Enter and the cwd-switch choice on dismiss.
 * The Java port preserves the public data shapes and the dismiss
 * contract.</p>
 */
public class ThreadSelectorScreen extends ModalScreen<String> {

    /** One thread row in the selector. */
    public record ThreadInfo(
            String threadId,
            String agentName,
            int messages,
            String createdAt,
            String updatedAt,
            String gitBranch,
            String prompt,
            String cwd) {}

    /** Outcome of the cwd switch prompt invoked from the thread selector. */
    public enum CwdSwitchChoice { SWITCH, STAY, ABORT }

    private final List<ThreadInfo> threads;
    private final String currentCwd;
    private final boolean showBranchPicker;
    private int selected = 0;
    private String filter = "";
    private boolean showOnlyMine;
    private boolean relativeTime;

    public ThreadSelectorScreen(List<ThreadInfo> threads, String currentCwd,
                                boolean showBranchPicker) {
        super("", "thread-selector-screen");
        this.threads = threads == null ? List.of() : List.copyOf(threads);
        this.currentCwd = currentCwd;
        this.showBranchPicker = showBranchPicker;
    }

    public List<ThreadInfo> threads() { return threads; }
    public String currentCwd() { return currentCwd; }
    public boolean showBranchPicker() { return showBranchPicker; }
    public int selected() { return selected; }
    public String filter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }
    public boolean showOnlyMine() { return showOnlyMine; }
    public void setShowOnlyMine(boolean v) { this.showOnlyMine = v; }
    public boolean relativeTime() { return relativeTime; }
    public void setRelativeTime(boolean v) { this.relativeTime = v; }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static("Select Thread",
                WidgetNode.Role.PRIMARY, false, true, false));
        rows.add(new WidgetNode.Input("thread-filter", "Filter…", filter, false));
        for (int i = 0; i < threads.size(); i++) {
            ThreadInfo t = threads.get(i);
            String prefix = i == selected ? "▶ " : "  ";
            rows.add(new WidgetNode.Static(
                    prefix + t.threadId() + "  " + t.agentName()
                            + "  " + t.messages() + " msgs",
                    i == selected ? WidgetNode.Role.PRIMARY : WidgetNode.Role.TEXT));
        }
        rows.add(new WidgetNode.Static("↑/↓ navigate · Enter select · Esc cancel",
                WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                "thread-selector-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Cancel"),
                KeyBinding.priority("up", "move_up", "Up"),
                KeyBinding.priority("k", "move_up", "Up"),
                KeyBinding.priority("down", "move_down", "Down"),
                KeyBinding.priority("j", "move_down", "Down"),
                KeyBinding.priority("enter", "select", "Select"));
    }

    public void actionCancel() { dismiss(null); }
    public void actionMoveUp() {
        if (threads.isEmpty()) return;
        selected = (selected - 1 + threads.size()) % threads.size();
    }
    public void actionMoveDown() {
        if (threads.isEmpty()) return;
        selected = (selected + 1) % threads.size();
    }
    public void actionSelect() {
        if (threads.isEmpty()) { dismiss(null); return; }
        dismiss(threads.get(selected).threadId());
    }
}
