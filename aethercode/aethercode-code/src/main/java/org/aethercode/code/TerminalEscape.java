package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Best-effort writer for terminal escape / control sequences.
 *
 * <p>Java-native port of the Python {@code deepagents_code.terminal_escape}
 * module. Writes prefer {@code /dev/tty} so output reaches the terminal
 * even when stdout/stderr are redirected, fall back to {@code System.err},
 * and never raise — cosmetic control output must not crash the app.</p>
 *
 * <p>Set {@code DEEPAGENTS_CODE_NO_TERMINAL_ESCAPE=1} to disable all output.</p>
 */
public final class TerminalEscape {
    private TerminalEscape() {}

    private static final Logger LOG = LoggerFactory.getLogger(TerminalEscape.class);

    /** Env-var consulted for the opt-out flag. */
    public static final String NO_TERMINAL_ESCAPE = "DEEPAGENTS_CODE_NO_TERMINAL_ESCAPE";

    /** Lower clamp bound for determinate {@code OSC 9;4} progress percentages. */
    public static final int PROGRESS_MIN = 0;

    /** Upper clamp bound for determinate {@code OSC 9;4} progress percentages. */
    public static final int PROGRESS_MAX = 100;

    /** {@code OSC 9;4} progress states. */
    public enum TerminalProgressState {
        CLEAR("0"),
        NORMAL("1"),
        ERROR("2"),
        INDETERMINATE("3"),
        WARNING("4");

        private final String code;
        TerminalProgressState(String code) { this.code = code; }
        public String code() { return code; }
    }

    private static final AtomicBoolean PROGRESS_ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean BACKGROUND_ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean ATEXIT_REGISTERED = new AtomicBoolean();

    /** Return whether terminal-escape output is opt-out disabled. */
    public static boolean isDisabled() {
        String v = System.getenv(NO_TERMINAL_ESCAPE);
        if (v == null) v = System.getProperty(NO_TERMINAL_ESCAPE);
        if (v == null) return false;
        return v.trim().equalsIgnoreCase("1")
                || v.trim().equalsIgnoreCase("true")
                || v.trim().equalsIgnoreCase("yes")
                || v.trim().equalsIgnoreCase("on");
    }

    /**
     * Best-effort write of a terminal control sequence. Returns {@code true}
     * when the sequence was written and flushed.
     */
    public static boolean writeTerminalEscape(String sequence) {
        if (isDisabled() || sequence == null || sequence.isEmpty()) {
            return false;
        }
        // Prefer /dev/tty on POSIX so the sequence reaches the terminal even
        // when stdout/stderr are redirected.
        if (!isWindows()) {
            try (OutputStream tty = Files.newOutputStream(Path.of("/dev/tty"))) {
                tty.write(sequence.getBytes(StandardCharsets.UTF_8));
                tty.flush();
                return true;
            } catch (IOException | RuntimeException e) {
                LOG.debug("terminal_escape /dev/tty write failed: {}", e.toString());
            }
        }
        // Fall back to System.err when it is a TTY.
        if (System.console() != null) {
            try {
                System.err.write(sequence.getBytes(StandardCharsets.UTF_8));
                System.err.flush();
                return true;
            } catch (IOException e) {
                LOG.debug("terminal_escape stderr write failed: {}", e.toString());
            }
        }
        return false;
    }

    /** Write an {@code OSC <command>;<payload>} sequence. */
    public static boolean writeOsc(String command, String payload, boolean st) {
        String body = (payload == null || payload.isEmpty()) ? command : command + ";" + payload;
        String terminator = st ? "\u001b\\" : "\u0007";
        return writeTerminalEscape("\u001b]" + body + terminator);
    }

    /** Convenience overload using the BEL terminator. */
    public static boolean writeOsc(String command, String payload) {
        return writeOsc(command, payload, false);
    }

    /** Clamp {@code progress} for the given state. */
    public static int validateProgress(Integer progress, TerminalProgressState state) {
        if (state == TerminalProgressState.CLEAR || state == TerminalProgressState.INDETERMINATE) {
            if (progress != null && progress != 0) {
                LOG.debug("terminal_progress: ignoring progress={} for state={}", progress, state);
            }
            return 0;
        }
        if (progress == null) return 0;
        int coerced;
        try {
            coerced = (int) progress.longValue();
        } catch (NumberFormatException e) {
            LOG.debug("terminal_progress: non-numeric progress={} ignored ({})", progress, e.toString());
            return 0;
        }
        return Math.max(PROGRESS_MIN, Math.min(PROGRESS_MAX, coerced));
    }

    /** Set the terminal's {@code OSC 9;4} progress indicator. */
    public static boolean setTerminalProgress(Integer progress, TerminalProgressState state) {
        int pct = validateProgress(progress, state);
        String payload = state.code() + ";" + pct;
        boolean wrote = writeOsc("9;4", payload);
        PROGRESS_ACTIVE.set(wrote);
        return wrote;
    }

    /** Clear the {@code OSC 9;4} progress indicator. */
    public static boolean clearTerminalProgress() {
        boolean wrote = writeOsc("9;4", "0;0");
        PROGRESS_ACTIVE.set(false);
        return wrote;
    }

    /** Mark the terminal as backgrounded. */
    public static boolean setTerminalBackground(boolean background) {
        BACKGROUND_ACTIVE.set(background);
        return writeOsc("9;4", background ? "1;0" : "0;0");
    }

    static {
        // Best-effort cleanup on shutdown. The cosmetic nature of the writes
        // means we ignore failures.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (PROGRESS_ACTIVE.get()) {
                writeOsc("9;4", "0;0");
            }
            if (BACKGROUND_ACTIVE.get()) {
                writeOsc("9;4", "0;0");
            }
        }, "terminal-escape-cleanup"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
