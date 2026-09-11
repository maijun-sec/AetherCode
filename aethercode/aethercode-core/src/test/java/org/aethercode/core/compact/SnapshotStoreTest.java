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

class SnapshotStoreTest {

    @Test
    void save_persistsToDisk(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot snap = s.save("test", 100, "summary", List.of(Message.userText("hi")));
        assertNotNull(snap.id());
        assertTrue(Files.exists(tmp.resolve(snap.id() + ".json")));
    }

    @Test
    void get_returnsById(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot snap = s.save("a", 10, "sum", List.of());
        assertEquals(snap, s.get(snap.id()).orElse(null));
    }

    @Test
    void get_returnsEmptyForUnknown(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        assertFalse(s.get("missing").isPresent());
    }

    @Test
    void all_returnsAll(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.save("a", 1, "", List.of());
        s.save("b", 2, "", List.of());
        s.save("c", 3, "", List.of());
        assertEquals(3, s.all().size());
    }

    @Test
    void byLabel_filters(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.save("session-1", 1, "", List.of());
        s.save("session-2", 2, "", List.of());
        s.save("session-1", 3, "", List.of());
        assertEquals(2, s.byLabel("session-1").size());
        assertEquals(1, s.byLabel("session-2").size());
    }

    @Test
    void delete_removes(@TempDir Path tmp) throws Exception {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot snap = s.save("a", 1, "", List.of());
        assertTrue(s.delete(snap.id()));
        assertFalse(s.get(snap.id()).isPresent());
        assertFalse(Files.exists(tmp.resolve(snap.id() + ".json")));
    }

    @Test
    void delete_returnsFalseForUnknown(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        assertFalse(s.delete("missing"));
    }

    @Test
    void size_tracksCount(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.save("a", 1, "", List.of());
        s.save("b", 2, "", List.of());
        assertEquals(2, s.size());
    }

    @Test
    void clear_removesAll(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.save("a", 1, "", List.of());
        s.save("b", 2, "", List.of());
        s.clear();
        assertEquals(0, s.size());
    }

    @Test
    void reload_rebuildsFromDisk(@TempDir Path tmp) {
        SnapshotStore s1 = new SnapshotStore(tmp);
        s1.save("a", 1, "", List.of());
        s1.save("b", 2, "", List.of());
        SnapshotStore s2 = new SnapshotStore(tmp);
        s2.reload();
        assertEquals(2, s2.size());
    }

    @Test
    void totalBytes_tracksSize(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        s.save("a", 1, "x", List.of());
        assertTrue(s.totalBytes() > 0);
    }

    @Test
    void save_withNullMessagesStoresEmpty(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot snap = s.save("a", 5, "", null);
        assertEquals(0, snap.keptMessageCount());
        assertEquals(5, snap.originalMessageCount());
    }

    @Test
    void save_withNullLabelBecomesEmpty(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot snap = s.save(null, 0, "", List.of());
        assertEquals("", snap.label());
    }

    @Test
    void save_idIsGenerated(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Snapshot a = s.save("a", 0, "", List.of());
        Snapshot b = s.save("b", 0, "", List.of());
        assertNotNull(a.id());
        assertNotNull(b.id());
        assertFalse(a.id().equals(b.id()));
    }

    @Test
    void save_persistsMessages(@TempDir Path tmp) {
        SnapshotStore s = new SnapshotStore(tmp);
        Message m = Message.userText("hello");
        Snapshot snap = s.save("a", 1, "summary", List.of(m));
        SnapshotStore s2 = new SnapshotStore(tmp);
        Snapshot got = s2.get(snap.id()).orElseThrow();
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
        s.save("a", 1, "", List.of());
        s.clear();
        assertEquals(0, s.totalBytes());
    }

    @Test
    void corruptSnapshotFile_isSkipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("bad-id.json"), "not json {");
        SnapshotStore s = new SnapshotStore(tmp);
        s.reload();
        assertEquals(0, s.size());
    }
}
