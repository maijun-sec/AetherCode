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
 * Application-wide configuration.
 *
 * <p>Java-native port of the Python {@code deepagents_code.config} module.
 * The Java port exposes the public types and constants used by the
 * CLI/TUI; the full TOML-based config loader lands with the
 * {@code deepagents-code.configuration} subdirectory port.</p>
 */
public final class Config {
    private Config() {}

    /** Charset mode detected for the active terminal. */
    public enum CharsetMode { ASCII, UNICODE }

    /** Glyph set for the active terminal mode. */
    public record Glyphs(
            String check,
            String cross,
            String warning,
            String question,
            String arrow,
            String bullet,
            String ellipsis,
            String newline,
            String tab) {
    }

    /** Application settings. */
    public record Settings(
            CharsetMode charsetMode,
            Glyphs glyphs,
            int maxIterations,
            boolean yoloSwitcherEnabled,
            boolean memoryAutoSaveEnabled,
            boolean openaiPromptCacheKeyEnabled) {
    }

    /** Default settings. */
    public static Settings defaults() {
        return new Settings(CharsetMode.UNICODE, unicodeGlyphs(), 3, true, true, true);
    }

    /** Unicode glyphs. */
    public static Glyphs unicodeGlyphs() {
        return new Glyphs("✔", "✘", "⚠", "?", "→", "•", "…", "\n", "    ");
    }

    /** ASCII glyphs. */
    public static Glyphs asciiGlyphs() {
        return new Glyphs("[+]", "[x]", "[!]", "[?]", "->", "*", "...", "\n", "    ");
    }
}
