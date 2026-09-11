package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Terminal capability detection.
 *
 * <p>Java-native port of the Python {@code deepagents_code.terminal_capabilities}
 * module. Detects optional terminal features (kitty-keyboard protocol
 * support) without reading from {@code stdin}. Detection is conservative
 * and relies on side-effect-free terminal identity signals plus an explicit
 * environment-variable override.</p>
 */
public final class TerminalCapabilities {
    private TerminalCapabilities() {}

    private static final Logger LOG = LoggerFactory.getLogger(TerminalCapabilities.class);

    /** Env-var name consulted for an explicit kitty-keyboard override. */
    public static final String KITTY_KEYBOARD = "DEEPAGENTS_CODE_KITTY_KEYBOARD";

    private static final Set<String> TRUE_VALUES = Set.of("1", "true", "yes", "on");
    private static final Set<String> FALSE_VALUES = Set.of("0", "false", "no", "off");
    private static final Set<String> KNOWN_KITTY_KEYBOARD_TERMS = Set.of("xterm-ghostty", "xterm-kitty");

    /**
     * Return an explicit kitty-keyboard override from {@code env}, if
     * present.
     */
    public static Boolean overrideSupportsKittyKeyboardProtocol(Map<String, String> env) {
        String raw = env == null ? null : env.get(KITTY_KEYBOARD);
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "auto".equals(normalized)) {
            return null;
        }
        if (TRUE_VALUES.contains(normalized)) {
            return Boolean.TRUE;
        }
        if (FALSE_VALUES.contains(normalized)) {
            return Boolean.FALSE;
        }
        LOG.warn("{}={} ignored; expected one of: {}, or 'auto' to defer to detection.",
                KITTY_KEYBOARD, raw, concat(TRUE_VALUES, FALSE_VALUES));
        return null;
    }

    /**
     * Return whether {@code env} identifies a terminal with built-in kitty
     * support. Configurable terminals such as iTerm2 and WezTerm are
     * intentionally not auto-detected because protocol support can be
     * disabled in user settings.
     */
    public static boolean terminalIdentitySupportsKittyKeyboardProtocol(Map<String, String> env) {
        if (env == null) {
            return false;
        }
        if (env.get("KITTY_WINDOW_ID") != null && !env.get("KITTY_WINDOW_ID").isEmpty()) {
            return true;
        }
        String term = env.getOrDefault("TERM", "");
        return KNOWN_KITTY_KEYBOARD_TERMS.contains(term);
    }

    /**
     * Return whether the attached terminal should be treated as kitty-aware.
     * Detection is side-effect free: it never writes escape sequences or
     * reads queued input bytes. That means it may under-detect some
     * configurable terminals, but it will not interfere with the TUI input
     * stream.
     */
    public static boolean supportsKittyKeyboardProtocol() {
        return supportsKittyKeyboardProtocol(System.getenv());
    }

    /** Variant that accepts an explicit env map (handy for tests). */
    public static boolean supportsKittyKeyboardProtocol(Map<String, String> env) {
        if (isWindows()) {
            LOG.debug("kitty kbd detection: false (win32 unsupported)");
            return false;
        }
        if (!stdinIsTty() || !stdoutIsTty()) {
            LOG.debug("kitty kbd detection: false (stdin/stdout not a tty)");
            return false;
        }
        Boolean override = overrideSupportsKittyKeyboardProtocol(env);
        if (override != null) {
            LOG.debug("kitty kbd detection: {} (explicit override)", override);
            return override;
        }
        boolean detected = terminalIdentitySupportsKittyKeyboardProtocol(env);
        LOG.debug("kitty kbd detection: {} (terminal identity TERM={} KITTY_WINDOW_ID={})",
                detected, env.getOrDefault("TERM", ""), env.get("KITTY_WINDOW_ID"));
        return detected;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean stdinIsTty() {
        try {
            return System.in != null && System.console() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean stdoutIsTty() {
        try {
            return System.console() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String concat(Set<String> a, Set<String> b) {
        java.util.TreeSet<String> all = new java.util.TreeSet<>();
        all.addAll(a);
        all.addAll(b);
        return String.join(", ", all);
    }
}
