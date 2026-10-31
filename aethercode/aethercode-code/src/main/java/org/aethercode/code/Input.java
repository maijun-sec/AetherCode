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

package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input/keymap surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.input} module.
 * The Java port exposes the small set of key-binding helpers the TUI
 * uses to advertise shortcuts; the full Textual keymap lands with the
 * TUI subdirectory port.</p>
 */
public final class Input {
    private Input() {}

    /** A key binding. */
    public record Binding(String key, String action, String description) {
        public static Binding of(String key, String action, String description) {
            return new Binding(key, action, description);
        }
    }

    /** Default key bindings. */
    public static final Map<String, Binding> DEFAULT_BINDINGS = Map.of(
            "ctrl+c", Binding.of("ctrl+c", "interrupt", "Interrupt the current turn"),
            "ctrl+d", Binding.of("ctrl+d", "exit", "Exit the TUI"),
            "ctrl+l", Binding.of("ctrl+l", "clear", "Clear the chat"),
            "ctrl+o", Binding.of("ctrl+o", "model", "Open the model picker"),
            "ctrl+p", Binding.of("ctrl+p", "command", "Open the command palette"),
            "ctrl+t", Binding.of("ctrl+t", "tools", "Open the tool catalog"),
            "ctrl+backslash", Binding.of("ctrl+backspace", "debug", "Open the debug console"));
}
