package org.aethercode.code;

import java.util.Map;
import java.util.HashMap;

/**
 * Escaping for external text that reaches Rich/Textual markdown source.
 *
 * <p>Java-native port of the Python {@code deepagents_code._markdown} module.
 * Covers the inline constructs Rich's markdown parser acts on (emphasis,
 * code spans, links, autolinks/HTML, HTML entities, strikethrough) plus the
 * {@code |} table-cell separator.</p>
 */
public final class Markdown {
    private Markdown() {}

    /** Characters escaped by {@link #escape(String)}. */
    private static final String ESCAPE_CHARS = "\\&`*_[]<>|~";

    /** Pre-built escape map backing {@link #escape(String)}. */
    public static final Map<Character, String> ESCAPES = buildEscapes();

    private static Map<Character, String> buildEscapes() {
        Map<Character, String> map = new HashMap<>();
        for (int i = 0; i < ESCAPE_CHARS.length(); i++) {
            char c = ESCAPE_CHARS.charAt(i);
            map.put(c, "\\" + c);
        }
        return Map.copyOf(map);
    }

    /**
     * Normalize line breaks and escape markdown syntax in external text.
     *
     * @param text display string that may contain markdown punctuation
     * @return {@code text} on one line with markdown-significant characters
     *         escaped
     */
    public static String escape(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text
                .replace("\r\n", " ")
                .replace("\r", " ")
                .replace("\n", " ");
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            String esc = ESCAPES.get(c);
            if (esc != null) {
                out.append(esc);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
