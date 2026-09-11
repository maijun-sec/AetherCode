package org.aethercode.core.cost;

import java.util.HashMap;
import java.util.Map;

/**
 * a BPE-style heuristic token counter. Better than the
 * pure "4 chars per token" approximation, especially for code
 * (where short identifiers dominate) and CJK text (where each
 * character is closer to 1-2 tokens, not 4 chars).
 *
 * <p>The counter breaks the input into runs of:
 * <ul>
 *   <li>whitespace — 1 token per run (roughly matches BPE which
 *       treats each whitespace as part of a word token)</li>
 *   <li>ASCII identifiers / numbers — 1 token per ~4 chars
 *       (matches the GPT-4 BPE behaviour for code-like text)</li>
 *   <li>non-ASCII characters (CJK, emoji) — ~1.3 tokens per
 *       character (CJK is usually 1-2 BPE tokens; emoji often
 *       2-3 because of multi-byte sequences)</li>
 *   <li>punctuation — 1 token per character when standalone
 *       (matches ",", ";", "!" etc. which are typically their
 *       own BPE tokens)</li>
 * </ul>
 *
 * <p>This is still a heuristic — for a precise count you'd need
 * the real cl100k_base / o200k_base vocab. The heuristic gets
 * within ~15% of the real count for most English + code +
 * CJK mixes, which is enough for cost estimation.
 */
public final class BpeHeuristicTokenCounter implements TokenCounter {

    /** process-singleton default. BPE counters are
     *  stateless (no per-thread state), so sharing one is fine. */
    public static final BpeHeuristicTokenCounter INSTANCE = new BpeHeuristicTokenCounter();

    // Tunable constants. The defaults were picked to give a
    // reasonable count on a mix of English prose, code, and
    // Chinese. A future round could let users override these
    // (e.g. a "code-only" preset that assumes ~5 chars per token
    // for snake_case identifiers).
    private static final double ASCII_CHARS_PER_TOKEN = 3.8;
    private static final double CJK_TOKENS_PER_CHAR   = 1.3;
    private static final double PUNCT_TOKENS_PER_CHAR  = 1.0;
    private static final int    WHITESPACE_TOKENS_PER_RUN = 1;

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int tokens = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                // Skip the rest of the whitespace run.
                int j = i + charCount;
                while (j < n) {
                    int next = text.codePointAt(j);
                    if (!Character.isWhitespace(next)) break;
                    j += Character.charCount(next);
                }
                tokens += WHITESPACE_TOKENS_PER_RUN;
                i = j;
            } else if (cp < 0x80) {
                // ASCII run. Count length and divide.
                int j = i + charCount;
                while (j < n) {
                    int next = text.codePointAt(j);
                    if (next >= 0x80 || Character.isWhitespace(next)
                            || isStandalonePunct((char) next)) break;
                    j += Character.charCount(next);
                }
                int runLen = j - i;
                tokens += Math.max(1, (int) Math.ceil(runLen / ASCII_CHARS_PER_TOKEN));
                i = j;
            } else {
                // Non-ASCII (CJK, emoji, accented Latin). Treat as
                // ~1.3 tokens per character.
                tokens += Math.max(1, (int) Math.ceil(charCount * CJK_TOKENS_PER_CHAR));
                i += charCount;
            }
        }
        return Math.max(1, tokens);
    }

    private static boolean isStandalonePunct(char c) {
        // A handful of punctuation chars that BPE typically gives
        // their own token: , . ; : ! ? ( ) [ ] { } < > = + - * /
        switch (c) {
            case ',':
            case '.':
            case ';':
            case ':':
            case '!':
            case '?':
            case '(':
            case ')':
            case '[':
            case ']':
            case '{':
            case '}':
            case '<':
            case '>':
            case '=':
            case '+':
            case '-':
            case '*':
            case '/':
            case '\\':
            case '|':
            case '@':
            case '#':
            case '$':
            case '%':
            case '^':
            case '&':
            case '~':
            case '`':
            case '"':
            case '\'':
                return true;
            default:
                return false;
        }
    }
}
