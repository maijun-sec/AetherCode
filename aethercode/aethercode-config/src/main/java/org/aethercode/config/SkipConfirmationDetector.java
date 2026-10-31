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

package org.aethercode.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * detect "no confirmation needed for next N rounds" patterns in
 * the user's prompt text. Returns the number of rounds the user
 * wants the engine to skip, or 0 if no pattern matches.
 *
 * <p>Supported phrasings (case-insensitive, white-space flexible):
 * <ul>
 *   <li>{@code "no confirmation needed for next 5 rounds"}</li>
 *   <li>{@code "no confirm for next 3 rounds"}</li>
 *   <li>{@code "skip confirmation for next 10 round"}</li>
 *   <li>{@code "no need to confirm for next 2 rounds"}</li>
 *   <li>{@code "auto-allow next 4 rounds"}</li>
 *   <li>{@code "no confirmation for the next round"} (singular, N=1)</li>
 * </ul>
 *
 * <p>Returns 0 (no match) when the prompt is null, blank, or has no
 * recognisable pattern. Callers should pipe the result into
 * {@link SkipConfirmationRegistry#set(String, int)}.
 */
public final class SkipConfirmationDetector {

    private SkipConfirmationDetector() {}

    // The number group covers "5", "12", etc. The trailing "round(s)?"
    // is required so we don't false-positive on "next 5 lines" or similar.
    private static final Pattern[] PATTERNS = new Pattern[] {
            Pattern.compile(
                    "(?:no|without)\\s+(?:more\\s+)?confirmation\\s+needed\\s+for\\s+(?:the\\s+)?next\\s+(\\d+)\\s+rounds?",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "(?:no|skip)\\s+confirm(?:ation)?\\s+for\\s+(?:the\\s+)?next\\s+(\\d+)\\s+rounds?",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "no\\s+need\\s+to\\s+confirm\\s+for\\s+(?:the\\s+)?next\\s+(\\d+)\\s+rounds?",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "auto[- ]allow\\s+(?:for\\s+)?(?:the\\s+)?next\\s+(\\d+)\\s+rounds?",
                    Pattern.CASE_INSENSITIVE),
            // "skip the next 5 prompts" — narrower phrasing
            Pattern.compile(
                    "skip\\s+(?:the\\s+)?next\\s+(\\d+)\\s+(?:permission\\s+)?prompts?",
                    Pattern.CASE_INSENSITIVE),
            // Singular "round" with no number -> defaults to 1.
            // Matches "no confirmation for the next round" or
            // "no need to ask for the next round" etc.
            Pattern.compile(
                    "(?:no|skip)\\s+(?:more\\s+)?(?:confirm(?:ation)?|ask|approval|prompt)\\s+for\\s+(?:the\\s+)?next\\s+round\\b",
                    Pattern.CASE_INSENSITIVE),
    };

    /**
     * Return the number of skip-rounds the prompt asks for, or 0 if
     * no recognisable pattern. Caps at {@link Integer#MAX_VALUE} but
     * typical values are 1-20. The last pattern (singular "next
     * round" with no number) returns 1.
     */
    public static int detect(String prompt) {
        if (prompt == null || prompt.isBlank()) return 0;
        int idx = 0;
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(prompt);
            if (m.find()) {
                // The last pattern has no capture group; assume 1.
                if (idx == PATTERNS.length - 1) return 1;
                try {
                    int n = Integer.parseInt(m.group(1));
                    return Math.max(0, n);
                } catch (NumberFormatException | IndexOutOfBoundsException ignore) {
                    return 0;
                }
            }
            idx++;
        }
        return 0;
    }
}
