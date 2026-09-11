package org.aethercode.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * reconnect strategy with exponential backoff + jitter. Modelled on the TS
 * {@code src/bridge/reconnect.ts}.
 *
 * <p>Strategy: start at {@link #initialDelayMs} and double up to
 * {@link #maxDelayMs}. Add up to 25% jitter so a thundering herd of clients
 * doesn't all retry at the same instant. Cap the total number of attempts at
 * {@link #maxAttempts} to give up after sustained failure.
 */
public class ReconnectStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(ReconnectStrategy.class);

    public static final int DEFAULT_INITIAL_DELAY_MS = 1_000;
    public static final int DEFAULT_MAX_DELAY_MS = 30_000;
    public static final int DEFAULT_MAX_ATTEMPTS = 8;

    private final int initialDelayMs;
    private final int maxDelayMs;
    private final int maxAttempts;
    private final AtomicInteger attempt = new AtomicInteger(0);
    private IntConsumer onGiveUp;

    public ReconnectStrategy() {
        this(DEFAULT_INITIAL_DELAY_MS, DEFAULT_MAX_DELAY_MS, DEFAULT_MAX_ATTEMPTS);
    }

    public ReconnectStrategy(int initialDelayMs, int maxDelayMs, int maxAttempts) {
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxAttempts = maxAttempts;
    }

    public ReconnectStrategy onGiveUp(IntConsumer c) { this.onGiveUp = c; return this; }

    /** Reset the attempt counter (e.g. after a successful connection). */
    public void reset() { attempt.set(0); }

    /** Compute the delay for the next attempt. Returns -1 if we should give up. */
    public int nextDelayMs() {
        int n = attempt.incrementAndGet();
        if (n > maxAttempts) {
            LOG.warn("bridge reconnect: giving up after {} attempts", maxAttempts);
            if (onGiveUp != null) onGiveUp.accept(n);
            return -1;
        }
        long base = Math.min(maxDelayMs, initialDelayMs * (1L << (n - 1)));
        long jitter = (long) (base * 0.25 * ThreadLocalRandom.current().nextDouble());
        return (int) (base + jitter);
    }

    public int attempts() { return attempt.get(); }
    public int maxAttempts() { return maxAttempts; }
}
