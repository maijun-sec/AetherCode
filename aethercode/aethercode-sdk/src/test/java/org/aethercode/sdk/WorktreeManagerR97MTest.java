package org.aethercode.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round.5 tests: WorktreeManager stub.
 *
 * <p>The stub ships a directory-based worktree
 * (no git integration yet). The behaviour matches
 * a "real" git worktree for the purposes of the
 * file_write / bash / file_read tools — the model
 * sees an isolated workspace under
 * {@code AETHERCODE_WORKTREE_ROOT}.
 */
class WorktreeManagerR97MTest {

    @Test
    void addWorktreeCreatesDirectory(@TempDir Path tmp) throws Exception {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);  // R149: isolate from JVM-default tmpdir
        WorktreeManager.WorktreeState st = mgr.addWorktree("test-wt-1");
        assertNotNull(st);
        assertEquals("test-wt-1", st.name());
        assertTrue(Files.isDirectory(st.path()),
                "worktree directory should exist: " + st.path());
    }

    @Test
    void addWorktreeRejectsBlankName() {
        WorktreeManager mgr = new WorktreeManager();
        assertThrows(IllegalArgumentException.class,
                () -> mgr.addWorktree(null));
        assertThrows(IllegalArgumentException.class,
                () -> mgr.addWorktree(""));
    }

    @Test
    void getWorktreeReturnsExisting(@TempDir Path tmp) {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);
        WorktreeManager.WorktreeState a = mgr.addWorktree("test-wt-2");
        WorktreeManager.WorktreeState b = mgr.getWorktree("test-wt-2");
        assertNotNull(b);
        assertEquals(a.path(), b.path());
    }

    @Test
    void removeWorktreeCleansUp(@TempDir Path tmp) throws Exception {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);
        WorktreeManager.WorktreeState st = mgr.addWorktree("test-wt-3");
        Path path = st.path();
        assertTrue(Files.isDirectory(path));
        boolean ok = mgr.removeWorktree("test-wt-3");
        assertTrue(ok);
        assertNull(mgr.getWorktree("test-wt-3"));
        // The directory should be gone (recursive delete).
        assertFalse(Files.exists(path),
                "worktree directory should be deleted: " + path);
    }

    @Test
    void listWorktreesIncludesAll(@TempDir Path tmp) {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);
        mgr.addWorktree("a");
        mgr.addWorktree("b");
        mgr.addWorktree("c");
        var list = mgr.listWorktrees();
        assertEquals(3, list.size());
        assertTrue(list.containsKey("a"));
        assertTrue(list.containsKey("b"));
        assertTrue(list.containsKey("c"));
    }
}
