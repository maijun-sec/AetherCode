package org.aethercode.tasks.reconnect;

import org.aethercode.tasks.events.EventRingBuffer;
import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * R-P1-T06 (app-spec/tasks.md §1.1): the re-attach handshake
 * the supervisor runs when an APP reconnects. The wire protocol
 * is:
 *
 * <pre>
 *   client -> server:  task/attached { childId, lastSeq }
 *   server -> client:  { child, events, lastEventId, applyEvents, fullReload? }
 * </pre>
 *
 * <p>The handshake consults the in-memory
 * {@link EventRingBuffer} first because reading from it is a
 * few microseconds; if the client's {@code lastSeq} is older
 * than the ring's high-water mark, we fall through to the
 * durable {@link SupervisorStore} and the caller asks the
 * client to do a full reload ({@code task/show} then
 * {@code task/events} poll).
 *
 * <p>Thread-safety: stateless beyond the injected
 * dependencies; every call is independent. The ring and the
 * store are themselves thread-safe.
 */
public final class ReattachHandshake {

    private static final Logger LOG = LoggerFactory.getLogger(ReattachHandshake.class);

    /**
     * If the diff is at least this large we mark the response
     * as a {@code fullReload} suggestion. The cap is sized to
     * roughly the last 5 minutes of a high-throughput session
     * (LLM responses, tool calls, results). The actual full
     * reload itself is the caller's responsibility — the
     * handshake just signals the recommendation.
     */
    public static final int FULL_RELOAD_THRESHOLD = 500;

    private final EventRingBuffer ring;
    private final SupervisorStore store;

    public ReattachHandshake(EventRingBuffer ring, SupervisorStore store) {
        this.ring = Objects.requireNonNull(ring, "ring");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Compute the re-attach payload for {@code childId} starting
     * from {@code lastSeq}. If the ring has the diff in memory,
     * that's what's returned; otherwise we suggest a full
     * reload.
     *
     * @return a {@link ReattachResult} that always carries
     *         {@code childId} and the {@code lastEventId} we
     *         observed; {@code child} is empty if the id is
     *         unknown, {@code events} is the diff to apply.
     */
    public ReattachResult attach(String childId, long lastSeq) throws SQLException {
        Objects.requireNonNull(childId, "childId");
        if (lastSeq < 0) {
            throw new IllegalArgumentException("lastSeq must be >= 0, got " + lastSeq);
        }
        Optional<ChildRecord> childOpt = store.getChild(childId);
        if (childOpt.isEmpty()) {
            return ReattachResult.notFound(childId, lastSeq);
        }
        ChildRecord child = childOpt.get();
        long ringHigh = ring.highWaterMark(childId);
        // Case 1: ring is empty (lastSeq is 0 or the ring was
        // evicted for this child) -> suggest a full reload.
        if (ringHigh == 0L) {
            LOG.info("re-attach {}: ring empty, suggesting full reload", childId);
            return ReattachResult.fullReload(child, lastSeq, 0L);
        }
        // Case 2: ring's high-water mark is below the client's
        // lastSeq (the client is ahead of us; they should
        // drop and reconnect) -> return empty diff, no
        // full-reload recommendation.
        if (lastSeq > ringHigh) {
            return new ReattachResult(childId, lastSeq, ringHigh,
                    List.of(),    // no events
                    true,         // found
                    Optional.of(child),
                    false,        // applyEvents = false
                    false);       // fullReload = false
        }
        // Case 3: ring has a partial diff -> return it. Mark
        // fullReload if the diff is huge (the client is better
        // off reading the durable store directly).
        List<ChildEventRecord> diff = ring.since(childId, lastSeq);
        boolean fullReload = diff.size() >= FULL_RELOAD_THRESHOLD;
        if (fullReload) {
            LOG.info("re-attach {}: diff size {} >= threshold, full reload",
                    childId, diff.size());
        }
        return new ReattachResult(childId, lastSeq, ringHigh, diff,
                true, Optional.of(child), true, fullReload);
    }

    /**
     * Result of a {@link #attach(String, long)} call. The
     * fields map 1:1 to the {@code task/attached} response
     * shape in app-spec/design.md §3.1.
     *
     * @param childId       the session id
     * @param lastSeq       the cursor the client sent
     * @param lastEventId   the highest event id we have in the ring
     * @param events        the diff the client should apply (may be empty)
     * @param found         true if the child was found in the store
     * @param child         the current child record, or empty
     * @param applyEvents   true if the client should apply {@code events}
     * @param fullReload    true if we recommend a full reload from the
     *                      durable store (ring empty, or diff too large)
     */
    public record ReattachResult(
            String childId,
            long lastSeq,
            long lastEventId,
            List<ChildEventRecord> events,
            boolean found,
            Optional<ChildRecord> child,
            boolean applyEvents,
            boolean fullReload
    ) {
        static ReattachResult notFound(String childId, long lastSeq) {
            return new ReattachResult(childId, lastSeq, 0L, List.of(),
                    false, Optional.empty(), false, false);
        }

        static ReattachResult fullReload(ChildRecord child, long lastSeq, long lastEventId) {
            return new ReattachResult(child.id(), lastSeq, lastEventId, List.of(),
                    true, Optional.of(child), false, true);
        }
    }
}
