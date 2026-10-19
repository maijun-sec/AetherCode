package org.aethercode.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R728: complete the R280 migration. Pre-R728,
 * {@link LayeredMemoryStore#appendProjectChange} wrote to the
 * legacy {@code FileBackedMemory} ({@code MEMORY.md}) while
 * {@code readProjectMemory} read from the R280
 * {@code ProjectMemoryStore} ({@code PROJECT_MEMORY.md}). The
 * two were disjoint (R724 caught the read-split bug and merged
 * them in {@code readProjectMemory} via "## Legacy change
 * log (R127, pre-R280)"), but the underlying write target was
 * still wrong. R728 closes the migration by routing
 * {@code appendProjectChange} to {@link ProjectMemoryStore}
 * directly.
 *
 * <p>These tests pin:
 *
 * <ul>
 *   <li>writes go to {@code PROJECT_MEMORY.md} (canonical
 *       R280 store)</li>
 *   <li>writes do NOT land in the legacy {@code MEMORY.md}
 *       file any more</li>
 *   <li>read sees both new (R728) entries and any legacy
 *       (pre-R728) entries that happen to still be on disk</li>
 *   <li>the {@link FileBackedMemory.MemoryItem} returned by the
 *       legacy method keeps its RPC contract (id + content +
 *       timestamps)</li>
 * </ul>
 */
class LayeredMemoryStoreR728Test {

    @TempDir
    Path tmp;

    /** Each test owns its own SessionMemoryStore which holds an
     *  open SQLite handle. The handle must be closed before the
     *  {@code @TempDir} teardown deletes the directory, otherwise
     *  Windows' temp-dir cleanup throws {@code IOException:
     *  Failed to delete temp directory} (the file is still
     *  locked). We close explicitly in {@link #closeSession()}. */
    private SessionMemoryStore currentSess;
    private LayeredMemoryStore currentStore;

    private LayeredMemoryStore newStore(String agentType) throws Exception {
        closeSession();
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(null);
        currentSess = new SessionMemoryStore(tmp.resolve("sessions.db"));
        currentStore = new LayeredMemoryStore(
                tmp.resolve("mem"), agentType,
                currentSess,
                compressor,
                /*projectCompressThreshold*/ 100,
                /*keepRecent*/ 5,
                /*autoCompress*/ false);
        return currentStore;
    }

    @AfterEach
    void cleanup() throws Exception {
        closeSession();
    }

    private void closeSession() {
        if (currentSess != null) {
            try { currentSess.close(); } catch (Exception ignored) {}
            currentSess = null;
            currentStore = null;
        }
    }

    /** Resolve the path of the project's PROJECT_MEMORY.md file
     *  produced by LayeredMemoryStore for {@code cwd}. */
    private Path projectMemoryFile(LayeredMemoryStore s, String cwd) {
        Path p = s.projectMemoryStore(cwd).file();
        return p;
    }

    /** Path of the legacy MEMORY.md file. Used to assert that
     *  R728 writes do NOT touch it. */
    private Path legacyMemoryFile(LayeredMemoryStore s, String cwd) {
        // Same dir as PROJECT_MEMORY.md; the legacy FileBackedMemory
        // entry-point is "MEMORY.md" inside MemoryPaths.entrypoint.
        ProjectMemoryStore pm = s.projectMemoryStore(cwd);
        return pm.file().getParent().resolve("MEMORY.md");
    }

    @Test
    void appendProjectChange_writesTo_PROJECT_MEMORY_md() throws Exception {
        LayeredMemoryStore s = newStore("r728-test-agent");
        String cwd = tmp.toString();

        s.appendProjectChange(cwd, "add 3 file_writes to src/foo.py");

        Path pmFile = projectMemoryFile(s, cwd);
        assertTrue(Files.exists(pmFile),
                "PROJECT_MEMORY.md must be created on first appendProjectChange: " + pmFile);
        String body = Files.readString(pmFile);
        assertTrue(body.contains("add 3 file_writes to src/foo.py"),
                "PROJECT_MEMORY.md must contain the appended content: " + body);
        // R280 ProjectMemoryStore uses [sessionId iso] format.
        assertTrue(body.contains("legacy"),
                "PROJECT_MEMORY.md entry must be tagged with the legacy sessionId: " + body);
    }

    @Test
    void appendProjectChange_doesNotWriteTo_legacy_MEMORY_md() throws Exception {
        LayeredMemoryStore s = newStore("r728-test-agent");
        String cwd = tmp.toString();

        s.appendProjectChange(cwd, "new write");

        Path legacy = legacyMemoryFile(s, cwd);
        if (Files.exists(legacy)) {
            String body = Files.readString(legacy);
            assertFalse(body.contains("new write"),
                    "R728 must NOT write to legacy MEMORY.md: " + body);
        }
        // If the file doesn't exist at all, that's even better — assertion
        // above is the meaningful one.
    }

    @Test
    void appendProjectChange_returnsMemoryItem_withStableShape() throws Exception {
        LayeredMemoryStore s = newStore("r728-test-agent");
        String cwd = tmp.toString();

        FileBackedMemory.MemoryItem item = s.appendProjectChange(cwd, "shape test");
        assertNotNull(item, "appendProjectChange must not return null");

        assertNotNull(item.id(), "MemoryItem.id() must be populated for RPC contract");
        assertTrue(item.id().length() > 0, "MemoryItem.id() must be non-empty");

        assertNotNull(item.content(), "MemoryItem.content() must be populated");
        assertTrue(item.content().contains("shape test"),
                "MemoryItem.content() must round-trip the original description: " + item.content());

        assertEquals("project-change", item.scope(),
                "MemoryItem.scope() must stay 'project-change' for the RPC contract");

        assertNotNull(item.createdAt(),
                "MemoryItem.createdAt() must be populated");
    }

    @Test
    void appendProjectChange_then_readProjectMemory_includesAllContent() throws Exception {
        LayeredMemoryStore s = newStore("r728-test-agent");
        String cwd = tmp.toString();

        s.appendProjectChange(cwd, "post-R728 entry");
        s.appendProjectChange(cwd, "another post-R728 entry");

        String read = s.readProjectMemory(cwd);
        assertNotNull(read);
        assertTrue(read.contains("post-R728 entry"),
                "readProjectMemory must include the new (R728) entries: " + read);
        assertTrue(read.contains("another post-R728 entry"),
                "readProjectMemory must include every new entry: " + read);
    }

    @Test
    void readProjectMemory_merges_legacyEntries_withNewOnes() throws Exception {
        // Seed a LEGACY FileBackedMemory entry (simulating
        // pre-R728 content that landed in MEMORY.md via the old
        // code path), then write a new entry through the
        // migrated entry-point. The read path must surface BOTH.
        LayeredMemoryStore s = newStore("r728-test-agent");
        String cwd = tmp.toString();

        // Seed a legacy entry on the FileBackedMemory side. This
        // represents content written by pre-R728 sessions; the
        // R724 "Legacy change log" merge section is supposed to
        // pull these into readProjectMemory so the model still
        // sees them.
        FileBackedMemory legacyStore = s.publicProjectStore(cwd);
        FileBackedMemory.MemoryItem legacyItem = legacyStore.add(
                "[2026-01-01T00:00:00Z] legacy pre-R728 entry",
                "project-change",
                List.of());

        // Now write a NEW entry through the migrated entry-point.
        s.appendProjectChange(cwd, "new post-R728 entry");

        String read = s.readProjectMemory(cwd);
        assertNotNull(read);
        assertTrue(read.contains("new post-R728 entry"),
                "new R728 entry must be visible: " + read);
        assertTrue(read.contains("legacy pre-R728 entry"),
                "legacy pre-R728 entry must still be surfaced via R724 merge: " + read);
        assertTrue(read.contains("## Legacy change log (R127, pre-R280)"),
                "R724 legacy section header must be present in read: " + read);
    }

    @Test
    void appendProjectChange_returnsNull_when_cwdIsNull() throws Exception {
        LayeredMemoryStore s = newStore("r728-test-agent");
        assertEquals(null, s.appendProjectChange(null, "x"),
                "null cwd must return null instead of NPE");
    }

    @Test
    void appendProjectChange_returnsNull_when_contentIsNull() throws Exception {
        LayeredMemoryStore s = newStore("r728-test-agent");
        assertEquals(null, s.appendProjectChange(tmp.toString(), null),
                "null content must return null instead of NPE");
    }

    @Test
    void multipleAppends_allPersist() throws Exception {
        LayeredMemoryStore s = newStore("r728-test-agent");
        String cwd = tmp.toString();
        int n = 5;
        for (int i = 0; i < n; i++) {
            s.appendProjectChange(cwd, "entry " + i);
        }
        Path pmFile = projectMemoryFile(s, cwd);
        String body = Files.readString(pmFile);
        for (int i = 0; i < n; i++) {
            assertTrue(body.contains("entry " + i),
                    "PROJECT_MEMORY.md must contain all 5 entries: entry " + i
                            + " missing in " + body);
        }
    }

    @Test
    void projectMemoryCount_tracksAppends() throws Exception {
        LayeredMemoryStore s = newStore("r728-test-agent");
        String cwd = tmp.toString();

        s.appendProjectChange(cwd, "one");
        s.appendProjectChange(cwd, "two");
        s.appendProjectChange(cwd, "three");

        int count = s.countProjectChanges(cwd);
        assertTrue(count >= 3,
                "countProjectChanges must reflect the 3 R728 appends, got: " + count);
    }
}
