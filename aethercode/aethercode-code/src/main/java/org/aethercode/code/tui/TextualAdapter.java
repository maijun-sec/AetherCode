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
