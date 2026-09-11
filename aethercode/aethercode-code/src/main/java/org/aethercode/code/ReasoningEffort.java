package org.aethercode.code;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Model reasoning-effort levels.
 *
 * <p>Java-native port of the Python {@code deepagents_code.reasoning_effort}
 * module. The Java port exposes the closed vocabulary of effort levels
 * plus the {@code validate()} helper the TUI uses to filter the model
 * selector.</p>
 */
public final class ReasoningEffort {
    private ReasoningEffort() {}

    /** Closed vocabulary of effort levels. */
    public enum Level {
        LOW, MEDIUM, HIGH;

        public String wireName() { return name().toLowerCase(Locale.ROOT); }

        public static Level coerce(Object value, Level fallback) {
            if (value instanceof Level l) return l;
            if (value instanceof String s) {
                try { return Level.valueOf(s.toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) { return fallback; }
            }
            return fallback;
        }
    }

    /** Mapping of effort level to its hint string. */
    public static final Map<Level, String> HINTS = Map.of(
            Level.LOW, "low latency, less thorough",
            Level.MEDIUM, "balanced",
            Level.HIGH, "most thorough, slowest");

    /** All known effort levels in display order. */
    public static final Set<Level> ALL = Set.of(Level.LOW, Level.MEDIUM, Level.HIGH);

    /** Return the hint for a level. */
    public static String hint(Level level) {
        return HINTS.getOrDefault(level, "");
    }
}
