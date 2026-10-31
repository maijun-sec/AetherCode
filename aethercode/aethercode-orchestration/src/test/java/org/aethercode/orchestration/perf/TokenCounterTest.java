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

package org.aethercode.orchestration.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link TokenCounter}.
 */
class TokenCounterTest {

    @Test
    void zeroCounterAlwaysReturnsZero() {
        TokenCounter<String> z = TokenCounter.zero();
        assertEquals(0L, z.count("hello"));
        assertEquals(0L, z.count(""));
        assertEquals(0L, z.count(null));
    }

    @Test
    void charQuotientDividesByFour() {
        TokenCounter<String> c = TokenCounter.charQuotient();
        assertEquals(0L, c.count(""));
        assertEquals(1L, c.count("abcd"));
        assertEquals(2L, c.count("abcdefgh"));
        assertEquals(3L, c.count("abcdefghijkl"));
    }

    @Test
    void charQuotientHandlesNullSafely() {
        assertEquals(0L, TokenCounter.<String>charQuotient().count(null));
    }

    @Test
    void orElseFallsBackOnZero() {
        TokenCounter<String> primary = s -> 0L;
        TokenCounter<String> fallback = TokenCounter.charQuotient();
        TokenCounter<String> chain = TokenCounter.orElse(primary, fallback);
        assertEquals(2L, chain.count("abcdefgh"));
    }

    @Test
    void orElsePrefersPrimaryWhenNonZero() {
        TokenCounter<String> primary = s -> 42L;
        TokenCounter<String> fallback = s -> 999L;
        TokenCounter<String> chain = TokenCounter.orElse(primary, fallback);
        assertEquals(42L, chain.count("anything"));
    }

    @Test
    void orElseRejectsNullArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> TokenCounter.orElse(null, s -> 0L));
        assertThrows(IllegalArgumentException.class,
                () -> TokenCounter.orElse(s -> 0L, null));
    }

    @Test
    void ofAdaptsToLongFunction() {
        TokenCounter<String> c = TokenCounter.of(s -> s == null ? 0L : s.length());
        assertEquals(5L, c.count("hello"));
        assertEquals(0L, c.count(null));
    }

    @Test
    void ofRejectsNullFunction() {
        assertThrows(IllegalArgumentException.class, () -> TokenCounter.of(null));
    }
}
