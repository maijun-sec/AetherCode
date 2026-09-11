package org.aethercode.tasks.supervisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * prior round (T-313/§4.1.2 design.md): 3-strike auto-restart policy
 * for the supervisor. The supervisor wraps its long-lived work
 * in this policy; on any uncaught exception thrown out of the
 * run loop, the policy schedules a restart.
 *
 * <p>Rules:
 * <ul>
 *   <li>First 3 consecutive failures → restart with exponential
 *       backoff (e.g. 1s, 2s, 4s).</li>
 *   <li>4th failure within {@code windowMs} → call the
 *       {@code onGiveUp} hook (the daemon "asks user") and
 *       stop restarting.</li>
 *   <li>If {@code windowMs} elapses without a failure, the
 *       counter resets to 0 (transient blips don't accumulate).</li>
 * </ul>
 *
 * <p>Test usage:
 * <pre>{@code
 *   AtomicInteger restarts = new AtomicInteger();
 *   AutoRestartPolicy p = new AutoRestartPolicy(3, 60_000L, restarts::incrementAndGet, () -> fail);
 *   p.recordFailure();   // 1
 *   p.recordFailure();   // 2
 *   p.recordFailure();   // 3
 *   p.recordFailure();   // triggers onGiveUp
 *   assertTrue(p.gaveUp());
 * }</pre>
 */
public final class AutoRestartPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(AutoRestartPolicy.class);

    private final int maxStrikes;
    private final long windowMs;
    private final LongConsumer onRestart;        // receives the backoff ms
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

    public int strikes() { return strikes.get(); }
    public int restarts() { return restarts.get(); }
    public boolean gaveUp() { return givesUp.get() > 0; }

    /** Record a successful tick; clears the strike window. */
    public void recordSuccess() {
        strikes.set(0);
        firstFailureAtMs = 0L;
    }

    /**
     * Record a failure. Returns the backoff in ms before the
     * next restart (0 if the policy has given up).
     */
    public long recordFailure() {
        long now = System.currentTimeMillis();
        if (firstFailureAtMs == 0L || (now - firstFailureAtMs) > windowMs) {
            firstFailureAtMs = now;
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
        // Exponential backoff capped at 30s.
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
