package org.aethercode.deepagents.selfimprove;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R245.3 (O-3 + O-10): periodic {@link ReasoningBank#decayPass}
 * scheduler. Bumps the effective utility of every unit at
 * a fixed cadence so the strategy library naturally ages
 * out cold strategies (R241.3) without the host having to
 * wire a cron / timer.
 *
 * <h2>Why a dedicated class</h2>
 *
 * <p>Without a scheduler, {@code decayPass} only runs when
 * a tool call touches the bank. A daemon that just sits
 * idle never re-evaluates utility, so cold units keep
 * ranking high forever. R245.3 fixes that with a daemon-side
 * scheduled task that runs {@code decayPass} every
 * {@code interval} (default: 60 minutes; opt-in via
 * {@code DEEPAGENTS_TALON_DECAY_INTERVAL_MIN}).</p>
 *
 * <h2>Why {@link ScheduledExecutorService}</h2>
 *
 * <p>JDK's {@code ScheduledExecutorService} is purpose-built
 * for this, gives us daemon thread semantics, and lets us
 * cleanly {@link #stop()} the loop when the daemon shuts
 * down. No external dependencies.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link ReasoningBank#decayPass} is the production
 * critical section — it's also called by the recall
 * middleware. The scheduler just calls into the same
 * method, so the bank's own synchronization covers it.
 * We also count how many ticks fired
 * ({@link #tickCount}) and how many units changed in the
 * last tick ({@link #lastChanged}) for observability; the
 * counters use {@link AtomicLong} so a host log line can
 * read them without locking.</p>
 *
 * <h2>Failure isolation</h2>
 *
 * <p>A failed tick (e.g. disk full) logs the error and
 * <em>keeps the scheduler alive</em> — one bad tick
 * shouldn't kill the loop. The bank itself already
 * degrades gracefully on storage errors (R241.3).</p>
 */
public final class DecayScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DecayScheduler.class);

    private final ReasoningBank bank;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> future;
    private final AtomicLong tickCount = new AtomicLong(0L);
    private final AtomicLong lastChanged = new AtomicLong(0L);

    private DecayScheduler(ReasoningBank bank, Duration interval, ScheduledExecutorService executor) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.future = executor.scheduleAtFixedRate(
                this::tickSafe, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Build + start a scheduler that fires {@code decayPass}
     *  every {@code interval}. The first tick fires after
     *  {@code interval} (no immediate boot tick — a fresh
     *  bank has nothing to decay yet, and we want the
     *  scheduler to be quiet during startup). */
    public static DecayScheduler start(ReasoningBank bank, Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "decay-scheduler");
            t.setDaemon(true);
            return t;
        });
        return new DecayScheduler(bank, interval, executor);
    }

    /** Total number of ticks fired since start. */
    public long tickCount() { return tickCount.get(); }

    /** Units whose effective utility changed in the most
     *  recent tick. Zero before the first tick fires. */
    public long lastChanged() { return lastChanged.get(); }

    /** Resolved interval (for logging). */
    public Duration interval() { return interval; }

    /** Stop the scheduler. Idempotent. Safe to call from
     *  a shutdown hook or a test tearDown. */
    public void stop() {
        future.cancel(false);
        executor.shutdownNow();
    }

    /** {@link AutoCloseable} for try-with-resources. */
    @Override
    public void close() {
        stop();
    }

    // --- internals ---

    /** Tick that catches + logs (but does not rethrow) so
     *  one bad tick never kills the loop. */
    private void tickSafe() {
        try {
            int n = bank.decayPass(Instant.now());
            tickCount.incrementAndGet();
            lastChanged.set(n);
            if (n > 0) {
                LOG.info("decay-scheduler tick {}: rewrote {} unit(s) (interval={})",
                        tickCount.get(), n, interval);
            } else {
                LOG.debug("decay-scheduler tick {}: no-op (interval={})",
                        tickCount.get(), interval);
            }
        } catch (Throwable t) {
            // R245.3: do NOT let the loop die on a bad tick.
            // Log at warn (the bank is the likely root cause
            // and the host should notice), but keep running.
            LOG.warn("decay-scheduler tick failed (will retry next interval): {}",
                    t.getMessage(), t);
        }
    }
}
