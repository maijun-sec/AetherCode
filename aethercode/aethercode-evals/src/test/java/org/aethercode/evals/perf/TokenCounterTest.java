package org.aethercode.evals.perf;

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
