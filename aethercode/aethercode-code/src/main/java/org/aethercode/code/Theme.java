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
