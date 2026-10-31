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
 * Theme tokens.
 *
 * <p>Java-native port of the Python {@code deepagents_code.theme} module.
 * The Java port exposes a single in-memory theme record; the TUI host
 * reads it to pick the palette for the current terminal capabilities.</p>
 */
public final class Theme {
    private Theme() {}

    /** A single theme token. */
    public record Token(String name, String foreground, String background, boolean bold) {
    }

    /** A theme. */
    public record ThemeSpec(String name, Map<String, Token> tokens) {
        public static ThemeSpec of(String name, Map<String, Token> tokens) {
            return new ThemeSpec(name, Map.copyOf(tokens));
        }
    }

    /** The default dark theme. */
    public static ThemeSpec defaultDark() {
        Map<String, Token> tokens = new LinkedHashMap<>();
        tokens.put("primary", new Token("primary", "cyan", null, false));
        tokens.put("muted", new Token("muted", "gray", null, false));
        tokens.put("warning", new Token("warning", "yellow", null, true));
        tokens.put("error", new Token("error", "red", null, true));
        tokens.put("success", new Token("success", "green", null, true));
        tokens.put("accent", new Token("accent", "magenta", null, false));
        tokens.put("tool", new Token("tool", "blue", null, false));
        tokens.put("text", new Token("text", "white", null, false));
        return ThemeSpec.of("dark", tokens);
    }

    /** The default light theme. */
    public static ThemeSpec defaultLight() {
        Map<String, Token> tokens = new LinkedHashMap<>();
        tokens.put("primary", new Token("primary", "blue", null, false));
        tokens.put("muted", new Token("muted", "gray", null, false));
        tokens.put("warning", new Token("warning", "yellow", null, true));
        tokens.put("error", new Token("error", "red", null, true));
        tokens.put("success", new Token("success", "green", null, true));
        tokens.put("accent", new Token("accent", "magenta", null, false));
        tokens.put("tool", new Token("tool", "blue", null, false));
        tokens.put("text", new Token("text", "black", null, false));
        return ThemeSpec.of("light", tokens);
    }
}
