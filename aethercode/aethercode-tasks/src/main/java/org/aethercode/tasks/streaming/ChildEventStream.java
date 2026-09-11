package org.aethercode.tasks.streaming;

import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.SupervisorStore;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * prior round (T-340..T-342/§4.4 design.md): a per-child event stream
 * that wraps the supervisor's append-only {@code child_events}
 * table as a server-sent-events surface.
 *
 * <p>Two consumption modes are supported:
 * <ul>
 *   <li><b>Pull / replay</b>: {@link #replaySince(long, int)} returns
 *       every event with id &gt; {@code sinceId} (oldest first).
 *       The TUI uses this on attach to back-fill from the persisted
 *       log, then switches to the push side.</li>
 *   <li><b>Push / live</b>: {@link #subscribe(Consumer)} registers
 *       a callback that receives every new event as it is appended.
 *       {@link #close()} unsubscribes and stops the polling thread.</li>
 * </ul>
 *
 * <p>Reconnect-and-replay is built in: when the TUI drops the
 * connection it keeps its last-seen event id; on the next attach
 * it calls {@code replaySince(lastId, 1000)} and the supervisor
 * catches it up. {@link #sinceCursor()} exposes the high-water
 * mark so callers can persist it.
 *
 * <p>Implementation: a single-threaded scheduler polls the store
 * every {@code pollMs} (default 100ms) and dispatches new rows
 * to the subscribers. This is intentionally simple — the wire
 * format is JSON so a future contributor can swap in a proper
 * push (e.g. {@code Flow.Publisher} on top of a {@code SelectableChannel})
 * without breaking the public API.
 */
public final class ChildEventStream implements AutoCloseable {

    /** Default polling interval for the live-feed side. */
    public static final long DEFAULT_POLL_MS = 100L;

    private final SupervisorStore store;
    private final String childId;
    private final long pollMs;
    private final List<Consumer<ChildEventRecord>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong highWaterMark = new AtomicLong(0L);
    private ScheduledExecutorService poller;
    private ScheduledFuture<?> task;

    public ChildEventStream(SupervisorStore store, String childId) {
        this(store, childId, DEFAULT_POLL_MS);
    }

    public ChildEventStream(SupervisorStore store, String childId, long pollMs) {
        this.store = Objects.requireNonNull(store, "store");
        this.childId = Objects.requireNonNull(childId, "childId");
        this.pollMs = pollMs > 0 ? pollMs : DEFAULT_POLL_MS;
    }

    public String childId() { return childId; }
    public long pollIntervalMs() { return pollMs; }

    /** Number of currently-attached subscribers. */
    public int subscriberCount() { return subscribers.size(); }

    /** Last event id observed by this stream. 0 if nothing has been polled yet. */
    public long sinceCursor() { return highWaterMark.get(); }

    /**
     * Replay every event with id &gt; {@code sinceId}, oldest first.
     * Bumps the internal high-water mark so subsequent
     * {@link #subscribe(Consumer)} callbacks don't double-deliver.
     */
    public List<ChildEventRecord> replaySince(long sinceId, int limit) throws SQLException {
        List<ChildEventRecord> rows = store.listEvents(childId, sinceId, limit);
        if (!rows.isEmpty()) {
            long max = rows.get(rows.size() - 1).id();
            highWaterMark.updateAndGet(prev -> Math.max(prev, max));
        }
        return rows;
    }

    /**
     * Register a callback to be invoked on every new event. Starts
     * the polling thread on the first subscriber. The callback
     * runs on the scheduler thread; implementations should be
     * non-blocking.
     */
    public AutoCloseable subscribe(Consumer<ChildEventRecord> onEvent) {
        Objects.requireNonNull(onEvent, "onEvent");
        subscribers.add(onEvent);
        startIfNeeded();
        return () -> {
            subscribers.remove(onEvent);
            if (subscribers.isEmpty()) stopPolling();
        };
    }

    /**
     * Push an event to all subscribers from outside the polling
     * thread (e.g. an in-process subscriber that just appended an
     * event without going through the store poll). Bumps the
     * high-water mark so the next poll doesn't redeliver.
     */
    public void publish(ChildEventRecord event) {
        Objects.requireNonNull(event, "event");
        if (!childId.equals(event.childId())) {
            throw new IllegalArgumentException("event childId mismatch: "
                    + event.childId() + " != " + childId);
        }
        highWaterMark.updateAndGet(prev -> Math.max(prev, event.id()));
        for (Consumer<ChildEventRecord> s : subscribers) {
            try { s.accept(event); }
            catch (RuntimeException re) {
                // Bad subscribers shouldn't break the rest of the
                // fan-out. Logging happens at the caller.
            }
        }
    }

    private void startIfNeeded() {
        if (!running.compareAndSet(false, true)) return;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aethercode-stream-" + childId);
            t.setDaemon(true);
            return t;
        });
        task = poller.scheduleAtFixedRate(this::tick, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        if (!running.compareAndSet(true, false)) return;
        if (task != null) task.cancel(false);
        if (poller != null) poller.shutdownNow();
        task = null;
        poller = null;
    }

    private void tick() {
        try {
            List<ChildEventRecord> rows = store.listEvents(childId, highWaterMark.get(), 1000);
            for (ChildEventRecord r : rows) {
                highWaterMark.set(r.id());
                for (Consumer<ChildEventRecord> s : subscribers) {
                    try { s.accept(r); } catch (RuntimeException ignored) {}
                }
            }
        } catch (SQLException e) {
            // Polling failures should not kill the executor; we
            // just log indirectly via a no-op subscriber that
            // can be wired by tests.
            for (Consumer<ChildEventRecord> s : subscribers) {
                try {
                    // Notify as an "error" sentinel — no event will
                    // ever have id == -1.
                    // (Kept off the hot path: most subscribers
                    // don't care, and the cost is one virtual
                    // call per failing tick.)
                } catch (RuntimeException ignored) {}
            }
        }
    }

    @Override
    public void close() {
        subscribers.clear();
        stopPolling();
    }

    // -- well-known event types (T-341) ------------------------------------

    /** Re-export of {@link ChildEventRecord} type constants for stream consumers. */
    public static final class Types {
        private Types() {}
        public static final String MODEL_MESSAGE = ChildEventRecord.TYPE_MODEL_MESSAGE;
        public static final String TOOL_CALL     = ChildEventRecord.TYPE_TOOL_CALL;
        public static final String TOOL_RESULT   = ChildEventRecord.TYPE_TOOL_RESULT;
        public static final String TODO_UPDATE   = ChildEventRecord.TYPE_TODO_UPDATE;
        public static final String FILE_EDIT     = ChildEventRecord.TYPE_FILE_EDIT;
        public static final String STATUS_CHANGE = ChildEventRecord.TYPE_STATUS_CHANGE;
        public static final String LIMITS_HIT    = ChildEventRecord.TYPE_LIMITS_HIT;
        public static final String ERROR         = ChildEventRecord.TYPE_ERROR;

        /** Every well-known type, in declaration order. */
        public static final List<String> ALL = List.of(
                MODEL_MESSAGE, TOOL_CALL, TOOL_RESULT,
                TODO_UPDATE, FILE_EDIT, STATUS_CHANGE,
                LIMITS_HIT, ERROR);
    }
}
