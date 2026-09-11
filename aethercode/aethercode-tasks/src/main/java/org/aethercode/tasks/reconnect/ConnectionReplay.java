package org.aethercode.tasks.reconnect;

import org.aethercode.tasks.events.EventRingBuffer;
import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * R-P1-T09 (app-spec/tasks.md §1.1): the connection-loss replay
 * path. When the APP's socket drops mid-session, the supervisor
 * keeps emitting events into both the durable
 * {@link SupervisorStore} and the in-memory
 * {@link EventRingBuffer}. On reconnect, the
 * {@link ReattachHandshake} computes the diff; this class
 * is the higher-level orchestrator that combines the ring's
 * diff with a fallback to the durable store when the ring is
 * empty (e.g. across a JVM restart that lost the in-memory
 * state).
 *
 * <p>Why two implementations of the same idea? The handshake
 * is the "ring only" fast path; replay is the same query but
 * with a guaranteed-correct fallback. The desktop / TUI
 * typically goes through {@code replay} so the user gets a
 * correct transcript even after a hard crash.
 */
public final class ConnectionReplay {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionReplay.class);

    /** Hard cap on the replay payload size. */
    public static final int MAX_REPLAY_EVENTS = 1000;

    private final EventRingBuffer ring;
    private final SupervisorStore store;

    public ConnectionReplay(EventRingBuffer ring, SupervisorStore store) {
        this.ring = Objects.requireNonNull(ring, "ring");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Compute the replay payload for {@code childId} from
     * {@code lastSeq}. The result is sourced from the ring if
     * possible; if the ring has nothing for this child (ring
     * was evicted, or the supervisor restarted and lost the
     * in-memory state) we fall back to the durable store.
     *
     * <p>The {@code events} list is capped at
     * {@link #MAX_REPLAY_EVENTS}; older events are dropped
     * from the front so the client always sees the most
     * recent activity (matches the ring's eviction policy).
     */
    public ReplayResult replay(String childId, long lastSeq) throws SQLException {
        Objects.requireNonNull(childId, "childId");
        if (lastSeq < 0) {
            throw new IllegalArgumentException("lastSeq must be >= 0, got " + lastSeq);
        }
        Optional<ChildRecord> childOpt = store.getChild(childId);
        if (childOpt.isEmpty()) {
            return new ReplayResult(childId, lastSeq, 0L, List.of(),
                    Source.UNKNOWN, false);
        }
        long ringHigh = ring.highWaterMark(childId);
        if (ringHigh > 0L && lastSeq <= ringHigh) {
            List<ChildEventRecord> diff = ring.since(childId, lastSeq);
            diff = cap(diff, MAX_REPLAY_EVENTS);
            return new ReplayResult(childId, lastSeq, ringHigh, diff,
                    Source.RING, false);
        }
        // Fallback to the durable store. We read since lastSeq
        // (or since 0 if the client is asking for a cold
        // attach). The store is the source of truth so we
        // can satisfy any cursor.
        List<ChildEventRecord> rows = store.listEvents(childId, lastSeq, MAX_REPLAY_EVENTS);
        long maxId = lastSeq;
        for (ChildEventRecord e : rows) {
            if (e.id() > maxId) maxId = e.id();
        }
        if (rows.isEmpty() && lastSeq > 0) {
            // The client is ahead of the durable store too;
            // tell it the high-water mark so it can stop polling.
            // We don't have it cheaply (would require a count);
            // use 0 as a "you're done" signal — the client will
            // re-issue replay with lastSeq=0 if it wants more.
            LOG.info("replay {}: client ahead of durable store (lastSeq={})",
                    childId, lastSeq);
        }
        return new ReplayResult(childId, lastSeq, maxId, rows,
                Source.DURABLE, rows.size() >= MAX_REPLAY_EVENTS);
    }

    private static List<ChildEventRecord> cap(List<ChildEventRecord> events, int cap) {
        if (events.size() <= cap) return events;
        return new ArrayList<>(events.subList(events.size() - cap, events.size()));
    }

    /** Where the events came from. */
    public enum Source {
        RING,
        DURABLE,
        UNKNOWN
    }

    /**
     * The replay payload. The shape is identical to
     * {@code task/attached} in design.md §3.1; the
     * {@code source} field is included for diagnostics.
     */
    public record ReplayResult(
            String childId,
            long lastSeq,
            long lastEventId,
            List<ChildEventRecord> events,
            Source source,
            boolean truncated
    ) {}
}
