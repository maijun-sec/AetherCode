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

import java.util.List;

/**
 * Confirmation modals for {@code /update} dependency-refresh flows in the TUI.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.update_confirm}. The
 * Python module exposes a private base {@code _DependencyConfirmScreen}
 * plus two concrete subclasses that share layout, styling, and the
 * {@code True}/{@code False} dismiss contract. The Java port mirrors the
 * same hierarchy.</p>
 */
public final class UpdateConfirm {

    private UpdateConfirm() {}

    /**
     * Shared base for the {@code /update} dependency-refresh modals.
     *
     * <p>Subclasses supply the title, body, and help text; the base owns
     * the layout, styling, and dismiss contract.</p>
     */
    public abstract static class DependencyConfirmScreen extends ModalScreen<Boolean> {
        private final String title;
        private final String body;
        private final String help;

        protected DependencyConfirmScreen(String cssClass, String title, String body, String help) {
            super("", cssClass);
            this.title = title;
            this.body = body;
            this.help = help;
        }

        public String title() { return title; }
        public String body() { return body; }
        public String help() { return help; }

        @Override
        public WidgetNode render() {
            WidgetNode titleNode = new WidgetNode.Static(title, WidgetNode.Role.PRIMARY, false, true, false);
            WidgetNode bodyNode = new WidgetNode.Static(body, WidgetNode.Role.TEXT);
            WidgetNode helpNode = new WidgetNode.Static(help,
                    WidgetNode.Role.MUTED, true, false, true);
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                    List.of(titleNode, bodyNode, helpNode), classes());
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "confirm", "Confirm"),
                    KeyBinding.priority("escape", "cancel", "Cancel"));
        }

        public void actionConfirm() { dismiss(Boolean.TRUE); }
        public void actionCancel() { dismiss(Boolean.FALSE); }
    }

    /**
     * Confirmation overlay before {@code /update --deps} upgrades dcode
     * itself. Dismisses with {@code true} when the user chooses the
     * app update first and {@code false} when they prefer to refresh
     * dependencies for the current app version.
     */
    public static final class UpdateBeforeDependenciesConfirmScreen extends DependencyConfirmScreen {
        public UpdateBeforeDependenciesConfirmScreen(String current, String latest) {
            super("update-before-deps-confirm-screen",
                    "Update dcode first?",
                    "A newer deepagents-code version is available ("
                            + current + " -> " + latest + "). Update dcode now, or "
                            + "refresh dependencies for the current version you already have.",
                    "Enter to update dcode, Esc to refresh current dependencies");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "confirm", "Update"),
                    KeyBinding.priority("escape", "cancel", "Refresh deps"));
        }
    }

    /**
     * Confirmation overlay for a dependency refresh.
     *
     * <p>Dismisses with {@code true} when the user confirms and
     * {@code false} when the user cancels.</p>
     */
    public static final class RefreshDependenciesConfirmScreen extends DependencyConfirmScreen {
        public RefreshDependenciesConfirmScreen(String plannedChanges) {
            super("refresh-dependencies-confirm-screen",
                    "Refresh dependencies?",
                    plannedChanges != null && !plannedChanges.isEmpty()
                            ? "deepagents-code is already up to date, but compatible dependency "
                              + "updates are available. Refresh to apply these changes:\n\n"
                              + plannedChanges
                            : "deepagents-code is already up to date, but its dependencies "
                              + "can be re-resolved to the newest compatible versions. This may "
                              + "pull in newer minor releases of packages like langchain-openai.",
                    "Enter to refresh, Esc to cancel");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "confirm", "Refresh"),
                    KeyBinding.priority("escape", "cancel", "Cancel"));
        }
    }
}
