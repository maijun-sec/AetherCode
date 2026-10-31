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

import java.util.Set;

/**
 * Unicode security helpers.
 *
 * <p>Java-native port of the Python {@code deepagents_code.unicode_security}
 * module. The Java port exposes the small set of helpers the TUI uses
 * to detect potentially-confusable characters and strip invisible ones
 * from text it displays.</p>
 */
public final class UnicodeSecurity {
    private UnicodeSecurity() {}

    /** Common invisible characters (zero-width, bidi marks, etc.). */
    public static final Set<Character> INVISIBLE_CHARS = Set.of(
            '\u200B', // ZERO WIDTH SPACE
            '\u200C', // ZERO WIDTH NON-JOINER
            '\u200D', // ZERO WIDTH JOINER
            '\u2060', // WORD JOINER
            '\uFEFF', // ZERO WIDTH NO-BREAK SPACE
            '\u202E', // RIGHT-TO-LEFT OVERRIDE
            '\u202D', // LEFT-TO-RIGHT OVERRIDE
            '\u202C', // POP DIRECTIONAL FORMATTING
            '\u202A', // LEFT-TO-RIGHT EMBEDDING
            '\u202B'  // RIGHT-TO-LEFT EMBEDDING
    );

    /** Strip invisible characters from a string. */
    public static String stripInvisibles(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!INVISIBLE_CHARS.contains(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Whether a string contains any invisible character. */
    public static boolean hasInvisibles(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (INVISIBLE_CHARS.contains(text.charAt(i))) return true;
        }
        return false;
    }
}
