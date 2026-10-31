/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.tasks.reconnect;

import org.aethercode.tasks.events.EventRingBuffer;
import org.aethercode.tasks.reconnect.ConnectionReplay.ReplayResult;
import org.aethercode.tasks.reconnect.ConnectionReplay.Source;
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
 * R-P1-T09: connection-loss replay. The orchestrator combines
 * the in-memory ring with a fallback to the durable store so
 * the client always gets a coherent transcript, even after a
 * JVM restart.
 */
class ConnectionReplayTest {

    private SupervisorStore store;
    private EventRingBuffer ring;
    private ConnectionReplay replay;
    private String childId;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        ring = new EventRingBuffer();
        replay = new ConnectionReplay(ring, store);
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
    void replay_servesFromRingWhenAvailable() throws Exception {
        long e1 = store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "1");
        long e2 = store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_CALL,     "2");
        long e3 = store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_RESULT,   "3");
        // Mirror the events into the ring (the production code path
        // does this in SupervisorService.appendEvent).
        ring.append(event(e1, childId));
        ring.append(event(e2, childId));
        ring.append(event(e3, childId));

        ReplayResult r = replay.replay(childId, e1);
        assertEquals(Source.RING, r.source());
        assertEquals(e3, r.lastEventId());
        assertEquals(2, r.events().size());
        assertEquals(e2, r.events().get(0).id());
        assertFalse(r.truncated());
    }

    @Test
    void replay_fallsBackToDurableStoreWhenRingEmpty() throws Exception {
        // Events only in the durable store (ring was evicted /
        // supervisor restarted). The replay must still return
        // the full diff.
        long e1 = store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "1");
        store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_CALL, "2");
        long e3 = store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_RESULT, "3");

        ReplayResult r = replay.replay(childId, e1);
        assertEquals(Source.DURABLE, r.source());
        assertEquals(2, r.events().size());
        assertEquals(e3, r.lastEventId());
    }

    @Test
    void replay_unknownChildReturnsEmptyWithUnknownSource() throws Exception {
        ReplayResult r = replay.replay("does-not-exist", 0L);
        assertEquals(Source.UNKNOWN, r.source());
        assertTrue(r.events().isEmpty());
        assertEquals(0L, r.lastEventId());
    }

    @Test
    void replay_capsAtMaxReplayEventsAndSetsTruncated() throws Exception {
        // Fill the durable store with more than MAX_REPLAY_EVENTS rows.
        for (long i = 1; i <= ConnectionReplay.MAX_REPLAY_EVENTS + 50; i++) {
            store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "{}");
        }
        ReplayResult r = replay.replay(childId, 0L);
        assertEquals(Source.DURABLE, r.source());
        assertEquals(ConnectionReplay.MAX_REPLAY_EVENTS, r.events().size());
        assertTrue(r.truncated(),
                "must flag truncation when the durable diff exceeds the cap");
    }

    @Test
    void replay_rejectsNegativeLastSeq() {
        assertThrows(IllegalArgumentException.class, () -> replay.replay(childId, -1L));
    }
}
