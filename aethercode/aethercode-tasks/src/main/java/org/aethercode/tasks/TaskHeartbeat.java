package org.aethercode.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * emits periodic heartbeat events for tasks that are
 * still RUNNING. A heartbeat is useful for long-running
 * tasks (multi-minute plan steps, slow API calls) where the
 * TUI wants to show "still alive" without polling the
 * registry.
 *
 * <p>Usage:
 * <pre>{@code
 * TaskHeartbeat hb = new TaskHeartbeat(registry, 5_000L,
 *         ev -> log.info("heartbeat {}", ev));
 * hb.start();
 * }</pre>
 *
 * <p>Each tick iterates RUNNING tasks and emits a
 * {@link Heartbeat} event with the task id, age, and a wall-
 * clock timestamp.
 */
public final class TaskHeartbeat {

    private static final Logger LOG = LoggerFactory.getLogger(TaskHeartbeat.class);

    public record Heartbeat(String taskId, long ageMs, long timestampMs) {}

    private final TaskRegistry registry;
    private final long intervalMs;
    private final Consumer<Heartbeat> onBeat;
    private final ScheduledExecutorService executor;
    private final java.util.List<String> running = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> future;
    private volatile boolean started = false;

    public TaskHeartbeat(TaskRegistry registry, long intervalMs, Consumer<Heartbeat> onBeat) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        if (intervalMs < 20) throw new IllegalArgumentException("intervalMs must be >= 20ms");
        if (onBeat == null) throw new IllegalArgumentException("onBeat must not be null");
        this.registry = registry;
        this.intervalMs = intervalMs;
        this.onBeat = onBeat;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aethercode-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public long intervalMs() { return intervalMs; }
    public boolean isRunning() { return started; }

    public synchronized void start() {
        if (started) return;
        future = executor.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
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

    /** how many running tasks the heartbeat is currently
     *  tracking. Updated each tick. */
    public java.util.List<String> runningTaskIds() {
        return List.copyOf(running);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        java.util.List<String> current = new java.util.ArrayList<>();
        for (Task t : registry.list()) {
            if (t.status() != TaskStatus.RUNNING) continue;
            current.add(t.id());
            long age = now - t.createdAtMs();
            try {
                onBeat.accept(new Heartbeat(t.id(), age, now));
            } catch (Exception e) {
                LOG.warn("heartbeat listener threw: {}", e.getMessage());
            }
        }
        running.clear();
        running.addAll(current);
    }
}
