package org.aethercode.tasks.engine.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * 3-strike auto-restart policy for the supervisor's main run loop.
 * Modeled after AetherCode's AutoRestartPolicy (aethercode-tasks).
 *
 * <p>Rules:
 * <ul>
 *   <li>First {@code maxStrikes} consecutive failures → restart with
 *       exponential backoff (e.g. 1 s, 2 s, 4 s, capped at 30 s).</li>
 *   <li>The {@code (maxStrikes + 1)}-th failure within {@code windowMs}
 *       → call the {@code onGiveUp} hook and stop restarting.</li>
 *   <li>If {@code windowMs} elapses without a failure, the counter
 *       resets to 0 (transient blips don't accumulate).</li>
 * </ul>
 */
public final class AutoRestartPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(AutoRestartPolicy.class);

    private final int maxStrikes;
    private final long windowMs;
    private final LongConsumer onRestart;
    private final Runnable onGiveUp;
    private final AtomicInteger strikes = new AtomicInteger(0);
    private final AtomicInteger restarts = new AtomicInteger(0);
    private final AtomicInteger givesUp = new AtomicInteger(0);
    private volatile long firstFailureAtMs = 0L;

    public AutoRestartPolicy(int maxStrikes, long windowMs,
                             LongConsumer onRestart, Runnable onGiveUp) {
        if (maxStrikes < 1) throw new IllegalArgumentException("maxStrikes must be >= 1");
        if (windowMs < 10) throw new IllegalArgumentException("windowMs must be >= 10ms");
        this.maxStrikes = maxStrikes;
        this.windowMs = windowMs;
        this.onRestart = onRestart;
        this.onGiveUp = onGiveUp;
    }

    public int maxStrikes() { return maxStrikes; }
    public long windowMs() { return windowMs; }
    public int strikes() { return strikes.get(); }
    public int restarts() { return restarts.get(); }
    public boolean gaveUp() { return givesUp.get() > 0; }

    /** Record a successful tick; clears the strike window. */
    public void recordSuccess() {
        strikes.set(0);
        firstFailureAtMs = 0L;
    }

    /**
     * Record a failure. Returns the backoff in ms before the next
     * restart, or 0 if the policy has given up. The default
     * implementation uses {@link System#currentTimeMillis()} for
     * the wall clock; tests should drive the policy through a
     * custom clock (see {@link #recordFailureAt(long)}).
     */
    public long recordFailure() {
        return recordFailureAt(System.currentTimeMillis());
    }

    /** Test-friendly variant: record a failure at an explicit time. */
    public long recordFailureAt(long nowMs) {
        if (firstFailureAtMs == 0L || (nowMs - firstFailureAtMs) > windowMs) {
            firstFailureAtMs = nowMs;
            strikes.set(1);
        } else {
            strikes.incrementAndGet();
        }
        int s = strikes.get();
        if (s > maxStrikes) {
            if (givesUp.compareAndSet(0, 1)) {
                LOG.error("supervisor gave up after {} strikes within {}ms", s - 1, windowMs);
                if (onGiveUp != null) {
                    try { onGiveUp.run(); } catch (Exception e) {
                        LOG.warn("onGiveUp threw: {}", e.getMessage());
                    }
                }
            }
            return 0L;
        }
        long backoff = Math.min(30_000L, 1_000L << Math.min(s - 1, 5));
        restarts.incrementAndGet();
        LOG.warn("supervisor restart {} of {} (backoff {}ms)", s, maxStrikes, backoff);
        if (onRestart != null) {
            try { onRestart.accept(backoff); } catch (Exception e) {
                LOG.warn("onRestart threw: {}", e.getMessage());
            }
        }
        return backoff;
    }
}
