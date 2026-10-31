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
