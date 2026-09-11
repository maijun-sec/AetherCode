package org.aethercode.core.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.aethercode.core.fs.WorkspaceTree.Node;
import org.aethercode.core.fs.WorkspaceTree.Options;
import org.aethercode.core.fs.WorkspaceTree.RenderResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTreeTest {

    @Test
    void scan_simpleTree(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("a/b"));
        Files.createFile(tmp.resolve("a/x.txt"));
        Files.createFile(tmp.resolve("a/b/y.txt"));
        Node root = WorkspaceTree.scan(tmp, Options.defaults());
        assertEquals(tmp.getFileName().toString(), root.name());
        assertTrue(root.directory());
        assertTrue(root.children().size() >= 1);
    }

    @Test
    void scan_honorsMaxDepth(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("a/b/c/d"));
        Node root = WorkspaceTree.scan(tmp, new Options(2, 200, false, true, false));
        // Depth 0: root, 1: a, 2: b — c should not be present
        Node a = root.children().get(0);
        assertEquals("a", a.name());
        if (!a.children().isEmpty()) {
            Node b = a.children().get(0);
            assertEquals("b", b.name());
            assertTrue(b.children().isEmpty(), "depth 2 should have no children");
        }
    }

    @Test
    void scan_honorsMaxEntries(@TempDir Path tmp) throws Exception {
        for (int i = 0; i < 10; i++) Files.createFile(tmp.resolve("file" + i + ".txt"));
        Node root = WorkspaceTree.scan(tmp, new Options(5, 3, false, true, false));
        assertTrue(root.children().size() <= 3);
    }

    @Test
    void scan_hiddenFilesExcludedByDefault(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("visible.txt"));
        Files.createFile(tmp.resolve(".hidden"));
        Node root = WorkspaceTree.scan(tmp, Options.defaults());
        // Only visible.txt should be in children
        assertEquals(1, root.children().size());
        assertEquals("visible.txt", root.children().get(0).name());
    }

    @Test
    void scan_hiddenFilesIncludedWhenEnabled(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("visible.txt"));
        Files.createFile(tmp.resolve(".hidden"));
        Node root = WorkspaceTree.scan(tmp, new Options(5, 200, true, true, false));
        assertEquals(2, root.children().size());
    }

    @Test
    void scan_directoriesOnly(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("a.txt"));
        Files.createDirectories(tmp.resolve("sub"));
        Node root = WorkspaceTree.scan(tmp, new Options(5, 200, false, true, true));
        assertEquals(1, root.children().size());
        assertEquals("sub", root.children().get(0).name());
    }

    @Test
    void render_usesTreeConnectors(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("a"));
        Files.createFile(tmp.resolve("b.txt"));
        RenderResult r = WorkspaceTree.scanAndRender(tmp, Options.defaults());
        String out = WorkspaceTree.toString(r);
        assertTrue(out.contains("├── "));
        assertTrue(out.contains("└── "));
    }

    @Test
    void render_directoriesHaveTrailingSlash(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("sub"));
        RenderResult r = WorkspaceTree.scanAndRender(tmp, Options.defaults());
        assertTrue(WorkspaceTree.toString(r).contains("sub/"));
    }

    @Test
    void render_filesHaveNoTrailingSlash(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("file.txt"));
        RenderResult r = WorkspaceTree.scanAndRender(tmp, Options.defaults());
        String out = WorkspaceTree.toString(r);
        assertTrue(out.contains("file.txt"));
        assertFalse(out.contains("file.txt/"));
    }

    @Test
    void scan_missingPathThrows(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope");
        assertThrows(IOException.class, () -> WorkspaceTree.scan(missing, Options.defaults()));
    }

    @Test
    void scan_emptyDir(@TempDir Path tmp) throws Exception {
        Node root = WorkspaceTree.scan(tmp, Options.defaults());
        assertEquals(0, root.children().size());
    }

    @Test
    void render_linesAreNonEmpty(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("a.txt"));
        Files.createFile(tmp.resolve("b.txt"));
        RenderResult r = WorkspaceTree.scanAndRender(tmp, Options.defaults());
        for (String line : r.lines()) {
            assertFalse(line.isEmpty());
        }
    }

    @Test
    void scan_filesOnlyWhenShowFilesDisabled(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("a.txt"));
        Files.createDirectories(tmp.resolve("sub"));
        Node root = WorkspaceTree.scan(tmp, new Options(5, 200, false, false, false));
        // showFiles=false: only directories. So "sub" is shown, "a.txt" is not.
        assertEquals(1, root.children().size());
        assertEquals("sub", root.children().get(0).name());
    }

    @Test
    void options_defaultsHaveSensibleValues() {
        Options o = Options.defaults();
        assertTrue(o.maxDepth() > 0);
        assertTrue(o.maxEntries() > 0);
    }

    @Test
    void node_isFile_isInverseOfDirectory() {
        Node dir = new Node("d", null, true, java.util.List.of());
        Node file = new Node("f", null, false, java.util.List.of());
        assertTrue(dir.isFile() == false);
        assertTrue(file.isFile());
    }
}
