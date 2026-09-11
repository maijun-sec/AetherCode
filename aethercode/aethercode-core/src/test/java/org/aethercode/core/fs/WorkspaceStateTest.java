package org.aethercode.core.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.aethercode.core.fs.WorkspaceState.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceStateTest {

    @Test
    void put_thenGet_returnsValue(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        s.put("k", "v");
        assertEquals("v", s.get("k", String.class).orElse(null));
    }

    @Test
    void get_missingKeyReturnsEmpty(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        assertFalse(s.get("missing").isPresent());
    }

    @Test
    void get_typedMismatchReturnsEmpty(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        s.put("k", "v");
        assertFalse(s.get("k", Integer.class).isPresent());
    }

    @Test
    void remove_deletes(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        s.put("k", "v");
        s.remove("k");
        assertFalse(s.get("k").isPresent());
    }

    @Test
    void clear_emptiesAll(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        s.put("a", "1");
        s.put("b", "2");
        s.clear();
        assertEquals(0, s.size());
        assertEquals(null, s.workspaceId());
    }

    @Test
    void save_writesToDisk(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("state.json");
        WorkspaceState s = new WorkspaceState(f);
        s.put("k", "v");
        s.setWorkspaceId("ws-1");
        Snapshot snap = s.save();
        assertTrue(Files.exists(f));
        assertEquals("ws-1", snap.workspaceId());
    }

    @Test
    void save_persistsLastSavedAt(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        Snapshot snap = s.save();
        assertNotNull(snap.lastSavedAt());
        assertEquals(snap.lastSavedAt(), s.lastSavedAt());
    }

    @Test
    void load_reopensSavedState(@TempDir Path tmp) {
        Path f = tmp.resolve("state.json");
        WorkspaceState s1 = new WorkspaceState(f);
        s1.put("k1", "v1");
        s1.put("k2", 42);
        s1.setWorkspaceId("ws-1");
        s1.save();

        WorkspaceState s2 = new WorkspaceState(f);
        assertEquals("v1", s2.get("k1", String.class).orElse(null));
        assertEquals(42, s2.get("k2", Integer.class).orElse(null));
        assertEquals("ws-1", s2.workspaceId());
    }

    @Test
    void load_missingFileIsEmpty(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("nope.json"));
        assertEquals(0, s.size());
    }

    @Test
    void put_rejectsNullKey(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        assertThrows(NullPointerException.class, () -> s.put(null, "v"));
    }

    @Test
    void put_rejectsNullValue(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        assertThrows(NullPointerException.class, () -> s.put("k", null));
    }

    @Test
    void put_overwritesExisting(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        s.put("k", "v1");
        s.put("k", "v2");
        assertEquals("v2", s.get("k", String.class).orElse(null));
    }

    @Test
    void size_tracksEntries(@TempDir Path tmp) {
        WorkspaceState s = new WorkspaceState(tmp.resolve("state.json"));
        s.put("a", "1");
        s.put("b", "2");
        assertEquals(2, s.size());
    }

    @Test
    void file_returnsConstructorPath(@TempDir Path tmp) {
        Path f = tmp.resolve("state.json");
        WorkspaceState s = new WorkspaceState(f);
        assertEquals(f, s.file());
    }

    @Test
    void corruptFile_backsUpAndStartsEmpty(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("state.json");
        Files.writeString(f, "not json {");
        WorkspaceState s = new WorkspaceState(f);
        assertEquals(0, s.size());
        assertTrue(Files.exists(f.resolveSibling("state.json.bak")));
    }

    @Test
    void load_emptyFileIsNoOp(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("state.json");
        Files.writeString(f, "");
        WorkspaceState s = new WorkspaceState(f);
        assertEquals(0, s.size());
    }

    @Test
    void save_createsParentDirectories(@TempDir Path tmp) {
        Path f = tmp.resolve("a/b/state.json");
        WorkspaceState s = new WorkspaceState(f);
        s.put("k", "v");
        s.save();
        assertTrue(Files.exists(f));
    }
}
