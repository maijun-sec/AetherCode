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
