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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link RetryHelper} — the retry runner
 * used by PlanExecutor's step retry.
 */
class RetryHelperTest {

    /** test sleeper that records every sleep call without
     *  actually waiting. */
    static class FakeSleeper implements RetryHelper.Sleeper {
        long totalMs = 0L;
        int calls = 0;
        @Override
        public void sleep(long millis) {
            totalMs += millis;
            calls++;
        }
    }

    @Test
    void run_succeedsOnFirstTry() {
        FakeSleeper sleeper = new FakeSleeper();
        RetryHelper.Result<String> r = RetryHelper.run(() -> "ok", RetryPolicy.DEFAULT, sleeper);
        assertTrue(r.isSuccess());
        assertEquals("ok", r.value());
        assertEquals(1, r.attempts());
        assertEquals(0, sleeper.calls, "no sleep on first-try success");
    }

    @Test
    void run_retriesUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        FakeSleeper sleeper = new FakeSleeper();
        RetryHelper.Result<String> r = RetryHelper.run(() -> {
            int n = calls.incrementAndGet();
            if (n < 3) throw new RuntimeException("transient " + n);
            return "ok-on-3";
        }, RetryPolicy.DEFAULT, sleeper);
        assertTrue(r.isSuccess());
        assertEquals("ok-on-3", r.value());
        assertEquals(3, r.attempts());
        // 2 sleeps (between attempt 1 and 2, and between 2 and 3)
        assertEquals(2, sleeper.calls);
    }

    @Test
    void run_givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        FakeSleeper sleeper = new FakeSleeper();
        RetryHelper.Result<String> r = RetryHelper.run(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("always-fails");
        }, RetryPolicy.DEFAULT, sleeper);
        assertTrue(r.isFailure());
        assertEquals(3, r.attempts());
        assertEquals(3, calls.get());
        assertNotNull(r.error());
        // 2 sleeps (after attempt 1 and 2 — none after the last)
        assertEquals(2, sleeper.calls);
    }

    @Test
    void run_NONE_neverRetries() {
        AtomicInteger calls = new AtomicInteger();
        FakeSleeper sleeper = new FakeSleeper();
        RetryHelper.Result<String> r = RetryHelper.run(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }, RetryPolicy.NONE, sleeper);
        assertTrue(r.isFailure());
        assertEquals(1, calls.get());
        assertEquals(0, sleeper.calls);
    }

    @Test
    void run_appliesBackoffBetweenAttempts() {
        FakeSleeper sleeper = new FakeSleeper();
        RetryPolicy p = new RetryPolicy(4, 100L, 1_000L, 2.0, 0.0);
        RetryHelper.run(() -> { throw new RuntimeException("x"); }, p, sleeper);
        // 3 backoffs: 100 + 200 + 400 = 700
        assertEquals(3, sleeper.calls);
        assertEquals(700L, sleeper.totalMs);
    }

    @Test
    void run_interruptedDuringSleep_returnsInterrupted() throws Exception {
        // Task that fails on first attempt, then the sleep is
        // interrupted. Result should be a failure with the
        // InterruptedException as the error.
        RetryHelper.Sleeper interrupting = millis -> { throw new InterruptedException("test"); };
        AtomicInteger calls = new AtomicInteger();
        RetryHelper.Result<String> r = RetryHelper.run(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("first");
        }, RetryPolicy.DEFAULT, interrupting);
        assertTrue(r.isFailure());
        assertTrue(r.error() instanceof InterruptedException);
        assertEquals(1, r.attempts());
    }

    @Test
    void run_nullTask_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryHelper.run(null, RetryPolicy.DEFAULT));
    }

    @Test
    void run_nullPolicy_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryHelper.run(() -> "ok", null));
    }

    @Test
    void run_recordsCorrectAttempts() {
        AtomicInteger calls = new AtomicInteger();
        FakeSleeper sleeper = new FakeSleeper();
        RetryHelper.Result<Integer> r = RetryHelper.run(() -> {
            int n = calls.incrementAndGet();
            if (n == 2) return n;
            throw new RuntimeException("retry");
        }, RetryPolicy.DEFAULT, sleeper);
        assertTrue(r.isSuccess());
        assertEquals(2, r.attempts());
        assertEquals(2, r.value());
    }
}
