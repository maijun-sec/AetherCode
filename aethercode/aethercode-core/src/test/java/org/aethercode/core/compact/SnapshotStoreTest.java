package org.aethercode.core.compact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.aethercode.core.compact.SnapshotStore.Snapshot;
import org.aethercode.core.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R284: tests for the sessionId-indexed snapshot store. The
 * previous label/UUID interface was reshaped because the UI now
 * wants "snapshots for session X, compaction index N" — the new
 * shape returns monotonic-per-session indices that line up with
 * the summary message metadata QueryEngine attaches to a
 * compaction event.
 */
class SnapshotStoreTest {

    private static final String SESSION = "2026-09-18T10-00-00Z_abc12345";

    @Test
    void saveForSession_persistsToDisk(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot snap = s.saveForSession(SESSION, 100, "summary",
                List.of(Message.userText("hi")));
        assertEquals(SESSION, snap.sessionId());
        assertEquals(0, snap.compactionIndex());
        assertTrue(Files.exists(tmp.resolve(snap.fileName())));
    }

    @Test
    void saveForSession_assignsMonotonicIndex(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot a = s.saveForSession(SESSION, 1, "sum1", List.of());
        Snapshot b = s.saveForSession(SESSION, 2, "sum2", List.of());
        Snapshot c = s.saveForSession(SESSION, 3, "sum3", List.of());
        assertEquals(0, a.compactionIndex());
        assertEquals(1, b.compactionIndex());
        assertEquals(2, c.compactionIndex());
    }

    @Test
    void saveForSession_separateSessionsGetTheirOwnSequence(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot a1 = s.saveForSession("s1", 1, "a", List.of());
        Snapshot b1 = s.saveForSession("s2", 1, "b", List.of());
        Snapshot a2 = s.saveForSession("s1", 2, "a2", List.of());
        Snapshot b2 = s.saveForSession("s2", 2, "b2", List.of());
        assertEquals(0, a1.compactionIndex());
        assertEquals(0, b1.compactionIndex());
        assertEquals(1, a2.compactionIndex());
        assertEquals(1, b2.compactionIndex());
    }

    @Test
    void bySession_returnsInOrder(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.saveForSession(SESSION, 1, "first", List.of());
        s.saveForSession(SESSION, 2, "second", List.of());
        s.saveForSession(SESSION, 3, "third", List.of());
        List<Snapshot> all = s.bySession(SESSION);
        assertEquals(3, all.size());
        assertEquals(0, all.get(0).compactionIndex());
        assertEquals("first", all.get(0).summary());
        assertEquals(1, all.get(1).compactionIndex());
        assertEquals(2, all.get(2).compactionIndex());
    }

    @Test
    void bySession_returnsEmptyForUnknown(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        assertEquals(0, s.bySession("never-seen").size());
    }

    @Test
    void getByIndex_returnsMatchingSnapshot(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.saveForSession(SESSION, 1, "first", List.of());
        s.saveForSession(SESSION, 2, "second", List.of());
        Snapshot got = s.getByIndex(SESSION, 1).orElseThrow();
        assertEquals("second", got.summary());
        assertEquals(2, got.originalMessageCount());
    }

    @Test
    void getByIndex_returnsEmptyForOutOfRange(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.saveForSession(SESSION, 1, "first", List.of());
        assertFalse(s.getByIndex(SESSION, 99).isPresent());
    }

    @Test
    void delete_removesSnapshotAndCompactsIndex(@TempDir Path tmp)
            throws IOException {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot a = s.saveForSession(SESSION, 1, "first", List.of());
        Snapshot b = s.saveForSession(SESSION, 2, "second", List.of());
        assertTrue(s.delete(SESSION, 0));
        assertFalse(s.getByIndex(SESSION, 0).isPresent());
        assertFalse(Files.exists(tmp.resolve(a.fileName())));
        // b's file is intact
        assertTrue(Files.exists(tmp.resolve(b.fileName())));
        assertTrue(s.getByIndex(SESSION, 1).isPresent());
    }

    @Test
    void delete_returnsFalseForUnknownSession(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        assertFalse(s.delete("unknown", 0));
    }

    @Test
    void listSessions_returnsEverySessionWithASnapshot(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.saveForSession("s1", 1, "a", List.of());
        s.saveForSession("s2", 1, "b", List.of());
        s.saveForSession("s1", 2, "a2", List.of());
        List<String> sessions = s.listSessions();
        assertEquals(2, sessions.size());
        assertTrue(sessions.contains("s1"));
        assertTrue(sessions.contains("s2"));
    }

    @Test
    void size_tracksCount(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.saveForSession("s1", 1, "a", List.of());
        s.saveForSession("s2", 1, "b", List.of());
        assertEquals(2, s.size());
    }

    @Test
    void clear_removesAll(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.saveForSession("s1", 1, "a", List.of());
        s.saveForSession("s2", 1, "b", List.of());
        s.clear();
        assertEquals(0, s.size());
        assertEquals(0, s.bySession("s1").size());
    }

    @Test
    void reload_rebuildsFromDisk(@TempDir Path tmp) {
        SnapshotStore s1 = new SnapshotStore(tmp);
        s1.saveForSession(SESSION, 1, "first", List.of());
        s1.saveForSession(SESSION, 2, "second", List.of());
        SnapshotStore s2 = new SnapshotStore(tmp);
        s2.reload();
        assertEquals(2, s2.size());
        assertEquals("first", s2.bySession(SESSION).get(0).summary());
    }

    @Test
    void reload_restoresMonotonicIndices(@TempDir Path tmp) {
        // Edge case: process crashed after writing the JSON
        // file but before the in-memory index update. The
        // next saveForSession should still pick the right
        // next index (N+1) rather than reusing N.
        SnapshotStore s1 = new SnapshotStore(tmp);
        s1.saveForSession(SESSION, 1, "a", List.of());
        s1.saveForSession(SESSION, 2, "b", List.of());
        s1.saveForSession(SESSION, 3, "c", List.of());
        SnapshotStore s2 = new SnapshotStore(tmp);
        s2.reload();
        Snapshot next = s2.saveForSession(SESSION, 4, "d", List.of());
        assertEquals(3, next.compactionIndex(),
                "next index must continue from the highest seen");
    }

    @Test
    void totalBytes_tracksSize(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.saveForSession(SESSION, 1, "x", List.of());
        assertTrue(s.totalBytes() > 0);
    }

    @Test
    void saveForSession_nullMessagesStoresEmpty(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot snap = s.saveForSession(SESSION, 5, "", null);
        assertEquals(0, snap.keptMessageCount());
        assertEquals(5, snap.originalMessageCount());
    }

    @Test
    void saveForSession_persistsMessages(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Message m = Message.userText("hello");
        Snapshot snap = s.saveForSession(SESSION, 1, "summary", List.of(m));
        SnapshotStore s2 = new SnapshotStore(tmp);
        Snapshot got = s2.bySession(SESSION).get(0);
        assertEquals(1, got.messages().size());
        assertEquals("hello", got.messages().get(0).textContent());
    }

    @Test
    void dir_returnsConstructorPath(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        assertEquals(tmp, s.dir());
    }

    @Test
    void clear_resetsTotalBytes(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.saveForSession(SESSION, 1, "x", List.of());
        s.clear();
        assertEquals(0, s.totalBytes());
    }

    @Test
    void corruptSnapshotFile_isSkipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("not-a-snapshot__0.json"), "not json {");
        SnapshotStore s = new SnapshotStore(tmp);
        s.reload();
        assertEquals(0, s.size());
    }

    @Test
    void snapshot_filenameFollowsConvention(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot snap = s.saveForSession(SESSION, 1, "summary", List.of());
        assertEquals(SESSION + "__0.json", snap.fileName());
    }
}