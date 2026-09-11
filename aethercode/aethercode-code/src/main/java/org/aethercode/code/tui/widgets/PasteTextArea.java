package org.aethercode.code.tui.widgets;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Shared paste handling for text-area inputs.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets._paste_textarea}.
 * The Python module exposes two text-area subclasses:
 * {@code PasteBurstTextArea} (paste-burst detection and Enter
 * suppression) and {@code CollapsingPasteTextArea} (large-paste
 * collapse into a {@code [Pasted text #N]} placeholder).</p>
 *
 * <p>The Java port preserves the public constants and exposes a
 * {@link BurstDetector} that hosts wire into their own text-area
 * implementation. The text-area base class is left to the host TUI.</p>
 */
public final class PasteTextArea {

    private PasteTextArea() {}

    /** Maximum time between chars to treat input as a paste-like burst. */
    public static final double PASTE_BURST_CHAR_GAP_SECONDS = 0.03;

    /** Idle timeout before flushing buffered burst text. */
    public static final double PASTE_BURST_FLUSH_DELAY_SECONDS = 0.08;

    /** Consecutive fast keystrokes before a stream is treated as a paste burst. */
    public static final int PASTE_BURST_MIN_CHARS = 3;

    /** Rapid-run length that on its own confirms a key-event paste. */
    public static final int PASTE_BURST_PROMOTE_CHARS = 5;

    /** Window after recent burst activity during which {@code enter} inserts a newline. */
    public static final double PASTE_ENTER_SUPPRESS_WINDOW_SECONDS = 0.12;

    /** A buffered burst character. */
    public record BurstChar(char ch, double at) {}

    /** A recorded paste in the document. */
    public record PastedContent(int index, String text) {
        public String display() { return "[Pasted text #" + index + "]"; }
    }

    /** State machine for paste-burst detection. */
    public static final class BurstDetector {
        private final Deque<BurstChar> buffer = new ArrayDeque<>();
        private int run = 0;
        private long lastCharMs = 0L;
        private boolean enterSuppressed;

        public boolean isEnterSuppressed() { return enterSuppressed; }
        public Deque<BurstChar> buffer() { return buffer; }

        /** Record a key event and return whether it was absorbed into the burst. */
        public boolean absorbKey(char ch, long nowMs) {
            long gapMs = lastCharMs == 0 ? 0 : nowMs - lastCharMs;
            boolean fast = gapMs > 0
                    && (gapMs / 1000.0) < PASTE_BURST_CHAR_GAP_SECONDS;
            if (fast) {
                run++;
                buffer.offerLast(new BurstChar(ch, nowMs / 1000.0));
            } else {
                run = 1;
                buffer.clear();
                buffer.offerLast(new BurstChar(ch, nowMs / 1000.0));
            }
            lastCharMs = nowMs;
            enterSuppressed = run >= PASTE_BURST_MIN_CHARS;
            return false;  // Never absorb single chars; promotion happens via flush.
        }

        /** Whether the burst qualifies for promotion to a hidden buffer. */
        public boolean shouldPromote() {
            return buffer.size() >= PASTE_BURST_PROMOTE_CHARS;
        }

        /** Reset the detector after a flush. */
        public void reset() {
            buffer.clear();
            run = 0;
            lastCharMs = 0L;
            enterSuppressed = false;
        }
    }

    /** Whether collapsing pastes is enabled. */
    public static boolean collapsingEnabled() { return true; }

    /**
     * Format a paste reference as a {@code [Pasted text #N]} placeholder.
     */
    public static String formatPasteRef(PastedContent content) {
        if (content == null) return "";
        return content.display();
    }

    /** Expand a list of paste references into their full text. */
    public static String expandPasteRefs(List<PastedContent> contents, int index) {
        if (contents == null) return "";
        if (index < 0 || index >= contents.size()) return "";
        return contents.get(index).text();
    }
}
