package org.aethercode.tasks.ratelimit;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * R-P1-T22 (app-spec/tasks.md §1.2): a per-connection sliding-
 * window rate limiter. The default policy allows at most
 * {@value #DEFAULT_MAX_REQUESTS} RPC calls per
 * {@value #DEFAULT_WINDOW_MS} milliseconds (the "100 calls/sec"
 * cap in the spec). Calls beyond the cap are denied with
 * {@link Decision#DENY}; the caller is expected to return
 * a {@code -32603 / rate_limited} JSON-RPC error.
 *
 * <p>Algorithm: the {@value #DEFAULT_BUCKET_COUNT}-bucket
 * window is a circular array where each bucket holds the
 * count of requests in its {@code windowMs / bucketCount}
 * sub-window. On every {@link #tryAcquire()} we:
 * <ol>
 *   <li>compute the current bucket index from
 *       {@code System.nanoTime() / bucketSizeNs};</li>
 *   <li>zero out any bucket whose sub-window has fully passed
 *       (older than {@code bucketCount} slots back);</li>
 *   <li>sum the live buckets, add 1 for this request, and
 *       compare against the cap.</li>
 * </ol>
 *
 * <p>Thread-safety: the bucket array is an
 * {@link AtomicLongArray}; each bucket is mutated via
 * {@link AtomicLongArray#compareAndSet} so concurrent requests
 * don't lose updates. A connection's limiter is single-owner
 * so contention is bounded by the number of concurrent
 * requests on one socket.
 */
public final class SlidingWindowRateLimiter {

    /** Default cap (100 RPC calls / second). */
    public static final int DEFAULT_MAX_REQUESTS = 100;
    /** Default sliding window length in ms. */
    public static final long DEFAULT_WINDOW_MS = 1_000L;
    /** Default bucket count (10 ms each). */
    public static final int DEFAULT_BUCKET_COUNT = 100;

    /** Sentinel for an unused bucket (so bucketIdx=0 is still distinguishable). */
    private static final long UNINITIALISED = Long.MIN_VALUE;

    private final int maxRequests;
    private final long windowMs;
    private final int bucketCount;
    private final long bucketSizeMs;
    private final long bucketSizeNs;
    /** Counts per bucket, lazily-aged by {@link #tryAcquire(long)}. */
    private final AtomicLongArray counts;
    /** Generation (bucketIdx) the count was last bumped for; UNINITIALISED = never. */
    private final AtomicLongArray generations;

    public SlidingWindowRateLimiter() {
        this(DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW_MS, DEFAULT_BUCKET_COUNT);
    }

    public SlidingWindowRateLimiter(int maxRequests, long windowMs, int bucketCount) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("maxRequests must be >= 1, got " + maxRequests);
        }
        if (windowMs < 10) {
            throw new IllegalArgumentException("windowMs must be >= 10, got " + windowMs);
        }
        if (bucketCount < 2) {
            throw new IllegalArgumentException("bucketCount must be >= 2, got " + bucketCount);
        }
        if (windowMs % bucketCount != 0) {
            throw new IllegalArgumentException(
                    "windowMs must be a multiple of bucketCount (got "
                            + windowMs + " and " + bucketCount + ")");
        }
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
        this.bucketCount = bucketCount;
        this.bucketSizeMs = windowMs / bucketCount;
        this.bucketSizeNs = this.bucketSizeMs * 1_000_000L;
        this.counts = new AtomicLongArray(bucketCount);
        this.generations = new AtomicLongArray(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            generations.set(i, UNINITIALISED);
        }
    }

    public int maxRequests() { return maxRequests; }
    public long windowMs() { return windowMs; }
    public int bucketCount() { return bucketCount; }

    /**
     * Try to consume one request slot. Returns the decision
     * (allow or deny) and the current usage for diagnostics.
     * Never blocks.
     */
    public Decision tryAcquire() {
        return tryAcquire(System.nanoTime());
    }

    /**
     * Same as {@link #tryAcquire()} but with an injectable
     * "now" in nanoseconds (for tests that need a deterministic
     * clock).
     */
    public Decision tryAcquire(long nowNanos) {
        long bucketIdx = nowNanos / bucketSizeNs;
        int slot = (int) Math.floorMod(bucketIdx, bucketCount);
        // Trim: zero every bucket whose recorded generation is
        // older than `bucketCount` sub-windows ago. We loop
        // over all slots because up to `bucketCount` may be
        // stale (the limiter has been idle, or the clock has
        // jumped). The loop is O(bucketCount) but each
        // iteration is a single CAS.
        long minAlive = bucketIdx - (bucketCount - 1);
        for (int i = 0; i < bucketCount; i++) {
            long gen = generations.get(i);
            if (gen != UNINITIALISED && gen < minAlive) {
                // Try to claim the trim; another thread may
                // have raced and bumped this bucket for a new
                // sub-window, in which case we leave it alone.
                if (generations.compareAndSet(i, gen, UNINITIALISED)) {
                    counts.set(i, 0L);
                }
            }
        }
        // Sum the live window.
        long total = 0L;
        for (int i = 0; i < bucketCount; i++) {
            total += counts.get(i);
        }
        if (total >= maxRequests) {
            return new Decision(false, total, maxRequests, windowMs);
        }
        // Bump the current bucket. Reset it first if this is
        // the first request of a fresh sub-window.
        long currentGen = generations.get(slot);
        if (currentGen != bucketIdx) {
            if (generations.compareAndSet(slot, currentGen, bucketIdx)) {
                counts.set(slot, 0L);
            }
        }
        counts.incrementAndGet(slot);
        return new Decision(true, total + 1L, maxRequests, windowMs);
    }

    /** Reset the limiter. Primarily for tests. */
    public void reset() {
        for (int i = 0; i < bucketCount; i++) {
            counts.set(i, 0L);
            generations.set(i, UNINITIALISED);
        }
    }

    @Override
    public String toString() {
        return "SlidingWindowRateLimiter{"
                + "maxRequests=" + maxRequests
                + ", windowMs=" + windowMs
                + ", bucketCount=" + bucketCount
                + '}';
    }

    /**
     * Outcome of a {@link #tryAcquire()} call.
     *
     * @param allowed      true if the call fits in the window
     * @param currentCount total calls in the window (including this one if allowed)
     * @param limit        the cap (echo of {@link #maxRequests()})
     * @param windowMs     the window length (echo of {@link #windowMs()})
     */
    public record Decision(boolean allowed, long currentCount, int limit, long windowMs) {
        public long remaining() { return Math.max(0L, limit - currentCount); }
    }
}
