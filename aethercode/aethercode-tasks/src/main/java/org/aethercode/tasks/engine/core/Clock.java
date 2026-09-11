package org.aethercode.tasks.engine.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wall-clock abstraction so the supervisor's periodic guards
 * (WallClockGuard, IdleGuard) can be tested without sleeping.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link SystemClock} — real wall clock.</li>
 *   <li>{@link SettableClock} — manually advanced; for unit tests
 *       and for the 10-hour simulation in T-1-03 / T-1-04.</li>
 * </ul>
 *
 * <p>The interface is in millisecond precision; the supervisor
 * never needs sub-millisecond accuracy for limit checks.
 */
public interface Clock {
    /** Current wall-clock time in ms since the unix epoch. */
    long nowMs();

    /** Current wall-clock time as an {@link Instant}. */
    default Instant nowInstant() { return Instant.ofEpochMilli(nowMs()); }

    /** Sleep for {@code duration}; real clocks use Thread.sleep,
     *  settable clocks are no-ops (test drives the clock). */
    default void sleep(Duration duration) throws InterruptedException {
        long ms = duration.toMillis();
        if (ms <= 0) return;
        Thread.sleep(ms);
    }

    /** Real wall clock backed by {@link System#currentTimeMillis()}. */
    final class SystemClock implements Clock {
        public static final SystemClock INSTANCE = new SystemClock();
        @Override public long nowMs() { return System.currentTimeMillis(); }
    }

    /**
     * A settable clock useful for tests. The clock is advanced
     * by {@link #advance(long)} (or {@link #advanceTo(long)}).
     * Thread-safe via an {@link AtomicLong}.
     */
    final class SettableClock implements Clock {
        private final AtomicLong now;

        public SettableClock() { this(System.currentTimeMillis()); }
        public SettableClock(long initialMs) { this.now = new AtomicLong(initialMs); }

        @Override public long nowMs() { return now.get(); }

        /** Advance the clock by {@code deltaMs}; may be negative. */
        public void advance(long deltaMs) { now.addAndGet(deltaMs); }

        /** Set the clock to an absolute time. */
        public void advanceTo(long absoluteMs) { now.set(absoluteMs); }

        /** Sleep is a no-op; tests drive the clock manually. */
        @Override public void sleep(Duration duration) { /* no-op */ }
    }
}
