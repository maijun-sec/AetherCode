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
