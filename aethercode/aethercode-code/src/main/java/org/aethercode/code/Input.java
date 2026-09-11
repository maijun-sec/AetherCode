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
