package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * Clipboard utilities.
 *
 * <p>Java-native port of the Python {@code deepagents_code.clipboard} module.
 * The Java port provides a small interface ({@link ClipboardBackend}) so the
 * TUI app can plug in its own implementation, and a default OSC 52 backend
 * that works over SSH/tmux.</p>
 */
public final class Clipboard {
    private Clipboard() {}

    private static final Logger LOG = LoggerFactory.getLogger(Clipboard.class);

    private static final int PREVIEW_MAX_LENGTH = 40;

    /** Functional interface for a single clipboard write backend. */
    @FunctionalInterface
    public interface ClipboardBackend {
        /**
         * Try to copy {@code text} to the clipboard.
         * @throws Exception on failure; callers fall through to the next backend
         */
        void copy(String text) throws Exception;
    }

    /**
     * OSC 52 clipboard backend. Writes an OSC 52 escape to {@code /dev/tty}
     * (or stderr on non-POSIX systems) so SSH/tmux sessions can capture it.
     */
    public static final ClipboardBackend OSC52_BACKEND = text -> {
        String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        String seq = "\033]52;c;" + encoded + "\u0007";
        if (System.getenv("TMUX") != null) {
            seq = "\033Ptmux;\033" + seq + "\033\\";
        }
        try {
            // Prefer /dev/tty on POSIX; fall back to stderr otherwise.
            if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                System.err.write(seq.getBytes(StandardCharsets.UTF_8));
                System.err.flush();
            } else {
                try (OutputStream tty = Files.newOutputStream(Path.of("/dev/tty"))) {
                    tty.write(seq.getBytes(StandardCharsets.UTF_8));
                    tty.flush();
                }
            }
        } catch (IOException io) {
            throw io;
        }
    };

    /**
     * Try to copy {@code text} to the clipboard via the given backends in
     * order. Returns {@code (success, lastError)}.
     */
    public static Result copyTextToClipboard(List<ClipboardBackend> backends, String text) {
        Throwable last = null;
        for (ClipboardBackend backend : backends) {
            try {
                backend.copy(text);
                return new Result(true, null);
            } catch (Throwable t) {
                last = t;
                LOG.debug("Clipboard backend failed: {}", t.toString(), t);
            }
        }
        String msg = last == null ? null : (last.getMessage() != null ? last.getMessage() : last.getClass().getName());
        return new Result(false, msg);
    }

    /**
     * Convenience: copy with the default backend set (OSC 52 only). Callers
     * that need pyperclip-equivalent functionality must add their backend
     * to the front of the list.
     */
    public static Result copyTextDefault(String text) {
        List<ClipboardBackend> backends = new ArrayList<>();
        backends.add(OSC52_BACKEND);
        return copyTextToClipboard(backends, text);
    }

    /**
     * Format a short preview for notifications, using the supplied
     * newline/ellipsis glyphs.
     */
    public static String shortenPreview(List<String> texts, String newline, String ellipsis) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) sb.append(newline);
            sb.append(texts.get(i).replace("\n", newline));
        }
        String dense = sb.toString();
        if (dense.length() > PREVIEW_MAX_LENGTH) {
            return dense.substring(0, PREVIEW_MAX_LENGTH - 1) + ellipsis;
        }
        return dense;
    }

    /**
     * Hook for a TUI-side notify function. The TUI app passes its
     * {@code notify(String, severity, timeout)} function; the CLI uses
     * stdout (null).
     */
    public static boolean copyTextWithFeedback(Consumer<String> infoNotify,
                                               Consumer<String> warnNotify,
                                               String text,
                                               String failureNoun,
                                               String successMessage) {
        Result r = copyTextDefault(text);
        if (r.success()) {
            if (successMessage != null && infoNotify != null) {
                infoNotify.accept(successMessage);
            }
            return true;
        }
        if (warnNotify != null) {
            String msg = r.error() != null
                    ? "Failed to copy " + failureNoun + ": " + r.error()
                    : "Failed to copy " + failureNoun + " - no clipboard method available";
            warnNotify.accept(msg);
        }
        return false;
    }

    /** Result of a clipboard attempt. */
    public record Result(boolean success, String error) {}
}
