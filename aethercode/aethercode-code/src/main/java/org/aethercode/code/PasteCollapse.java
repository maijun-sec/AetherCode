package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Large paste collapsing for the chat input.
 *
 * <p>When the user pastes text exceeding a size or line threshold, the full
 * text is stored off-screen and a compact {@code [Pasted text #N +M lines]}
 * placeholder is inserted into the input box instead. At submission time the
 * placeholder is expanded back to the original content so the agent receives
 * the full text. Java-native port of the Python
 * {@code deepagents_code.paste_collapse} module.</p>
 */
public final class PasteCollapse {
    private PasteCollapse() {}

    /** Minimum character count for a paste to be collapsed into a placeholder. */
    public static final int PASTE_THRESHOLD_CHARS = 800;

    /** Minimum line count (newline-separated) for a paste to be collapsed. */
    public static final int PASTE_THRESHOLD_LINES = 2;

    /** Regex matching {@code [Pasted text #N]} or {@code [Pasted text #N +M lines]}. */
    public static final Pattern PASTE_PLACEHOLDER_PATTERN =
            Pattern.compile("\\[Pasted text #(\\d+)(?: \\+(\\d+) lines)?\\]");

    /**
     * Stored content for a collapsed paste.
     */
    public record PastedContent(String content) {}

    /** Return the number of newline characters in {@code text}. */
    public static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** Return whether {@code text} should be collapsed into a placeholder. */
    public static boolean shouldCollapsePaste(String text) {
        if (text == null) {
            return false;
        }
        return text.length() > PASTE_THRESHOLD_CHARS
                || countLines(text) > PASTE_THRESHOLD_LINES;
    }

    /**
     * Format a paste placeholder reference string.
     *
     * @param pasteId  the numeric paste identifier
     * @param numLines the number of extra lines (newlines) in the pasted content
     * @return {@code [Pasted text #N]} when {@code numLines} is 0, otherwise
     *         {@code [Pasted text #N +M lines]}
     */
    public static String formatPasteRef(int pasteId, int numLines) {
        if (numLines == 0) {
            return "[Pasted text #" + pasteId + "]";
        }
        return "[Pasted text #" + pasteId + " +" + numLines + " lines]";
    }

    /**
     * Replace all paste placeholders in {@code text} with their full content.
     * Placeholders whose IDs are not in {@code pastedContents} are left
     * unchanged.
     */
    public static String expandPasteRefs(String text, Map<Integer, PastedContent> pastedContents) {
        if (text == null || pastedContents == null || pastedContents.isEmpty()) {
            return text;
        }
        Matcher m = PASTE_PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            int id = Integer.parseInt(m.group(1));
            PastedContent content = pastedContents.get(id);
            if (content != null) {
                out.append(content.content());
            } else {
                out.append(text, m.start(), m.end());
            }
            last = m.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /**
     * Convenience builder: returns a fresh paste map and a placeholder string
     * for a freshly pasted text. The caller stores the {@link PastedContent}
     * under the returned id in the returned map.
     */
    public static StoredPaste store(Map<Integer, PastedContent> store, int pasteId, String text) {
        if (store == null) {
            store = new LinkedHashMap<>();
        }
        Map<Integer, PastedContent> out = new LinkedHashMap<>(store);
        int numLines = countLines(text);
        out.put(pasteId, new PastedContent(text));
        return new StoredPaste(out, formatPasteRef(pasteId, numLines));
    }

    /** Bundle returned by {@link #store}. */
    public record StoredPaste(Map<Integer, PastedContent> store, String placeholder) {}
}
