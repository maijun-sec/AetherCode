package org.aethercode.tasks.events;

import org.aethercode.tasks.supervisor.ChildEventRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * R-P1-T05 (app-spec/tasks.md §1.1): an in-memory ring buffer
 * that keeps the last {@value #DEFAULT_CAPACITY} events for every
 * supervised child. The buffer is the source of truth for
 * re-attach handshakes: when an APP reconnects to the supervisor
 * it sends the id of the last event it saw, and the supervisor
 * diffs against this buffer to compute the catch-up.
 *
 * <p>Design notes:
 * <ul>
 *   <li>One ring per {@code childId}; the outer map is a
 *       {@link ConcurrentHashMap} so new children are added
 *       without coordinating with readers.</li>
 *   <li>The inner ring is a single-lock fixed-size {@link ArrayDeque}.
 *       Operations are O(1) amortised; the lock window is
 *       sub-microsecond, so the contention cost is negligible
 *       compared to the SQLite append on the durable side.</li>
 *   <li>Oldest events are dropped on overflow; the deque never
 *       grows past {@link #capacity(int) capacity}.</li>
 *   <li>Iteration is done under the ring lock, returning an
 *       {@link ArrayList} snapshot — callers may then iterate
 *       freely without holding any monitor.</li>
 * </ul>
 *
 * <p>Thread-safety: every public method is safe to call from any
 * thread. The outer map protects against losing updates; the
 * inner lock serialises the per-session ring.
 */
public final class EventRingBuffer {

    /** Default ring depth per session. */
    public static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    private final Map<String, Ring> rings = new ConcurrentHashMap<>();

    public EventRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public EventRingBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
    }

    /** Maximum number of events stored per session. */
    public int capacity() { return capacity; }

    /**
     * Append an event to the session's ring. Creates the ring
     * on first use. On overflow, the oldest event is evicted
     * from the head; the new event sits at the tail.
     */
    public void append(ChildEventRecord event) {
        Objects.requireNonNull(event, "event");
        Ring ring = rings.computeIfAbsent(event.childId(), id -> new Ring(capacity));
        ring.append(event);
    }

    /**
     * Snapshot the session's full ring, oldest first. Returns
     * an empty list if no events have been recorded for the
     * session.
     */
    public List<ChildEventRecord> snapshot(String childId) {
        Objects.requireNonNull(childId, "childId");
        Ring ring = rings.get(childId);
        return ring == null ? List.of() : ring.snapshot();
    }

    /**
     * All events with id &gt; {@code sinceId}, oldest first. Used
     * by the re-attach handshake to compute the diff the client
     * missed while it was disconnected.
     */
    public List<ChildEventRecord> since(String childId, long sinceId) {
        Objects.requireNonNull(childId, "childId");
        Ring ring = rings.get(childId);
        return ring == null ? List.of() : ring.since(sinceId);
    }

    /**
     * Highest event id currently stored for the session, or
     * {@code 0} if the ring is empty / unknown. The re-attach
     * protocol uses this as the new {@code lastEventId} cursor
     * so the client's next follow-up poll can pick up from here.
     */
    public long highWaterMark(String childId) {
        Objects.requireNonNull(childId, "childId");
        Ring ring = rings.get(childId);
        return ring == null ? 0L : ring.highWaterMark();
    }

    /**
     * Remove the session's ring. Called on a child reaching a
     * terminal state so the buffer doesn't grow unbounded across
     * a long-running supervisor. No-op if the session is unknown.
     */
    public void evict(String childId) {
        Objects.requireNonNull(childId, "childId");
        rings.remove(childId);
    }

    /** Number of sessions currently tracked. */
    public int sessionCount() { return rings.size(); }

    /** Total number of events buffered across all sessions. */
    public long totalSize() {
        long sum = 0L;
        for (Ring r : rings.values()) sum += r.size();
        return sum;
    }

    /** Clear every ring. Intended for tests; never call in production. */
    public void clear() { rings.clear(); }

    // ------------------------------------------------------------------
    // Per-session ring
    // ------------------------------------------------------------------

    /**
     * A single fixed-size ring. The implementation is a plain
     * {@link ArrayDeque} guarded by a single intrinsic lock; the
     * duration of the critical section is bounded (a constant
     * number of deque ops) so the lock is not a bottleneck.
     */
    static final class Ring {
        private final int capacity;
        private final Deque<ChildEventRecord> deque;
        private long highWaterMark = 0L;

        Ring(int capacity) {
            this.capacity = capacity;
            this.deque = new ArrayDeque<>(capacity);
        }

        synchronized void append(ChildEventRecord event) {
            if (deque.size() >= capacity) {
                deque.pollFirst();
            }
            deque.offerLast(event);
            if (event.id() > highWaterMark) {
                highWaterMark = event.id();
            }
        }

        synchronized List<ChildEventRecord> snapshot() {
            return new ArrayList<>(deque);
        }

        synchronized List<ChildEventRecord> since(long sinceId) {
            if (deque.isEmpty()) return List.of();
            List<ChildEventRecord> out = new ArrayList<>();
            for (ChildEventRecord e : deque) {
                if (e.id() > sinceId) out.add(e);
            }
            return out;
        }

        synchronized long highWaterMark() { return highWaterMark; }

        synchronized int size() { return deque.size(); }
    }
}
