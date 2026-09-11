package org.aethercode.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * a watchdog that detects silent runs. A long-running
 * task can hang — the model gets stuck, the network drops, the
 * process runs out of file descriptors — and the only signal
 * the user gets is a frozen status bar. The watchdog polls a
 * "last event time" supplier every {@link #pollMs} milliseconds;
 * if the gap exceeds {@link #timeoutMs}, it fires a callback
 * (typically: ping the model, log a warning, or abort the run).
 *
 * <p>The watchdog is stateful: a successful call to
 * {@link #kick()} resets the timer. Most callers wire
 * {@code kick} to a stream event handler so every model
 * event extends the timeout.
 *
 * <p>This class is thread-safe. The background thread uses a
 * single-threaded executor; the kick method is non-blocking.
 */
public final class Watchdog implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Watchdog.class);

    /** how often the watchdog checks for silence. */
    public static final long DEFAULT_POLL_MS = 5_000L;
    /** how long a run can be silent before the watchdog
     *  fires. 60s is the default — long enough that a model
     *  "thinking" pause doesn't trigger, short enough that a
     *  10-hour run has a chance to recover. */
    public static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /** action taken on timeout. The integer is the elapsed
     *  silence duration in milliseconds (for the callback to
     *  decide what to do). */
    public interface TimeoutHandler {
        void onTimeout(long silenceMs);
    }

    private final LongSupplier lastEventTimeMs;
    private final TimeoutHandler handler;
    private final long pollMs;
    private final long timeoutMs;
    private final AtomicLong lastKickMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean tripped = new AtomicBoolean(false);
    private final ScheduledExecutorService exec;
    private ScheduledFuture<?> handle;

    public Watchdog(LongSupplier lastEventTimeMs, TimeoutHandler handler) {
        this(lastEventTimeMs, handler, DEFAULT_POLL_MS, DEFAULT_TIMEOUT_MS);
    }

    public Watchdog(LongSupplier lastEventTimeMs, TimeoutHandler handler,
                    long pollMs, long timeoutMs) {
        if (lastEventTimeMs == null) throw new IllegalArgumentException("lastEventTimeMs");
        if (handler == null) throw new IllegalArgumentException("handler");
        if (pollMs < 100) throw new IllegalArgumentException("pollMs must be >= 100");
        if (timeoutMs < pollMs) throw new IllegalArgumentException("timeoutMs must be >= pollMs");
        this.lastEventTimeMs = lastEventTimeMs;
        this.handler = handler;
        this.pollMs = pollMs;
        this.timeoutMs = timeoutMs;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aethercode-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /** start the watchdog. */
    public void start() {
        if (handle != null) return;
        lastKickMs.set(System.currentTimeMillis());
        handle = exec.scheduleAtFixedRate(this::tick, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    /** stop the watchdog. Safe to call multiple times. */
    public void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
    }

    /** reset the timer (call from your event handler). */
    public void kick() {
        lastKickMs.set(System.currentTimeMillis());
    }

    /** did the watchdog fire at least once? Useful for tests
     *  and the TUI's "model stalled" hint. */
    public boolean isTripped() { return tripped.get(); }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            long lastEvent = lastEventTimeMs.getAsLong();
            long silence = now - Math.max(lastEvent, lastKickMs.get());
            if (silence >= timeoutMs && !tripped.get()) {
                tripped.set(true);
                LOG.warn("对应历史 round watchdog fired: silence={}ms (timeout={}ms)", silence, timeoutMs);
                handler.onTimeout(silence);
            } else if (silence < timeoutMs) {
                tripped.set(false);
            }
        } catch (Throwable t) {
            LOG.warn("watchdog tick threw: {}", t.getMessage());
        }
    }

    @Override
    public void close() {
        stop();
        exec.shutdown();
        try {
            if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
