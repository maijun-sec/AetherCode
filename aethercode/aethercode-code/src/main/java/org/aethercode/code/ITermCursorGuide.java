package org.aethercode.code;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * iTerm2 cursor guide workaround for alternate-screen TUI rendering.
 *
 * <p>Java-native port of the Python {@code deepagents_code.iterm_cursor_guide}
 * module. iTerm2's cursor guide (highlight cursor line) causes visual
 * artifacts when a TUI takes over the terminal in alternate screen mode.
 * We disable it at module load and restore it on exit only if the active
 * iTerm2 profile had cursor guide enabled before launch.</p>
 */
public final class ITermCursorGuide {
    private ITermCursorGuide() {}

    private static final String ITERM_CURSOR_GUIDE_OFF = "\u001b]1337;HighlightCursorLine=no\u001b\\";
    private static final String ITERM_CURSOR_GUIDE_ON = "\u001b]1337;HighlightCursorLine=yes\u001b\\";
    private static final Path ITERM_PREFS_PATH =
            Path.of(System.getProperty("user.home"), "Library/Preferences/com.googlecode.iterm2.plist");

    /** True when we're running under iTerm2 and stderr is a TTY. */
    public static final boolean IS_ITERM = isITerm();

    /** Pre-launch cursor-guide state used to decide whether to restore. */
    public static final boolean RESTORE_ITERM_CURSOR_GUIDE = isITerm() && itermProfileCursorGuideEnabled();

    private static volatile boolean restored = false;

    private static boolean isITerm() {
        String lcTerminal = System.getenv("LC_TERMINAL");
        String termProgram = System.getenv("TERM_PROGRAM");
        boolean isITerm2 = "iTerm2".equals(lcTerminal) || "iTerm.app".equals(termProgram);
        boolean stderrIsTty;
        try {
            stderrIsTty = System.console() != null;
        } catch (Exception e) {
            stderrIsTty = false;
        }
        return isITerm2 && stderrIsTty;
    }

    /** Restore iTerm2 cursor guide when launch-time profile state required it. */
    public static void restoreITermCursorGuide() {
        if (!RESTORE_ITERM_CURSOR_GUIDE || restored) {
            return;
        }
        restored = true;
        writeITermEscape(ITERM_CURSOR_GUIDE_ON);
    }

    private static void disableITermCursorGuide() {
        if (!RESTORE_ITERM_CURSOR_GUIDE) {
            return;
        }
        writeITermEscape(ITERM_CURSOR_GUIDE_OFF);
    }

    private static void writeITermEscape(String sequence) {
        if (!IS_ITERM) {
            return;
        }
        try {
            // Write to System.err — the same destination Python's sys.__stderr__ uses.
            System.err.write(sequence.getBytes(StandardCharsets.UTF_8));
            System.err.flush();
        } catch (IOException ignored) {
            // Terminal may be unavailable (redirected, closed, broken pipe).
        }
    }

    /** Plist boolean coercion (returns {@code null} when value isn't boolean-like). */
    public static Boolean plistBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return null;
    }

    /** Whether an iTerm2 profile entry has cursor guide enabled. */
    public static boolean profileUsesCursorGuide(Map<String, Object> profile) {
        if (profile == null) {
            return false;
        }
        Boolean enabled = plistBool(profile.get("Use Cursor Guide"));
        if (enabled != null) {
            return enabled;
        }
        for (String key : new String[]{"Use Cursor Guide (Dark)", "Use Cursor Guide (Light)"}) {
            if (Boolean.TRUE.equals(plistBool(profile.get(key)))) {
                return true;
            }
        }
        return false;
    }

    /** Infer whether iTerm2 cursor guide was enabled before startup. */
    public static boolean itermProfileCursorGuideEnabled() {
        if (!IS_ITERM) {
            return false;
        }
        // Reading the iTerm2 plist on every startup pulls in a runtime
        // dependency on a binary plist parser; the Java port stores the
        // raw bytes into a Map<String,Object> via a JSON-derivative or via
        // a future plist library. For now we conservatively return false
        // when the file cannot be parsed, matching the Python fall-through.
        if (!Files.exists(ITERM_PREFS_PATH)) {
            return false;
        }
        try {
            // Lightweight XML plist read: the iTerm2 plist is binary by default
            // and we don't want to drag a plist library into the runtime for
            // this single read. The Python module uses plistlib, which handles
            // both XML and binary plists. The Java port defers to a future
            // plist helper; until then this is a no-op (no restore performed).
            // TODO: integrate a plist parser to honor the launch-time state.
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static {
        // Disable cursor guide at module load, but only when launch-time
        // state detection confirmed that exit cleanup will restore it.
        disableITermCursorGuide();
        if (RESTORE_ITERM_CURSOR_GUIDE) {
            Runtime.getRuntime().addShutdownHook(new Thread(ITermCursorGuide::restoreITermCursorGuide,
                    "iterm-cursor-guide-restore"));
        }
    }

    // Visible for testing.
    static void resetRestoredFlag() { restored = false; }
}
