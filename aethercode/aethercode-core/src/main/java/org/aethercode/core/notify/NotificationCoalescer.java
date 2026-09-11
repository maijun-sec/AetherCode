package org.aethercode.core.notify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * batch up notifications within a time window. The classic
 * case is the LLM tool loop firing 5 "tool called" events in 200ms —
 * the user would rather see "5 tool calls in the last 5s" than 5
 * separate toasts.
 *
 * <p>Per-key coalescing: the same {@code key} (e.g. tool name) is
 * accumulated; the flush callback receives a list of all accumulated
 * values for that key. A different key flushes independently.
 */
public class NotificationCoalescer<T> {

    public record FlushEntry<T>(String key, List<T> values) {
        public int count() { return values.size(); }
    }

    private final Duration window;
    private final Consumer<List<FlushEntry<T>>> onFlush;
    private final Map<String, List<T>> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> scheduled;

    public NotificationCoalescer(Duration window, Consumer<List<FlushEntry<T>>> onFlush) {
        this(window, onFlush, false);
    }

    public NotificationCoalescer(Duration window, Consumer<List<FlushEntry<T>>> onFlush, boolean daemonExecutor) {
        if (window == null || window.isZero() || window.isNegative())
            throw new IllegalArgumentException("window must be positive");
        if (onFlush == null) throw new IllegalArgumentException("onFlush is null");
        this.window = window;
        this.onFlush = onFlush;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "notif-coalescer");
            t.setDaemon(daemonExecutor);
            return t;
        });
    }

    /** add a notification under a key. Schedules a flush if not already pending. */
    public synchronized void add(String key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        pending.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        if (scheduled == null || scheduled.isDone()) {
            scheduled = executor.schedule(this::flush, window.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** force-flush all pending notifications. */
    public synchronized void flush() {
        if (pending.isEmpty()) return;
        List<FlushEntry<T>> entries = new ArrayList<>();
        for (Map.Entry<String, List<T>> e : pending.entrySet()) {
            entries.add(new FlushEntry<>(e.getKey(), List.copyOf(e.getValue())));
        }
        pending.clear();
        try {
            onFlush.accept(entries);
        } catch (RuntimeException e) {
            // don't let consumer exceptions break the scheduler loop
        }
    }

    public int pendingCount() {
        return pending.values().stream().mapToInt(List::size).sum();
    }

    public int pendingKeyCount() {
        return pending.size();
    }

    /** the configured flush window. */
    public Duration window() { return window; }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** sequence number for ordering events. */
    public long currentSequence() { return sequence.get(); }
}
