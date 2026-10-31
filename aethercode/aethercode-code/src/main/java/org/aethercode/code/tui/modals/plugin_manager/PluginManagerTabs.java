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

package org.aethercode.code.tui.modals.plugin_manager;

/**
 * Clickable tab labels for the plugin manager header.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.modals.plugin_manager.tabs} module.
 * The Java port exposes the data needed to render tabs; the
 * Textual-style {@code Static} subclass lives in the runtime
 * widget tree and is not ported.</p>
 */
public final class PluginManagerTabs {
    private PluginManagerTabs() {}

    /** A click event produced by a tab label. */
    public record Click(PluginTab.Tab tab) {}

    /**
     * A label descriptor for a tab.
     */
    public record TabLabel(PluginTab.Tab tab, String label, boolean active) {
        public String displayText() {
            return active ? "> " + label + " <" : "  " + label + "  ";
        }
    }

    /**
     * Build a label with the active marker.
     */
    public static TabLabel label(PluginTab.Tab tab, boolean active) {
        return new TabLabel(tab, PluginTab.tabLabels().get(tab), active);
    }
}
