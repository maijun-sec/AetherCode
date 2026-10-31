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
