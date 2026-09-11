package org.aethercode.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * a small helper that periodically invokes a callback.
 * Modelled on Claude Code's "task state autosave" — the user
 * wires a callback that reads the registry (or any other state)
 * and persists it. Default: every 30s, single-thread daemon
 * executor, errors are logged but never thrown.
 *
 * <p>Usage:
 * <pre>{@code
 * CheckpointScheduler sched = new CheckpointScheduler(
 *         30_000L,
 *         () -> persistTasks(registry.list()));
 * sched.start();
 * }</pre>
 */
public final class CheckpointScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointScheduler.class);

    private final long intervalMs;
    private final Consumer<Long> callback;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private final AtomicLong invocations = new AtomicLong(0);
    private final AtomicLong failures = new AtomicLong(0);
    private volatile boolean running = false;

    public CheckpointScheduler(long intervalMs, Runnable callback) {
        this(intervalMs, tick -> {
            if (callback == null) return;
            callback.run();
        });
        // The inner constructor already null-checks the wrapped
        // callback, so this overload is safe to call with a
        // null Runnable too. Document it explicitly:
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
    }

    public CheckpointScheduler(long intervalMs, Consumer<Long> callback) {
        if (intervalMs < 100) throw new IllegalArgumentException("intervalMs must be >= 100ms");
        if (callback == null) throw new IllegalArgumentException("callback must not be null");
        this.intervalMs = intervalMs;
        this.callback = callback;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aethercode-checkpoint");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (running) return;
        future = executor.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        running = true;
    }

    public synchronized void stop() {
        if (!running) return;
        if (future != null) future.cancel(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        running = false;
    }

    public long intervalMs() { return intervalMs; }
    public boolean isRunning() { return running; }
    public long invocations() { return invocations.get(); }
    public long failures() { return failures.get(); }

    /** trigger the callback immediately (synchronous).
     *  Useful for shutdown hooks or test fixtures. */
    public void tickNow() {
        tick();
    }

    private void tick() {
        long n = invocations.incrementAndGet();
        try {
            callback.accept(n);
        } catch (Throwable t) {
            failures.incrementAndGet();
            LOG.warn("checkpoint tick #{} failed: {}", n, t.getMessage());
        }
    }
}
