package org.aethercode.tasks.events;

import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-P1-T05: the supervisor's in-memory re-attach buffer.
 *
 * <p>The ring has a fixed capacity (1000 by default) and evicts
 * the oldest entry on overflow. The re-attach handshake uses
 * {@link EventRingBuffer#since(String, long)} to compute the
 * diff the client missed while disconnected.
 */
class EventRingBufferTest {

    private static ChildEventRecord event(long id, String childId, String type) {
        return new ChildEventRecord(id, childId, 1_700_000_000_000L + id, type, "{}");
    }

    @Test
    void append_storesEventInOrder() {
        EventRingBuffer buf = new EventRingBuffer();
        buf.append(event(1, "c1", "model_message"));
        buf.append(event(2, "c1", "tool_call"));
        buf.append(event(3, "c1", "tool_result"));
        List<ChildEventRecord> snap = buf.snapshot("c1");
        assertEquals(3, snap.size());
        assertEquals(1L, snap.get(0).id());
        assertEquals(2L, snap.get(1).id());
        assertEquals(3L, snap.get(2).id());
    }

    @Test
    void append_dropsOldestOnOverflow() {
        // Small capacity so we can prove the eviction in a single test.
        EventRingBuffer buf = new EventRingBuffer(3);
        buf.append(event(1, "c1", "a"));
        buf.append(event(2, "c1", "b"));
        buf.append(event(3, "c1", "c"));
        buf.append(event(4, "c1", "d")); // evicts id=1
        buf.append(event(5, "c1", "e")); // evicts id=2
        List<ChildEventRecord> snap = buf.snapshot("c1");
        assertEquals(3, snap.size());
        assertEquals(3L, snap.get(0).id());
        assertEquals(4L, snap.get(1).id());
        assertEquals(5L, snap.get(2).id());
    }

    @Test
    void since_returnsOnlyEventsAboveTheCursor() {
        EventRingBuffer buf = new EventRingBuffer(10);
        for (long i = 1; i <= 5; i++) {
            buf.append(event(i, "c1", "x"));
        }
        List<ChildEventRecord> diff = buf.since("c1", 2L);
        assertEquals(3, diff.size());
        assertEquals(3L, diff.get(0).id());
        assertEquals(4L, diff.get(1).id());
        assertEquals(5L, diff.get(2).id());
    }

    @Test
    void highWaterMark_tracksMaxEventIdPerSession() {
        EventRingBuffer buf = new EventRingBuffer(5);
        // Out-of-order inserts (the supervisor's appendEvent can
        // technically happen in any order across reconnects; the
        // high-water mark must always be the max seen).
        buf.append(event(7, "c1", "x"));
        buf.append(event(3, "c1", "x"));
        buf.append(event(5, "c1", "x"));
        assertEquals(7L, buf.highWaterMark("c1"));
    }

    @Test
    void sessionsAreIsolated() {
        EventRingBuffer buf = new EventRingBuffer(5);
        buf.append(event(1, "c1", "a"));
        buf.append(event(1, "c2", "b"));
        buf.append(event(2, "c1", "c"));
        List<ChildEventRecord> c1 = buf.snapshot("c1");
        List<ChildEventRecord> c2 = buf.snapshot("c2");
        assertEquals(2, c1.size());
        assertEquals(1, c2.size());
        assertEquals("a", c1.get(0).type());
        assertEquals("b", c2.get(0).type());
        // High-water marks are independent.
        assertEquals(2L, buf.highWaterMark("c1"));
        assertEquals(1L, buf.highWaterMark("c2"));
        assertEquals(2, buf.sessionCount());
    }
}
