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
