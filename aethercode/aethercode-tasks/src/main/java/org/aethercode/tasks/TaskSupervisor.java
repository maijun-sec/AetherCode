package org.aethercode.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * a single-supervisor daemon that combines the watchdog
 * (kills overdue RUNNING tasks) and the heartbeat (emits
 * periodic events for RUNNING tasks). One daemon instead of
 * two simplifies wiring and reduces thread count.
 *
 * <p>Usage:
 * <pre>{@code
 * TaskSupervisor sup = new TaskSupervisor(registry,
 *         30L * 60_000L,  // max age before kill
 *         5_000L,         // heartbeat interval
 *         hb -> log.info("hb {}", hb.taskId()),
 *         null            // no kill callback
 * );
 * sup.start();
 * }</pre>
 */
public final class TaskSupervisor {

    private static final Logger LOG = LoggerFactory.getLogger(TaskSupervisor.class);

    private final TaskRegistry registry;
    private final long maxAgeMs;
    private final long heartbeatIntervalMs;
    private final Consumer<TaskHeartbeat.Heartbeat> onBeat;
    private final Consumer<String> onKill;
    private final ScheduledExecutorService executor;
    private final AtomicLong kills = new AtomicLong(0);
    private final AtomicLong beats = new AtomicLong(0);
    private final java.util.List<String> running = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> future;
    private volatile boolean started = false;

    public TaskSupervisor(TaskRegistry registry, long maxAgeMs, long heartbeatIntervalMs,
                          Consumer<TaskHeartbeat.Heartbeat> onBeat,
                          Consumer<String> onKill) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        if (maxAgeMs < 100) throw new IllegalArgumentException("maxAgeMs must be >= 100ms");
        if (heartbeatIntervalMs < 20) throw new IllegalArgumentException("heartbeatIntervalMs must be >= 20ms");
        this.registry = registry;
        this.maxAgeMs = maxAgeMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.onBeat = onBeat;
        this.onKill = onKill;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aethercode-supervisor");
            t.setDaemon(true);
            return t;
        });
    }

    public long maxAgeMs() { return maxAgeMs; }
    public long heartbeatIntervalMs() { return heartbeatIntervalMs; }
    public long killCount() { return kills.get(); }
    public long beatCount() { return beats.get(); }
    public java.util.List<String> runningTaskIds() { return List.copyOf(running); }
    public boolean isRunning() { return started; }

    public synchronized void start() {
        if (started) return;
        // Schedule the supervisor at the heartbeat cadence. Each
        // tick does both heartbeat and watchdog.
        future = executor.scheduleAtFixedRate(this::tick, heartbeatIntervalMs,
                heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        started = true;
    }

    public synchronized void stop() {
        if (!started) return;
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
        started = false;
    }

    /** trigger one supervisor tick now. Useful for tests. */
    public int tickNow() {
        return tick();
    }

    private int tick() {
        long now = System.currentTimeMillis();
        java.util.List<String> currentRunning = new java.util.ArrayList<>();
        int killed = 0;
        for (Task t : registry.list()) {
            if (t.status() != TaskStatus.RUNNING) continue;
            currentRunning.add(t.id());
            long age = now - t.createdAtMs();
            // heartbeat.
            if (onBeat != null) {
                try {
                    onBeat.accept(new TaskHeartbeat.Heartbeat(t.id(), age, now));
                } catch (Exception e) {
                    LOG.warn("supervisor beat listener threw: {}", e.getMessage());
                }
            }
            beats.incrementAndGet();
            // watchdog — kill if overdue.
            if (age > maxAgeMs) {
                try {
                    registry.updateStatus(t.id(), TaskStatus.KILLED);
                    kills.incrementAndGet();
                    killed++;
                    if (onKill != null) onKill.accept(t.id());
                } catch (Exception e) {
                    LOG.warn("supervisor kill failed for {}: {}", t.id(), e.getMessage());
                }
            }
        }
        running.clear();
        running.addAll(currentRunning);
        return killed;
    }
}
