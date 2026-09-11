package org.aethercode.core.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.aethercode.core.fs.WorkspaceBackup.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceBackupTest {

    @Test
    void snapshot_copiesAllFiles(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        Files.createDirectories(src);
        Files.writeString(src.resolve("a.txt"), "hello");
        Files.writeString(src.resolve("b.txt"), "world");
        Files.createDirectories(src.resolve("sub"));
        Files.writeString(src.resolve("sub/c.txt"), "nested");

        Manifest m = new WorkspaceBackup(src, dst, "b1").snapshot();
        assertEquals(3, m.fileCount());
        assertTrue(Files.exists(dst.resolve("b1/a.txt")));
        assertTrue(Files.exists(dst.resolve("b1/b.txt")));
        assertTrue(Files.exists(dst.resolve("b1/sub/c.txt")));
    }

    @Test
    void snapshot_writesManifestFile(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        Files.createDirectories(src);
        Files.writeString(src.resolve("a.txt"), "x");
        new WorkspaceBackup(src, dst, "b1").snapshot();
        assertTrue(Files.exists(dst.resolve("b1/MANIFEST")));
    }

    @Test
    void snapshot_excludesHiddenByDefault(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        Files.createDirectories(src);
        Files.writeString(src.resolve("visible.txt"), "x");
        Files.writeString(src.resolve(".hidden"), "x");
        Manifest m = new WorkspaceBackup(src, dst, "b1").snapshot();
        assertEquals(1, m.fileCount());
        assertTrue(!Files.exists(dst.resolve("b1/.hidden")));
    }

    @Test
    void snapshot_excludesGitByDefault(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        Files.createDirectories(src.resolve(".git"));
        Files.writeString(src.resolve(".git/config"), "x");
        Files.writeString(src.resolve("file.txt"), "x");
        Manifest m = new WorkspaceBackup(src, dst, "b1").snapshot();
        assertEquals(1, m.fileCount());
    }

    @Test
    void snapshot_includesHiddenWhenEnabled(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        Files.createDirectories(src);
        Files.writeString(src.resolve("visible.txt"), "x");
        Files.writeString(src.resolve(".hidden"), "x");
        Manifest m = new WorkspaceBackup(src, dst, "b1", true, false).snapshot();
        assertEquals(2, m.fileCount());
    }

    @Test
    void snapshot_includesGitWhenEnabled(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        Files.createDirectories(src.resolve(".git"));
        Files.writeString(src.resolve(".git/config"), "x");
        Files.writeString(src.resolve("file.txt"), "x");
        Manifest m = new WorkspaceBackup(src, dst, "b1", false, true).snapshot();
        assertEquals(2, m.fileCount());
    }

    @Test
    void snapshot_missingSourceThrows(@TempDir Path tmp) {
        Path dst = tmp.resolve("dst");
        Path missing = tmp.resolve("nope");
        assertThrows(IOException.class, () -> new WorkspaceBackup(missing, dst, "b1").snapshot());
    }

    @Test
    void snapshot_blankNameThrows(@TempDir Path tmp) {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceBackup(src, dst, ""));
    }

    @Test
    void snapshot_emptySourceIsZeroFiles(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Path dst = tmp.resolve("dst");
        Manifest m = new WorkspaceBackup(src, dst, "b1").snapshot();
        assertEquals(0, m.fileCount());
    }

    @Test
    void listFiles_returnsAllIncluded(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("a.txt"), "x");
        Files.writeString(src.resolve("b.txt"), "x");
        List<Path> files = new WorkspaceBackup(src, tmp.resolve("dst"), "b1").listFiles();
        assertEquals(2, files.size());
    }

    @Test
    void listFiles_excludesHiddenAndGit(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve(".git"));
        Files.writeString(src.resolve("a.txt"), "x");
        Files.writeString(src.resolve(".hidden"), "x");
        List<Path> files = new WorkspaceBackup(src, tmp.resolve("dst"), "b1").listFiles();
        assertEquals(1, files.size());
    }

    @Test
    void manifestOf_readsManifest(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        Files.createDirectories(src);
        Files.writeString(src.resolve("a.txt"), "x");
        WorkspaceBackup bk = new WorkspaceBackup(src, dst, "b1");
        Manifest written = bk.snapshot();
        Manifest read = bk.manifestOf(written.backupRoot());
        assertEquals(written.backupName(), read.backupName());
        assertEquals(written.fileCount(), read.fileCount());
    }

    @Test
    void manifestOf_missingThrows(@TempDir Path tmp) {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        WorkspaceBackup bk = new WorkspaceBackup(src, dst, "b1");
        assertThrows(IOException.class, () -> bk.manifestOf(tmp.resolve("nope")));
    }

    @Test
    void snapshot_totalBytesAccumulates(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("a.txt"), "x".repeat(100));
        Files.writeString(src.resolve("b.txt"), "y".repeat(50));
        Manifest m = new WorkspaceBackup(src, tmp.resolve("dst"), "b1").snapshot();
        assertTrue(m.totalBytes() >= 150);
    }

    @Test
    void constructor_rejectsNullArgs(@TempDir Path tmp) {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        try {
            new WorkspaceBackup(null, dst, "b1");
        } catch (NullPointerException e) {
            assertNotNull(e);
        }
        try {
            new WorkspaceBackup(src, null, "b1");
        } catch (NullPointerException e) {
            assertNotNull(e);
        }
    }
}
