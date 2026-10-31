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

package org.aethercode.code.tui;

import java.util.List;
import java.util.Map;

/**
 * Adapter for building Textual-compatible JSON descriptors.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.textual_adapter} module. The Java port
 * produces plain data records that an actual Textual-equivalent
 * renderer can consume; rendering itself is out of scope.</p>
 */
public final class TextualAdapter {
    private TextualAdapter() {}

    /** A binding descriptor. */
    public record Binding(String key, String action, String description, boolean show) {
        public static Binding of(String key, String action) {
            return new Binding(key, action, null, false);
        }
    }

    /** A widget descriptor. */
    public record Widget(String id, String type, Map<String, Object> props) {
    }

    /** A screen descriptor. */
    public record Screen(String type, List<Binding> bindings, List<Widget> widgets) {
    }

    /**
     * Build a default Textual adapter screen descriptor for a
     * conversation session.
     */
    public static Screen sessionScreen() {
        return new Screen(
                "session",
                List.of(
                        Binding.of("ctrl+c", "quit"),
                        Binding.of("ctrl+l", "clear"),
                        Binding.of("enter", "submit")),
                List.of(
                        new Widget("chat", "rich_log", Map.of("wrap", true)),
                        new Widget("status", "static", Map.of("id", "status"))));
    }
}
