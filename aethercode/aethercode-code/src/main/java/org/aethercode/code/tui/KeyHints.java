package org.aethercode.code.tui;

/**
 * Shared keyboard hints for terminal UI components.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.key_hints} module.</p>
 */
public final class KeyHints {
    private KeyHints() {}

    /**
     * Build the navigation hint for modals whose Tab keys move the
     * cursor.
     */
    public static String modalNavigationHint(Glyphs glyphs) {
        if (glyphs == null) glyphs = Glyphs.ascii();
        return glyphs.arrowUp() + "/" + glyphs.arrowDown() + " or Tab/Shift+Tab navigate";
    }

    /**
     * Glyph set for the active terminal mode.
     */
    public record Glyphs(String arrowUp, String arrowDown, String bullet,
                          String checkmark, String boxHorizontal) {
        public static Glyphs ascii() {
            return new Glyphs("^", "v", "*", "+", "-");
        }
        public static Glyphs unicode() {
            return new Glyphs("↑", "↓", "•", "✓", "─");
        }
    }
}
