package org.aethercode.tasks.reconnect;

import org.aethercode.tasks.events.EventRingBuffer;
import org.aethercode.tasks.reconnect.ReattachHandshake.ReattachResult;
import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-P1-T06: re-attach handshake between the supervisor's
 * in-memory ring buffer and a reconnecting APP. The APP sends
 * the id of the last event it saw; we return the diff (or a
 * "full reload" signal if the ring doesn't have the diff).
 */
class ReattachHandshakeTest {

    private SupervisorStore store;
    private EventRingBuffer ring;
    private ReattachHandshake handshake;
    private String childId;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        ring = new EventRingBuffer();
        handshake = new ReattachHandshake(ring, store);
        childId = store.createChild("/tmp/work", "explain", null, null);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private static ChildEventRecord event(long id, String childId) {
        return new ChildEventRecord(id, childId, 1_700_000_000_000L + id,
                ChildEventRecord.TYPE_MODEL_MESSAGE, "{}");
    }

    @Test
    void attach_returnsDiffSinceLastSeq() throws Exception {
        long e1 = store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "{\"i\":1}");
        long e2 = store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_CALL,     "{\"i\":2}");
        long e3 = store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_RESULT,   "{\"i\":3}");
        // Feed the ring the same events so the handshake can serve a diff.
        ring.append(event(e1, childId));
        ring.append(event(e2, childId));
        ring.append(event(e3, childId));

        ReattachResult r = handshake.attach(childId, e1);
        assertEquals(childId, r.childId());
        assertEquals(e1, r.lastSeq());
        assertEquals(e3, r.lastEventId());
        assertTrue(r.found());
        assertTrue(r.child().isPresent());
        assertTrue(r.applyEvents());
        assertFalse(r.fullReload());
        assertEquals(2, r.events().size());
        assertEquals(e2, r.events().get(0).id());
        assertEquals(e3, r.events().get(1).id());
    }

    @Test
    void attach_emptyRingSuggestsFullReload() throws Exception {
        // The ring has nothing for this child (e.g. supervisor
        // restarted and the in-memory state was lost). The
        // handshake must NOT lie and return an empty diff —
        // it must suggest a full reload.
        ReattachResult r = handshake.attach(childId, 0L);
        assertTrue(r.found());
        assertFalse(r.applyEvents());
        assertTrue(r.fullReload());
        assertEquals(0L, r.lastEventId());
        assertTrue(r.events().isEmpty());
    }

    @Test
    void attach_unknownChildReturnsNotFound() throws Exception {
        ReattachResult r = handshake.attach("does-not-exist", 0L);
        assertFalse(r.found());
        assertFalse(r.applyEvents());
        assertFalse(r.fullReload());
        assertTrue(r.child().isEmpty());
        assertEquals("does-not-exist", r.childId());
    }

    @Test
    void attach_lastSeqAheadOfRingReturnsEmptyDiff() throws Exception {
        // Client claims it has seen event 100 but the ring
        // only has 1..10 (e.g. the child was resumed and the
        // ring was evicted). The handshake must return the
        // empty diff + the high-water mark so the client
        // knows what to re-request. It must NOT flag a full
        // reload — the client is ahead, not behind.
        for (long i = 1; i <= 10; i++) {
            ring.append(event(i, childId));
        }
        ReattachResult r = handshake.attach(childId, 100L);
        assertTrue(r.found());
        assertFalse(r.fullReload());
        assertFalse(r.applyEvents(),
                "applyEvents must be false so the client polls task/events instead");
        assertTrue(r.events().isEmpty());
        assertEquals(10L, r.lastEventId());
    }

    @Test
    void attach_diffLargerThanThresholdSuggestsFullReload() throws Exception {
        // 600 events; threshold is 500 — must suggest full reload.
        // Use a custom capacity so the ring actually keeps all 600.
        EventRingBuffer big = new EventRingBuffer(1000);
        ReattachHandshake hs = new ReattachHandshake(big, store);
        for (long i = 1; i <= 600; i++) {
            big.append(event(i, childId));
        }
        ReattachResult r = hs.attach(childId, 0L);
        assertTrue(r.found());
        assertEquals(600, r.events().size());
        assertTrue(r.fullReload(),
                "diff >= " + ReattachHandshake.FULL_RELOAD_THRESHOLD
                        + " must be flagged as full reload");
        assertEquals(600L, r.lastEventId());
    }

    @Test
    void attach_rejectsNegativeLastSeq() {
        assertThrows(IllegalArgumentException.class, () -> handshake.attach(childId, -1L));
    }
}
