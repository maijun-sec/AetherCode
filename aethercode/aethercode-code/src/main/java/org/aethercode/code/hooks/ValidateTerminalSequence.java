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

package org.aethercode.code.hooks;

import java.util.regex.Pattern;

/**
 * Terminal escape-sequence validation for hook output.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.validate_terminal_sequence} module.
 * Only OSC 0/1/2/9/99/777 (BEL or ST terminated) and bare BEL are
 * accepted; any other escape or control content rejects the entire
 * value. The regex is the direct Java equivalent of the upstream
 * <code>_ALLOWED_SEQUENCE</code> Python pattern.</p>
 */
public final class ValidateTerminalSequence {

    private static final Pattern ALLOWED_SEQUENCE = Pattern.compile(
            "(?:"
                    + "\\x1B\\](?:0|1|2|9|99|777);[^\\x00-\\x1F\\x7F-\\x9F]*(?:\\x07|\\x1B\\\\)"
                    + "|"
                    + "\\x07"
                    + ")+"
    );

    private ValidateTerminalSequence() {}

    /**
     * Return {@code value} when it is composed only of allowlisted
     * sequences; otherwise {@code null}.
     *
     * <p>Allowed tokens are OSC {@code 0}/{@code 1}/{@code 2}/{@code 9}/
     * {@code 99}/{@code 777} (BEL or ST terminated) and bare BEL. Any
     * other escape or control content rejects the entire value.</p>
     *
     * @param value candidate {@code terminalSequence} payload
     * @return the original string when valid, otherwise {@code null}
     */
    public static String validate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return ALLOWED_SEQUENCE.matcher(value).matches() ? value : null;
    }
}
