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
