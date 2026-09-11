package org.aethercode.code.configuration;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lightweight theme preference coercion shared by config providers and the
 * UI.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration.theme_resolution} module.</p>
 */
public final class ThemeResolution {
    private static final Logger LOGGER = Logger.getLogger(ThemeResolution.class.getName());

    private ThemeResolution() {}

    /**
     * Registry of canonical theme ids and human-readable labels.
     *
     * <p>Mirrors the Python {@code deepagents_code.theme} registry
     * interface; the full Textual-backed registry is loaded lazily by
     * the runtime so the configuration layer does not depend on Textual
     * on the startup hot path.</p>
     */
    public record ThemeRegistry(Map<String, String> entries) {
        public boolean contains(String id) {
            return id != null && entries.containsKey(id);
        }

        /**
         * Find a canonical id by either the registered id or its label,
         * case-insensitive.
         */
        public String findCanonical(String value) {
            if (value == null) return null;
            String folded = value.toLowerCase(Locale.ROOT);
            for (var e : entries.entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).equals(folded)) return e.getKey();
                if (e.getValue() != null && e.getValue().toLowerCase(Locale.ROOT).equals(folded)) {
                    return e.getKey();
                }
            }
            return null;
        }
    }

    /**
     * Resolve a user-supplied theme name to a canonical registry key.
     *
     * <p>Accepts the registry key or the human-readable label,
     * case-insensitive on both, with surrounding whitespace stripped.
     * Also applies the legacy {@code textual-ansi} to {@code ansi-light}
     * migration, which predates Textual 8.2.5.</p>
     *
     * @return canonical registry key, or {@code null} when the value
     *     is not a string or names no registered theme
     */
    public static String resolveThemeName(Object value, ThemeRegistry registry) {
        if (!(value instanceof String s)) return null;
        String name = s.strip();
        if ("textual-ansi".equals(name)) name = "ansi-light";
        if (registry != null) {
            if (registry.contains(name)) return name;
            return registry.findCanonical(name);
        }
        return null;
    }

    /**
     * Return {@code value} as a TOML table when it has the expected
     * runtime shape.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asTomlTable(Object value) {
        if (!(value instanceof Map<?, ?> m)) return null;
        for (Object k : m.keySet()) {
            if (!(k instanceof String)) return null;
        }
        return (Map<String, Object>) m;
    }

    /**
     * Resolve {@code [ui.terminal_themes][TERM_PROGRAM]} to a registered
     * theme. Returns canonical registry key, or {@code null} when no
     * valid mapping applies.
     */
    public static String resolveTerminalMapping(Map<String, Object> ui, ThemeRegistry registry) {
        if (ui == null) return null;
        Object terminalThemes = ui.get("terminal_themes");
        Map<String, Object> table = asTomlTable(terminalThemes);
        if (table == null) {
            LOGGER.warning("[ui.terminal_themes] should be a table mapping TERM_PROGRAM "
                    + "values to theme names; got " + (terminalThemes == null
                            ? "null" : terminalThemes.getClass().getSimpleName()));
            return null;
        }
        String termProgram = System.getenv("TERM_PROGRAM");
        if (termProgram == null) termProgram = "";
        termProgram = termProgram.strip();
        if (termProgram.isEmpty()) {
            if (!table.isEmpty()) {
                LOGGER.warning("[ui.terminal_themes] is configured but TERM_PROGRAM is unset; "
                        + "no per-terminal theme will be applied");
            }
            return null;
        }
        Object mapped = table.get(termProgram);
        String resolved = resolveThemeName(mapped, registry);
        if (resolved != null) return resolved;
        if (mapped instanceof String s) {
            LOGGER.warning("Unknown theme '" + s + "' mapped to TERM_PROGRAM='" + termProgram
                    + "' in [ui.terminal_themes]; ignoring");
        } else if (mapped != null) {
            LOGGER.warning("Expected string theme name for TERM_PROGRAM='" + termProgram
                    + "' in [ui.terminal_themes], got " + mapped.getClass().getSimpleName()
                    + "; ignoring");
        }
        return null;
    }
}
