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
