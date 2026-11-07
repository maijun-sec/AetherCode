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

package org.aethercode.core.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R388n: the checkpoint store — the FILES side of a rewind, and the
 * ordinal rule that decides where a rewind lands.
 *
 * <p>★ The tests that matter here are the ones about WHERE a rewind
 * lands. ★ A checkpoint that captures the wrong file state, or that
 * resolves to the wrong message, looks exactly like a working feature
 * right up until the moment it corrupts something.
 */
class CodeCheckpointStoreR388nTest {

    // ---------------------------------------------------------------
    // The ordinal rule: which USER-role messages count as user turns.
    // ---------------------------------------------------------------

    private static Message sideNote(String kind, String text) {
        return new Message(
                "n-" + kind,
                Role.USER,
                List.of(new ContentBlock.TextBlock(text)),
                null,
                Map.of("kind", kind));
    }

    @Test
    void plainUserMessageIsARealTurn() {
        assertTrue(CodeCheckpointStore.isRealUserTurn(Message.userText("hello")));
    }

    @Test
    void assistantMessageIsNotATurn() {
        Message m = Message.assistantText("hi");
        assertFalse(CodeCheckpointStore.isRealUserTurn(m));
    }

    @Test
    void systemMessageIsNotATurn() {
        Message m = Message.system("sys");
        assertFalse(CodeCheckpointStore.isRealUserTurn(m));
    }

    @Test
    void sideNotesAreNotTurns() {
        // ★ This is the whole point of the rule. ★ The engine writes
        // these with Role.USER, and counting them shifts every rewind
        // target that comes after them.
        assertFalse(CodeCheckpointStore.isRealUserTurn(
                sideNote("session-anchor", "[anchor]")));
        assertFalse(CodeCheckpointStore.isRealUserTurn(
                sideNote("compaction-side-note", "[compaction] done")));
        assertFalse(CodeCheckpointStore.isRealUserTurn(
                sideNote("transcript-trim-side-note", "[R716] trimmed")));
        assertFalse(CodeCheckpointStore.isRealUserTurn(
                sideNote("todo-ask-llm", "which todo next?")));
        assertFalse(CodeCheckpointStore.isRealUserTurn(
                sideNote("user-decision-resume", "[Resuming]")));
        assertFalse(CodeCheckpointStore.isRealUserTurn(
                sideNote("dedupe-stub", "stub")));
    }

    @Test
    void unknownKindIsStillATurn() {
        // A kind we do not know about must NOT silently swallow a real
        // user message — that would drop a turn from the count and
        // shift the numbering the other way.
        assertTrue(CodeCheckpointStore.isRealUserTurn(sideNote("some-future-kind", "x")));
    }

    @Test
    void userTurnIndicesSkipsSideNotes() {
        List<Message> t = List.of(
                Message.userText("first"),
                sideNote("session-anchor", "[anchor]"),
                Message.assistantText("reply"),
                Message.userText("second"),
                sideNote("compaction-side-note", "[compaction]"),
                Message.userText("third"));
        assertEquals(List.of(0, 3, 5), CodeCheckpointStore.userTurnIndices(t));
    }

    @Test
    void userTurnIndexResolvesTheRightMessage() {
        List<Message> t = List.of(
                Message.userText("first"),
                sideNote("session-anchor", "[anchor]"),
                Message.userText("second"));
        assertEquals(0, CodeCheckpointStore.userTurnIndex(t, 0));
        assertEquals(2, CodeCheckpointStore.userTurnIndex(t, 1));
        assertEquals(-1, CodeCheckpointStore.userTurnIndex(t, 2));
        assertEquals(-1, CodeCheckpointStore.userTurnIndex(t, -1));
    }

    // ---------------------------------------------------------------
    // Which files a checkpoint covers.
    // ---------------------------------------------------------------

    private static Message assistantToolUse(String tool, String pathField, String path) {
        return new Message(
                "a-" + tool,
                Role.ASSISTANT,
                List.of(new ContentBlock.ToolUseBlock(
                        "t-" + tool, tool, Map.of(pathField, path))),
                null,
                Map.of());
    }

    @Test
    void writeToolsAreTheOnesThatChangeFiles() {
        assertEquals(
                Map.of("file_write", "file_path", "file_edit", "file_path"),
                CodeCheckpointStore.writeTools());
    }

    @Test
    void touchedFilesCoversWriteToolsOnly(@TempDir Path tmp) {
        List<Message> t = List.of(
                assistantToolUse("file_write", "file_path", "a.txt"),
                assistantToolUse("file_edit", "file_path", "b.txt"),
                assistantToolUse("file_read", "file_path", "c.txt"),
                assistantToolUse("bash", "command", "ls"));
        var touched = CodeCheckpointStore.touchedFiles(t, tmp);
        assertEquals(2, touched.size());
        assertTrue(touched.contains(tmp.resolve("a.txt").normalize()));
        assertTrue(touched.contains(tmp.resolve("b.txt").normalize()));
    }

    @Test
    void touchedFilesResolvesRelativePathsAgainstCwd(@TempDir Path tmp) {
        List<Message> t = List.of(assistantToolUse("file_write", "file_path", "sub/x.txt"));
        var touched = CodeCheckpointStore.touchedFiles(t, tmp);
        assertEquals(1, touched.size());
        assertTrue(touched.contains(tmp.resolve("sub/x.txt").normalize()));
    }

    @Test
    void touchedFilesIgnoresBlankPaths(@TempDir Path tmp) {
        List<Message> t = List.of(
                new Message("a", Role.ASSISTANT,
                        List.of(new ContentBlock.ToolUseBlock("t", "file_write",
                                Map.of("file_path", "  "))), null, Map.of()));
        assertTrue(CodeCheckpointStore.touchedFiles(t, tmp).isEmpty());
    }

    // ---------------------------------------------------------------
    // The restore: what it puts back, deletes, skips and reports.
    // ---------------------------------------------------------------

    @Test
    void restorePutsBackTheOriginalContent(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "ORIGINAL", StandardCharsets.UTF_8);

        CodeCheckpointStore store = new CodeCheckpointStore();
        var cp = store.save("cp1",
                List.of(assistantToolUse("file_write", "file_path", "a.txt")),
                0, 0, tmp, "turn 1");

        // The session changed the file after the checkpoint.
        Files.writeString(f, "MODIFIED", StandardCharsets.UTF_8);

        var result = store.restore(cp);
        assertEquals(List.of("a.txt"), result.restored());
        assertEquals("ORIGINAL", Files.readString(f, StandardCharsets.UTF_8));
        assertTrue(result.failed().isEmpty());
        assertTrue(result.isComplete());
    }

    @Test
    void restoreDELETESaFileTheSessionCreated(@TempDir Path tmp) throws Exception {
        // ★ A created file must be DELETED, not written back empty. ★
        // Writing an empty file leaves a zero-byte file where the
        // user's tree had none — the restore "succeeded" and still
        // changed the tree.
        CodeCheckpointStore store = new CodeCheckpointStore();
        var cp = store.save("cp1",
                List.of(assistantToolUse("file_write", "file_path", "new.txt")),
                0, 0, tmp, "turn 1");
        assertEquals(1, cp.files().size());
        assertTrue(cp.files().get(0).created());

        Path f = tmp.resolve("new.txt");
        Files.writeString(f, "written by the session", StandardCharsets.UTF_8);

        var result = store.restore(cp);
        assertEquals(List.of("new.txt"), result.deleted());
        assertFalse(Files.exists(f));
    }

    @Test
    void restoreSkipsBinaryFilesAndSaysSo(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("bin.dat");
        byte[] bytes = new byte[] {1, 2, 0, 3, 4};
        Files.write(f, bytes);

        CodeCheckpointStore store = new CodeCheckpointStore();
        var cp = store.save("cp1",
                List.of(assistantToolUse("file_write", "file_path", "bin.dat")),
                0, 0, tmp, "turn 1");

        var result = store.restore(cp);
        assertEquals(List.of("bin.dat"), result.skipped());
        assertTrue(result.restored().isEmpty());
        // The file is untouched — a mangled write-back would be worse.
        assertArrayEqualsBytes(bytes, Files.readAllBytes(f));
    }

    @Test
    void binaryDetectionUsesNulInTheFirst8Kb() {
        assertTrue(CodeCheckpointStore.isBinary(new byte[] {1, 0, 2}));
        assertFalse(CodeCheckpointStore.isBinary("hello".getBytes(StandardCharsets.UTF_8)));
        // A NUL beyond the first 8 KB does not count. The filler byte
        // must NOT be NUL — a zero-filled array is binary everywhere.
        byte[] late = new byte[9000];
        java.util.Arrays.fill(late, (byte) 'x');
        late[8999] = 0;
        assertFalse(CodeCheckpointStore.isBinary(late));
        // A NUL inside the window does count.
        byte[] early = new byte[9000];
        java.util.Arrays.fill(early, (byte) 'x');
        early[10] = 0;
        assertTrue(CodeCheckpointStore.isBinary(early));
    }

    @Test
    void restoreNeverThrowsOnAMissingParentDirectory(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("nested/deep/a.txt");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "ORIGINAL", StandardCharsets.UTF_8);

        CodeCheckpointStore store = new CodeCheckpointStore();
        var cp = store.save("cp1",
                List.of(assistantToolUse("file_write", "file_path", "nested/deep/a.txt")),
                0, 0, tmp, "turn 1");

        // Remove the file and the directories it lived in, deepest
        // first — a directory can only be deleted when it is empty.
        Files.delete(f);
        Files.delete(f.getParent());
        Files.delete(f.getParent().getParent());

        var result = store.restore(cp);
        // The restore recreates the parents, so the file comes back.
        assertEquals(List.of("a.txt"), result.restored());
        assertTrue(result.failed().isEmpty());
        assertEquals("ORIGINAL", Files.readString(f, StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------
    // The list.
    // ---------------------------------------------------------------

    @Test
    void listKeepsCreationOrder(@TempDir Path tmp) {
        CodeCheckpointStore store = new CodeCheckpointStore();
        store.save("cp1", List.of(), 0, 0, tmp, "one");
        store.save("cp2", List.of(), 1, 1, tmp, "two");
        store.save("cp3", List.of(), 2, 2, tmp, "three");
        assertEquals(List.of("cp1", "cp2", "cp3"),
                store.list().stream().map(c -> c.id()).toList());
    }

    @Test
    void getReturnsEmptyForAnUnknownId() {
        CodeCheckpointStore store = new CodeCheckpointStore();
        assertTrue(store.get("nope").isEmpty());
        assertTrue(store.get(null).isEmpty());
    }

    @Test
    void clearForgetsEverything(@TempDir Path tmp) {
        CodeCheckpointStore store = new CodeCheckpointStore();
        store.save("cp1", List.of(), 0, 0, tmp, "one");
        store.clear();
        assertTrue(store.list().isEmpty());
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "byte length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte " + i);
        }
    }
}
