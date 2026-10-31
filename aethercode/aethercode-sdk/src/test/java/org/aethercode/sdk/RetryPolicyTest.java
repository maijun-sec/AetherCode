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

package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link RetryPolicy} — the retry/backoff
 * configuration used by PlanExecutor's step retry.
 */
class RetryPolicyTest {

    @Test
    void construct_rejectsInvalidMaxAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, 100, 1000, 2.0, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(-1, 100, 1000, 2.0, 0.1));
    }

    @Test
    void construct_rejectsNegativeBaseBackoff() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, -1, 1000, 2.0, 0.1));
    }

    @Test
    void construct_rejectsMaxLessThanBase() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 1000, 500, 2.0, 0.1));
    }

    @Test
    void construct_rejectsMultiplierLessThanOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 100, 1000, 0.5, 0.1));
    }

    @Test
    void construct_rejectsJitterOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 100, 1000, 2.0, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 100, 1000, 2.0, 1.1));
    }

    @Test
    void backoffFor_firstAttempt_isZero() {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertEquals(0L, p.backoffFor(1));
    }

    @Test
    void backoffFor_growsExponentially() {
        // base=100, multiplier=2.0, no jitter.
        RetryPolicy p = new RetryPolicy(5, 100L, 10_000L, 2.0, 0.0);
        // attempt 1: 0 (no backoff before first try)
        // attempt 2: 100 (base * 2^0)
        // attempt 3: 200 (base * 2^1)
        // attempt 4: 400 (base * 2^2)
        // attempt 5: 800 (base * 2^3)
        assertEquals(0L, p.backoffFor(1));
        assertEquals(100L, p.backoffFor(2));
        assertEquals(200L, p.backoffFor(3));
        assertEquals(400L, p.backoffFor(4));
        assertEquals(800L, p.backoffFor(5));
    }

    @Test
    void backoffFor_capsAtMax() {
        RetryPolicy p = new RetryPolicy(20, 100L, 500L, 2.0, 0.0);
        // After enough doublings, the cap kicks in.
        assertEquals(500L, p.backoffFor(10));
        assertEquals(500L, p.backoffFor(20));
    }

    @Test
    void backoffFor_jitterIsBounded() {
        // With jitter, the result can vary. Run many trials to
        // confirm it stays within [base*(1-jitter), base*(1+jitter)].
        RetryPolicy p = new RetryPolicy(3, 1000L, 10_000L, 2.0, 0.1);
        for (int i = 0; i < 50; i++) {
            long b = p.backoffFor(2);
            // attempt 2: 1000ms ± 10% = [900, 1100]
            assertTrue(b >= 900 && b <= 1100, "backoff out of jitter range: " + b);
        }
    }

    @Test
    void shouldRetry_returnsFalseAtMaxAttempts() {
        RetryPolicy p = new RetryPolicy(3, 100, 1000, 2.0, 0.1);
        assertTrue(p.shouldRetry(1));
        assertTrue(p.shouldRetry(2));
        assertFalse(p.shouldRetry(3)); // 3 == max
        assertFalse(p.shouldRetry(4));
    }

    @Test
    void shouldRetry_maxAttemptsOne_neverRetries() {
        RetryPolicy p = RetryPolicy.NONE;
        assertFalse(p.shouldRetry(1));
    }

    @Test
    void DEFAULT_isSensible() {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertEquals(3, p.maxAttempts());
        assertEquals(1_000L, p.baseBackoffMs());
        assertEquals(10_000L, p.maxBackoffMs());
        assertTrue(p.multiplier() >= 1.0);
    }

    @Test
    void AGGRESSIVE_isMoreRetries() {
        assertTrue(RetryPolicy.AGGRESSIVE.maxAttempts() > RetryPolicy.DEFAULT.maxAttempts());
    }

    @Test
    void NONE_oneAttemptNoBackoff() {
        assertEquals(1, RetryPolicy.NONE.maxAttempts());
        assertEquals(0L, RetryPolicy.NONE.baseBackoffMs());
        assertEquals(0L, RetryPolicy.NONE.backoffFor(2));
    }
}
