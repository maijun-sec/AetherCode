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

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * classic token-bucket rate limiter. Modelled on the TS
 * {@code cost-tracker.ts#throttle}. Caps the cumulative output tokens per
 * minute so a runaway prompt can't drain the whole monthly budget in one
 * second.
 *
 * <p>Defaults: 200,000 tokens capacity, refilled at 200,000 tokens per
 * 60 seconds. The bucket starts full.
 */
public class TokenBucketRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    public static final long DEFAULT_CAPACITY = 200_000L;
    public static final long DEFAULT_REFILL_PER_MIN = 200_000L;

    private final long capacity;
    private final long refillPerMs;
    private final AtomicLong tokens;
    private final AtomicLong lastRefill;

    public TokenBucketRateLimiter() { this(DEFAULT_CAPACITY, DEFAULT_REFILL_PER_MIN); }

    /** capacity + refill-per-minute. */
    public TokenBucketRateLimiter(long capacity, long refillPerMin) {
        this.capacity = Math.max(1, capacity);
        long perMin = Math.max(1, refillPerMin);
        this.refillPerMs = Math.max(1L, perMin / 60_000L);
        this.tokens = new AtomicLong(capacity);
        this.lastRefill = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * Try to consume {@code requested} tokens. Returns true when the bucket
     * has enough headroom, false when it doesn't. A successful call deducts
     * the tokens; a failed call is a no-op (caller can {@code tryAcquire} again
     * after waiting).
     */
    public boolean tryAcquire(long requested) {
        if (requested <= 0) return true;
        refill();
        while (true) {
            long cur = tokens.get();
            if (cur < requested) return false;
            if (tokens.compareAndSet(cur, cur - requested)) return true;
        }
    }

    /**
     * Block until {@code requested} tokens are available, or the wait
     * exceeds {@code maxWaitMs}. Returns true when the request was served.
     */
    public boolean acquireBlocking(long requested, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        long backoff = 25L;
        while (System.currentTimeMillis() < deadline) {
            if (tryAcquire(requested)) return true;
            try { Thread.sleep(backoff); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            backoff = Math.min(backoff * 2, 500L);
        }
        return false;
    }

    /** snapshot of the current token count. */
    public long available() { refill(); return tokens.get(); }
    public long capacity() { return capacity; }
    public long refillPerMin() { return refillPerMs * 60_000L; }

    /** refill the bucket to its current time-based maximum. */
    private void refill() {
        long now = System.currentTimeMillis();
        long prev = lastRefill.get();
        long elapsed = now - prev;
        if (elapsed <= 0) return;
        if (!lastRefill.compareAndSet(prev, now)) return;
        long add = elapsed * refillPerMs;
        if (add <= 0) return;
        long updated = Math.min(capacity, tokens.get() + add);
        tokens.set(updated);
    }

    /** explicit reset — useful for tests and for "new session" boundaries. */
    public void reset() {
        tokens.set(capacity);
        lastRefill.set(System.currentTimeMillis());
        LOG.debug("token bucket reset to capacity={}", capacity);
    }
}
