package org.aethercode.core.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchApplierTest {

    private static void write(Path p, String content) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void apply_addition(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        write(f, "line1\nline2\nline3\n");
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -2 +2,2 @@\n line2\n+inserted\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        new PatchApplier().apply(pf, tmp);
        String after = Files.readString(f, StandardCharsets.UTF_8);
        assertEquals("line1\nline2\ninserted\nline3\n", after);
    }

    @Test
    void apply_removal(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        write(f, "line1\nline2\nline3\n");
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -2 +2 @@\n-line2\n+line2-mod\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        new PatchApplier().apply(pf, tmp);
        String after = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(after.contains("line2-mod"));
        assertFalse(after.contains("line2\nline2"));
    }

    @Test
    void apply_replacement(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        write(f, "a\nb\nc\n");
        // -a, +x, " b", -c, +y: removes a and c, adds x and y around b
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1,3 +1,3 @@\n-a\n+x\n b\n-c\n+y\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        new PatchApplier().apply(pf, tmp);
        assertEquals("x\nb\ny\n", Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void apply_strictRejectsMismatch(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        write(f, "a\nb\nc\n");
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-WRONG\n+correct\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        assertThrows(PatchApplyException.class, () -> new PatchApplier().apply(pf, tmp));
    }

    @Test
    void apply_lenientAcceptsMismatch(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        write(f, "a\nb\nc\n");
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-WRONG\n+correct\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        new PatchApplier(false, false).apply(pf, tmp);
        // In lenient mode the file is still rewritten with whatever the
        // hunk says (the mismatched line is removed, the new one is added)
        String after = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(after.contains("correct"));
    }

    @Test
    void apply_addNewFile(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("new.txt");
        String diff = "--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1,2 @@\n+line1\n+line2\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        new PatchApplier().apply(pf, tmp);
        assertTrue(Files.exists(f));
        assertEquals("line1\nline2\n", Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void apply_deleteFile(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("delete-me.txt");
        write(f, "bye\n");
        String diff = "--- a/delete-me.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-bye\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        new PatchApplier().apply(pf, tmp);
        assertFalse(Files.exists(f));
    }

    @Test
    void apply_createsParentDirectories(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a/b/c.txt");
        String diff = "--- /dev/null\n+++ b/a/b/c.txt\n@@ -0,0 +1 @@\n+x\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        new PatchApplier().apply(pf, tmp);
        assertTrue(Files.exists(f));
        assertEquals("x\n", Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void apply_withBackup(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        write(f, "old\n");
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        new PatchApplier(true, true).apply(pf, tmp);
        assertEquals("new\n", Files.readString(f, StandardCharsets.UTF_8));
        Path bak = tmp.resolve("a.txt.bak");
        assertTrue(Files.exists(bak));
        assertEquals("old\n", Files.readString(bak, StandardCharsets.UTF_8));
    }

    @Test
    void apply_outOfRangeAnchorFails(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        write(f, "a\n");
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -100 +100 @@\n-?\n+x\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        assertThrows(PatchApplyException.class, () -> new PatchApplier().apply(pf, tmp));
    }

    @Test
    void applyAll_processesInOrder(@TempDir Path tmp) throws Exception {
        Path f1 = tmp.resolve("a.txt");
        Path f2 = tmp.resolve("b.txt");
        write(f1, "a\n");
        write(f2, "b\n");
        String diff = ""
                + "--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-a\n+A\n"
                + "--- a/b.txt\n+++ b/b.txt\n@@ -1 +1 @@\n-b\n+B\n";
        List<PatchFile> all = PatchParser.parse(diff);
        new PatchApplier().applyAll(all, tmp);
        assertEquals("A\n", Files.readString(f1, StandardCharsets.UTF_8));
        assertEquals("B\n", Files.readString(f2, StandardCharsets.UTF_8));
    }

    @Test
    void apply_multipleHunksInSameFile(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("multi.txt");
        write(f, "a1\na2\na3\nb1\nb2\nb3\n");
        // two hunks: replace a2 and b2. Hunk 1 covers lines 1-3, hunk 2 covers lines 4-6.
        String diff = ""
                + "--- a/multi.txt\n+++ b/multi.txt\n@@ -1,3 +1,3 @@\n a1\n-a2\n+A2\n a3\n"
                + "@@ -4,3 +4,3 @@\n b1\n-b2\n+B2\n b3\n";
        List<PatchFile> all = PatchParser.parse(diff);
        new PatchApplier().applyAll(all, tmp);
        assertEquals("a1\nA2\na3\nb1\nB2\nb3\n", Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void apply_returnsResultMetadata(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        write(f, "a\nb\nc\n");
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -2 +2 @@\n-b\n+B\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        var r = new PatchApplier().apply(pf, tmp);
        assertEquals("a.txt", r.newPath());
        assertFalse(r.deleted());
    }

    @Test
    void apply_missingOldFileOnModifyFails(@TempDir Path tmp) {
        String diff = "--- a/missing.txt\n+++ b/missing.txt\n@@ -1 +1 @@\n-a\n+A\n";
        PatchFile pf = PatchParser.parseSingle(diff);
        assertThrows(PatchApplyException.class, () -> new PatchApplier().apply(pf, tmp));
    }
}
