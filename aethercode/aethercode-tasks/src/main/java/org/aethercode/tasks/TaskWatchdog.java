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
 * a watchdog that polls the {@link TaskRegistry} and
 * auto-cancels (KILL) any RUNNING task whose age exceeds a
 * threshold. Prevents runaway tasks from blocking long-running
 * sessions.
 *
 * <p>Usage:
 * <pre>{@code
 * TaskWatchdog wd = new TaskWatchdog(registry, 30L * 60_000L); // 30 min
 * wd.start();
 * // ...later
 * wd.stop();
 * }</pre>
 *
 * <p>The watchdog uses a separate daemon-thread executor. The
 * poll interval is independent of the timeout — typically the
 * poll is much shorter (a few seconds) so we can catch overdue
 * tasks promptly.
 */
public final class TaskWatchdog {

    private static final Logger LOG = LoggerFactory.getLogger(TaskWatchdog.class);

    private final TaskRegistry registry;
    private final long maxAgeMs;
    private final long pollIntervalMs;
    private final Consumer<String> onKill; // optional callback
    private final ScheduledExecutorService executor;
    private final AtomicLong killsTriggered = new AtomicLong(0);
    private ScheduledFuture<?> future;
    private volatile boolean running = false;

    public TaskWatchdog(TaskRegistry registry, long maxAgeMs) {
        this(registry, maxAgeMs, 5_000L, null);
    }

    public TaskWatchdog(TaskRegistry registry, long maxAgeMs, long pollIntervalMs, Consumer<String> onKill) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        if (maxAgeMs < 50) throw new IllegalArgumentException("maxAgeMs must be >= 50ms");
        if (pollIntervalMs < 10) throw new IllegalArgumentException("pollIntervalMs must be >= 10ms");
        this.registry = registry;
        this.maxAgeMs = maxAgeMs;
        this.pollIntervalMs = pollIntervalMs;
        this.onKill = onKill;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aethercode-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public long maxAgeMs() { return maxAgeMs; }
    public long pollIntervalMs() { return pollIntervalMs; }
    public long killsTriggered() { return killsTriggered.get(); }
    public boolean isRunning() { return running; }

    public synchronized void start() {
        if (running) return;
        future = executor.scheduleAtFixedRate(this::tick, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
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

    /** trigger one tick immediately. Returns the number of
     *  tasks killed by this tick. Useful for tests. */
    public int tickNow() {
        return tick();
    }

    private int tick() {
        long now = System.currentTimeMillis();
        int killed = 0;
        try {
            for (Task t : registry.list()) {
                if (t.status() != TaskStatus.RUNNING) continue;
                long age = now - t.createdAtMs();
                if (age <= maxAgeMs) continue;
                try {
                    registry.updateStatus(t.id(), TaskStatus.KILLED);
                    killsTriggered.incrementAndGet();
                    killed++;
                    if (onKill != null) onKill.accept(t.id());
                    LOG.warn("watchdog killed task {} (age {}ms > {}ms)", t.id(), age, maxAgeMs);
                } catch (Exception e) {
                    LOG.warn("watchdog failed to kill task {}: {}", t.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("watchdog tick failed: {}", e.getMessage());
        }
        return killed;
    }
}
