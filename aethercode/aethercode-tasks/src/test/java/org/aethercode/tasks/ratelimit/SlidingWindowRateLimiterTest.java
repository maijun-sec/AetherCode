package org.aethercode.tasks.ratelimit;

import org.aethercode.tasks.ratelimit.SlidingWindowRateLimiter.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-P1-T22: per-connection sliding-window rate limiter. The
 * default policy is 100 calls / second, 100 buckets of 10 ms
 * each. The tests inject a deterministic "now" so the bucket
 * arithmetic is reproducible.
 */
class SlidingWindowRateLimiterTest {

    private static final long BUCKET_NS = 10L * 1_000_000L;   // 10 ms

    @Test
    void allowsUpToTheCapAndDeniesBeyond() {
        SlidingWindowRateLimiter rl = new SlidingWindowRateLimiter(10, 1000L, 100);
        // Pin "now" to the start of one sub-window so the
        // first 10 requests all land in bucket 0.
        long t0 = 0L;
        for (int i = 0; i < 10; i++) {
            Decision d = rl.tryAcquire(t0 + i);
            assertTrue(d.allowed(), "call " + i + " should be allowed");
        }
        Decision over = rl.tryAcquire(t0 + 10);
        assertFalse(over.allowed(), "11th call must be denied");
        assertEquals(10L, over.currentCount());
        assertEquals(0L, over.remaining());
    }

    @Test
    void slidingWindow_releasesSlotsAsBucketsExpire() {
        // 5-call cap, 5 buckets of 20 ms each (= 100 ms window).
        // Pin all 5 calls into the first bucket (t0..t0+10ns).
        // Then jump the clock past the full window and the
        // bucket should be trimmed, freeing the 5 slots.
        SlidingWindowRateLimiter rl = new SlidingWindowRateLimiter(5, 100L, 5);
        long bucket0 = 0L;
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.tryAcquire(bucket0).allowed());
        }
        // 6th call in the same bucket is denied.
        assertFalse(rl.tryAcquire(bucket0).allowed());
        // Jump 100 ms ahead — every bucket has aged out.
        long after = bucket0 + 100L * 1_000_000L;
        Decision d = rl.tryAcquire(after);
        assertTrue(d.allowed(), "after the window passes, the limit is released");
        assertEquals(1L, d.currentCount());
    }

    @Test
    void invalidConstructorArgsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowRateLimiter(0, 1000L, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowRateLimiter(10, 5L, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowRateLimiter(10, 1000L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowRateLimiter(10, 1001L, 100));
    }
}
