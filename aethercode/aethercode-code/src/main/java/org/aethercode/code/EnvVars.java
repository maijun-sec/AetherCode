package org.aethercode.code;

import java.util.Locale;

/**
 * Environment-variable constants.
 *
 * <p>Java-native port of the Python {@code deepagents_code._env_vars}
 * module. The Java port exposes the canonical env-var name constants
 * and the {@link #isEnvTruthy(String)} helper the TUI uses to interpret
 * the env-var booleans.</p>
 */
public final class EnvVars {
    private EnvVars() {}

    /** Enable verbose debug logging. */
    public static final String DEBUG = "DEEPAGENTS_CODE_DEBUG";

    /** Force a specific command name (used in resume hints). */
    public static final String INVOKED_AS = "DEEPAGENTS_CODE_INVOKED_AS";

    /** Disable terminal escape output. */
    public static final String NO_TERMINAL_ESCAPE = "DEEPAGENTS_CODE_NO_TERMINAL_ESCAPE";

    /** Kitty keyboard protocol override. */
    public static final String KITTY_KEYBOARD = "DEEPAGENTS_CODE_KITTY_KEYBOARD";

    /** Server env-var prefix. */
    public static final String SERVER_ENV_PREFIX = "DEEPAGENTS_CODE_SERVER_";

    private static final java.util.Set<String> TRUE_VALUES = java.util.Set.of("1", "true", "yes", "on");
    private static final java.util.Set<String> FALSE_VALUES = java.util.Set.of("0", "false", "no", "off");

    /** Whether a env-var value reads as truthy. */
    public static boolean isEnvTruthy(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return TRUE_VALUES.contains(v);
    }

    /** Whether a env-var value reads as falsy. */
    public static boolean isEnvFalsy(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return FALSE_VALUES.contains(v);
    }
}
