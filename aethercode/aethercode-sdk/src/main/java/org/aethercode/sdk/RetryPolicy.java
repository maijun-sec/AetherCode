package org.aethercode.sdk;

import java.util.concurrent.Callable;

/**
 * a simple retry policy for step execution. When a step
 * throws, the policy decides how long to wait before the next
 * attempt and how many attempts to make in total.
 *
 * <p>Defaults are conservative: 3 attempts max, 1s base backoff,
 * 10s cap, 2x multiplier. A failed step therefore takes at
 * most {@code 1s + 2s = 3s} of waiting before the final
 * attempt. Callers that need different trade-offs (e.g. network
 * calls vs local tools) construct a custom policy.
 *
 * <p>The policy is a pure value object — no I/O, no time. The
 * actual sleeping is done by the caller (typically
 * {@link RetryHelper}) so tests can pass a fake clock.
 */
public record RetryPolicy(
        int maxAttempts,
        long baseBackoffMs,
        long maxBackoffMs,
        double multiplier,
        double jitter
) {

    /** default policy. 3 attempts, 1s → 2s backoff with
     *  0.1 jitter. Suitable for most tool calls. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(
            3, 1_000L, 10_000L, 2.0, 0.1);

    /** aggressive policy. 5 attempts, 100ms → 200ms → 400ms.
     *  Use for short-lived operations that occasionally flake. */
    public static final RetryPolicy AGGRESSIVE = new RetryPolicy(
            5, 100L, 2_000L, 2.0, 0.1);

    /** no retry — fail on the first error. */
    public static final RetryPolicy NONE = new RetryPolicy(
            1, 0L, 0L, 1.0, 0.0);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        if (baseBackoffMs < 0) {
            throw new IllegalArgumentException("baseBackoffMs must be >= 0");
        }
        if (maxBackoffMs < baseBackoffMs) {
            throw new IllegalArgumentException("maxBackoffMs must be >= baseBackoffMs");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter must be in [0, 1]");
        }
    }

    /** compute the backoff (in ms) before the given attempt
     *  number. {@code attempt} is 1-based; the first attempt has
     *  no backoff. */
    public long backoffFor(int attempt) {
        if (attempt <= 1) return 0L;
        // Backoff = base * multiplier^(attempt-2), capped at max.
        double raw = baseBackoffMs * Math.pow(multiplier, attempt - 2);
        long capped = (long) Math.min(raw, maxBackoffMs);
        if (jitter <= 0) return capped;
        // Jitter: random in [capped*(1-jitter), capped*(1+jitter)]
        double delta = capped * jitter;
        long lo = (long) (capped - delta);
        long hi = (long) (capped + delta);
        return lo + (long) (Math.random() * Math.max(0, hi - lo));
    }

    /** should we retry? Returns true when the attempt
     *  number is less than {@link #maxAttempts}. */
    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }
}
