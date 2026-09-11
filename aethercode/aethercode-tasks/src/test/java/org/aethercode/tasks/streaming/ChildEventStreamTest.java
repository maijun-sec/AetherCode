package org.aethercode.tasks.streaming;

import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChildEventStreamTest {

    private SupervisorStore store;
    private String childId;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        childId = store.createChild("/tmp", "test", null, null);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    // -- T-341: well-known event types -------------------------------------

    @Test
    void types_includesAllExpectedConstants() {
        List<String> all = ChildEventStream.Types.ALL;
        assertTrue(all.contains(ChildEventStream.Types.MODEL_MESSAGE));
        assertTrue(all.contains(ChildEventStream.Types.TOOL_CALL));
        assertTrue(all.contains(ChildEventStream.Types.TOOL_RESULT));
        assertTrue(all.contains(ChildEventStream.Types.TODO_UPDATE));
        assertTrue(all.contains(ChildEventStream.Types.FILE_EDIT));
        assertTrue(all.contains(ChildEventStream.Types.STATUS_CHANGE));
        assertTrue(all.contains(ChildEventStream.Types.LIMITS_HIT));
        assertTrue(all.contains(ChildEventStream.Types.ERROR));
    }

    @Test
    void types_matchRecordConstants() {
        assertEquals(ChildEventRecord.TYPE_MODEL_MESSAGE, ChildEventStream.Types.MODEL_MESSAGE);
        assertEquals(ChildEventRecord.TYPE_TOOL_CALL,     ChildEventStream.Types.TOOL_CALL);
        assertEquals(ChildEventRecord.TYPE_TOOL_RESULT,   ChildEventStream.Types.TOOL_RESULT);
        assertEquals(ChildEventRecord.TYPE_TODO_UPDATE,   ChildEventStream.Types.TODO_UPDATE);
        assertEquals(ChildEventRecord.TYPE_FILE_EDIT,     ChildEventStream.Types.FILE_EDIT);
        assertEquals(ChildEventRecord.TYPE_STATUS_CHANGE, ChildEventStream.Types.STATUS_CHANGE);
        assertEquals(ChildEventRecord.TYPE_LIMITS_HIT,    ChildEventStream.Types.LIMITS_HIT);
        assertEquals(ChildEventRecord.TYPE_ERROR,         ChildEventStream.Types.ERROR);
    }

    // -- T-342: replay (since=id) ------------------------------------------

    @Test
    void replaySince_returnsAllEventsWhenSinceIsZero() throws Exception {
        store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "{\"i\":1}");
        store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_CALL, "{\"i\":2}");
        store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_RESULT, "{\"i\":3}");

        try (ChildEventStream s = new ChildEventStream(store, childId)) {
            List<ChildEventRecord> rows = s.replaySince(0L, 100);
            assertEquals(3, rows.size());
            assertEquals(1, rows.get(0).id());
            assertEquals(3, rows.get(2).id());
        }
    }

    @Test
    void replaySince_filtersBySinceId() throws Exception {
        long e1 = store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "{\"i\":1}");
        store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_CALL, "{\"i\":2}");
        store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_RESULT, "{\"i\":3}");

        try (ChildEventStream s = new ChildEventStream(store, childId)) {
            List<ChildEventRecord> rows = s.replaySince(e1, 100);
            assertEquals(2, rows.size());
            assertEquals("tool_call", rows.get(0).type());
            assertEquals("tool_result", rows.get(1).type());
        }
    }

    @Test
    void replaySince_capsAtLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "{\"i\":" + i + "}");
        }
        try (ChildEventStream s = new ChildEventStream(store, childId)) {
            List<ChildEventRecord> rows = s.replaySince(0L, 5);
            assertEquals(5, rows.size());
        }
    }

    @Test
    void replaySince_bumpsHighWaterMark() throws Exception {
        store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "{}");
        store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_CALL, "{}");
        try (ChildEventStream s = new ChildEventStream(store, childId)) {
            assertEquals(0L, s.sinceCursor());
            s.replaySince(0L, 100);
            assertEquals(2L, s.sinceCursor());
        }
    }

    // -- live subscription -------------------------------------------------

    @Test
    void subscribe_deliversFutureEvents() throws Exception {
        try (ChildEventStream s = new ChildEventStream(store, childId, 25L)) {
            CopyOnWriteArrayList<ChildEventRecord> received = new CopyOnWriteArrayList<>();
            AutoCloseable sub = s.subscribe(received::add);
            store.appendEvent(childId, ChildEventRecord.TYPE_MODEL_MESSAGE, "{\"hello\":1}");
            // Wait up to 2s for the poller to deliver.
            long deadline = System.currentTimeMillis() + 2000L;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(1, received.size());
            assertEquals("model_message", received.get(0).type());
            sub.close();
            assertEquals(0, s.subscriberCount());
        }
    }

    @Test
    void subscribe_multipleSubscribers_eachGetEvent() throws Exception {
        try (ChildEventStream s = new ChildEventStream(store, childId, 25L)) {
            CopyOnWriteArrayList<ChildEventRecord> a = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<ChildEventRecord> b = new CopyOnWriteArrayList<>();
            AutoCloseable sa = s.subscribe(a::add);
            AutoCloseable sb = s.subscribe(b::add);
            assertEquals(2, s.subscriberCount());
            store.appendEvent(childId, ChildEventRecord.TYPE_TOOL_CALL, "{\"i\":1}");
            long deadline = System.currentTimeMillis() + 2000L;
            while ((a.isEmpty() || b.isEmpty()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(1, a.size());
            assertEquals(1, b.size());
            sa.close(); sb.close();
        }
    }

    @Test
    void publish_synchronousFanOut() throws Exception {
        try (ChildEventStream s = new ChildEventStream(store, childId)) {
            CopyOnWriteArrayList<ChildEventRecord> got = new CopyOnWriteArrayList<>();
            s.subscribe(got::add);
            ChildEventRecord e = new ChildEventRecord(99L, childId,
                    System.currentTimeMillis(), "x", "{}");
            s.publish(e);
            assertEquals(1, got.size());
            assertEquals(99L, s.sinceCursor());
        }
    }

    @Test
    void close_isIdempotent() {
        ChildEventStream s = new ChildEventStream(store, childId);
        s.close();
        s.close();
    }
}
