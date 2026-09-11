package org.aethercode.core.cost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the BPE-heuristic token counter. The counter
 * is a heuristic — these tests pin its behaviour so a future
 * tuning doesn't silently change cost estimates.
 */
class BpeHeuristicTokenCounterTest {

    private final TokenCounter c = BpeHeuristicTokenCounter.INSTANCE;

    @Test
    void emptyStringIsZero() {
        assertEquals(0, c.estimate(""));
        assertEquals(0, c.estimate(null));
    }

    @Test
    void shortAsciiWordsAreOneTokenEach() {
        // "hello world" is 11 chars; with ~3.8 chars/token that's
        // 3 tokens, but each space-separated run gets at least
        // 1 token, so "hello" (5 chars) -> 2 tokens, "world"
        // (5 chars) -> 2 tokens = 4 total. The exact count
        // depends on the heuristic; we just check it's in a
        // reasonable range.
        int n = c.estimate("hello world");
        assertTrue(n >= 3 && n <= 6,
                "expected 3-6 tokens for 'hello world', got: " + n);
    }

    @Test
    void cjkTextTakesMoreTokensPerChar() {
        // A single Chinese char is ~1.3 tokens (rounded up to 2 by
        // the ceiling); 6 chars ≈ 12 tokens. Compare to the
        // 4-chars-per-token heuristic that would give 1-2 tokens
        // for the same 6 chars.
        int n = c.estimate("今天天气真好");  // 6 Chinese chars
        assertTrue(n >= 8 && n <= 16,
                "expected 8-16 tokens for 6 CJK chars, got: " + n);
    }

    @Test
    void codeLikeTextIsReasonablyCounted() {
        // snake_case identifiers: each identifier is ~1 token,
        // a 4-char identifier is ~1-2 tokens. "foo_bar_baz" is
        // 11 chars, ~3 tokens.
        int n = c.estimate("foo_bar_baz qux_corge");
        assertTrue(n >= 4 && n <= 8,
                "expected 4-8 tokens for 2 short identifiers, got: " + n);
    }

    @Test
    void singleCharIsOneToken() {
        assertEquals(1, c.estimate("a"));
    }

    @Test
    void longAsciiPassageIsApproximatelyFourCharsPerToken() {
        // A ~440-char English paragraph should be in the
        // 90-250-token range. The 4-chars-per-token heuristic
        // gives ~110; the BPE heuristic is higher because it
        // breaks at punctuation, treating "dog." as two tokens.
        // The range is intentionally wide so the test doesn't
        // break under small heuristic tuning.
        String prose = "The quick brown fox jumps over the lazy dog. ".repeat(10);
        int n = c.estimate(prose);
        assertTrue(n >= 90 && n <= 260,
                "expected 90-260 tokens for ~440-char prose, got: " + n);
    }

    @Test
    void defaultFor_returnsBpeHeuristic() {
        // The default-for helper always returns the BPE heuristic
        // (until we ship a real vocab).
        assertTrue(TokenCounter.defaultFor("claude-sonnet-4-5") instanceof BpeHeuristicTokenCounter);
    }
}
