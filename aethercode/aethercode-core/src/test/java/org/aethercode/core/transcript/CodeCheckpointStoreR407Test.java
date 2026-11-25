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
 * R407: the point BEFORE a turn has to know what that turn did, or a
 * rewind undoes nothing.
 *
 * <h2>★ THE DEFECT THIS MEASURED ★</h2>
 *
 * <p>A turn-boundary checkpoint is taken at the START of a turn, from the
 * tool calls the transcript has recorded SO FAR. A file the session
 * touches for the FIRST time in that turn is therefore not in that turn's
 * point — and restoring that point left the file on the disk while
 * reporting nothing at all:
 *
 * <pre>
 *   boundary point files = []
 *   restore -&gt; restored=[] deleted=[] skipped=[] failed=[]
 *   the file the turn created: still on disk
 * </pre>
 *
 * <p>That is a rewind that reports success and changes nothing. And the
 * cancel point's own javadoc promises the opposite: "undoing the
 * cancelled turn's partial work is the PREVIOUS turn-boundary point's
 * job".
 *
 * <h2>★ THE FIX, IN TWO MOVES ★</h2>
 *
 * <ol>
 *   <li>the write path reports what a file looked like BEFORE the first
 *       write ({@link CodeCheckpointStore#beforeWrite}) — the only moment
 *       that knows the answer;</li>
 *   <li>when the next point is taken, the point that PRECEDES the turn
 *       learns those files: a file the turn CREATED is recorded as
 *       created (the restore deletes it), a file the turn OVERWROTE is
 *       recorded with the content it had (the restore writes it back).</li>
 * </ol>
 */
class CodeCheckpointStoreR407Test {

    @TempDir Path root;

    /** A transcript message that says "the session wrote this file". */
    private static Message wroteFile(String path) {
        return new Message(
                "wrote-" + path,
                Role.ASSISTANT,
                List.of(new ContentBlock.ToolUseBlock(
                        "tu-" + path, "file_write", Map.of("file_path", path))),
                null,
                Map.of());
    }

    private Path work() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        return cwd;
    }

    // ------------------------------------------------------------------
    // 1. the point before the turn learns the file the turn created
    // ------------------------------------------------------------------

    @Test
    void thePointBeforeTheTurnLearnsTheFileThatTurnCreated() throws Exception {
        Path cwd = work();
        CodeCheckpointStore store = new CodeCheckpointStore();

        // the point BEFORE turn one
        store.save("p0", List.of(Message.userText("turn one")), 0, 0, cwd,
                "turn one", CodeCheckpointStore.KIND_TURN);

        // turn one writes a file that did not exist
        Path created = cwd.resolve("new.txt");
        store.beforeWrite(created);
        Files.writeString(created, "HALF DONE", StandardCharsets.UTF_8);

        // the next boundary takes the point BEFORE turn two
        store.save("p1", List.of(
                        Message.userText("turn one"),
                        wroteFile(created.toString()),
                        Message.userText("turn two")),
                3, 1, cwd, "turn two", CodeCheckpointStore.KIND_TURN);

        var amended = store.get("p0").orElseThrow();
        assertEquals(1, amended.files().size(),
                "the point before the turn must learn the file the turn created: "
                        + amended.files());
        assertTrue(amended.files().get(0).created(),
                "the file did not exist before that turn, so it is a CREATED file");
        assertEquals(created.toAbsolutePath().normalize(),
                amended.files().get(0).path());
    }

    @Test
    void theRestoreOfThatPointDeletesTheFileTheTurnCreated() throws Exception {
        Path cwd = work();
        CodeCheckpointStore store = new CodeCheckpointStore();

        store.save("p0", List.of(Message.userText("turn one")), 0, 0, cwd,
                "turn one", CodeCheckpointStore.KIND_TURN);

        Path created = cwd.resolve("new.txt");
        store.beforeWrite(created);
        Files.writeString(created, "HALF DONE", StandardCharsets.UTF_8);

        store.save("p1", List.of(
                        Message.userText("turn one"),
                        wroteFile(created.toString()),
                        Message.userText("turn two")),
                3, 1, cwd, "turn two", CodeCheckpointStore.KIND_TURN);

        var result = store.restore(store.get("p0").orElseThrow());
        assertEquals(List.of("new.txt"), result.deleted(),
                "the restore must DELETE the file the turn created: " + result);
        assertFalse(Files.exists(created),
                "the file the turn created must be gone after the rewind");
    }

    // ------------------------------------------------------------------
    // 2. a file the turn OVERWROTE goes back to what it held
    // ------------------------------------------------------------------

    @Test
    void thePointBeforeTheTurnRestoresWhatTheTurnOverwrote() throws Exception {
        Path cwd = work();
        CodeCheckpointStore store = new CodeCheckpointStore();

        store.save("p0", List.of(Message.userText("turn one")), 0, 0, cwd,
                "turn one", CodeCheckpointStore.KIND_TURN);

        Path existing = cwd.resolve("notes.md");
        Files.writeString(existing, "ORIGINAL", StandardCharsets.UTF_8);
        store.beforeWrite(existing);
        Files.writeString(existing, "MANGLED BY THE TURN", StandardCharsets.UTF_8);

        store.save("p1", List.of(
                        Message.userText("turn one"),
                        wroteFile(existing.toString()),
                        Message.userText("turn two")),
                3, 1, cwd, "turn two", CodeCheckpointStore.KIND_TURN);

        var amended = store.get("p0").orElseThrow();
        assertEquals(1, amended.files().size());
        assertFalse(amended.files().get(0).created(),
                "the file existed before the turn, so it is not a created file");
        assertEquals("ORIGINAL", amended.files().get(0).content(),
                "the point must hold the content the file had BEFORE the turn");

        var result = store.restore(amended);
        assertEquals(List.of("notes.md"), result.restored(), "the restore: " + result);
        assertEquals("ORIGINAL", Files.readString(existing),
                "the file must be back to what it held before the turn");
    }

    // ------------------------------------------------------------------
    // 3. only the FIRST touch counts
    // ------------------------------------------------------------------

    @Test
    void onlyTheFirstTouchIsRecorded() throws Exception {
        Path cwd = work();
        CodeCheckpointStore store = new CodeCheckpointStore();

        store.save("p0", List.of(Message.userText("turn one")), 0, 0, cwd,
                "turn one", CodeCheckpointStore.KIND_TURN);

        Path f = cwd.resolve("a.txt");
        Files.writeString(f, "ORIGINAL", StandardCharsets.UTF_8);
        store.beforeWrite(f);
        Files.writeString(f, "FIRST WRITE", StandardCharsets.UTF_8);
        // a second touch of the same file must NOT overwrite the record
        store.beforeWrite(f);
        Files.writeString(f, "SECOND WRITE", StandardCharsets.UTF_8);

        store.save("p1", List.of(
                        Message.userText("turn one"),
                        wroteFile(f.toString()),
                        Message.userText("turn two")),
                3, 1, cwd, "turn two", CodeCheckpointStore.KIND_TURN);

        var amended = store.get("p0").orElseThrow();
        assertEquals(1, amended.files().size());
        assertEquals("ORIGINAL", amended.files().get(0).content(),
                "the FIRST touch is the one that matters — the pre-session state");
    }

    // ------------------------------------------------------------------
    // 4. the CANCEL point does not take the turn's files
    // ------------------------------------------------------------------

    @Test
    void theCancelPointDoesNotTakeTheTurnsFiles() throws Exception {
        Path cwd = work();
        CodeCheckpointStore store = new CodeCheckpointStore();

        // the point before the cancelled turn
        store.save("p0", List.of(Message.userText("turn one")), 0, 0, cwd,
                "turn one", CodeCheckpointStore.KIND_TURN);

        // the cancelled turn creates a file
        Path created = cwd.resolve("half.txt");
        store.beforeWrite(created);
        Files.writeString(created, "HALF DONE", StandardCharsets.UTF_8);

        // the cancel point: the SCENE where the run stopped
        var cancel = store.save("cp-cancel", List.of(
                        Message.userText("turn one"),
                        wroteFile(created.toString()),
                        Message.userText("turn two")),
                3, 1, cwd, "(stopped here)", CodeCheckpointStore.KIND_CANCEL);

        assertEquals(CodeCheckpointStore.KIND_CANCEL, cancel.kind());
        assertEquals(1, cancel.files().size(),
                "the cancel point records the half-finished scene: " + cancel.files());
        assertFalse(cancel.files().get(0).created(),
                "the file exists at the cancel moment, so the cancel point holds it");

        var amended = store.get("p0").orElseThrow();
        assertEquals(1, amended.files().size(),
                "the files the CANCELLED turn created belong to the point BEFORE it");
        assertTrue(amended.files().get(0).created(),
                "and they are recorded as created, so a rewind deletes them");

        var result = store.restore(amended);
        assertEquals(List.of("half.txt"), result.deleted(),
                "rewinding past the cancelled turn must delete what it created");
    }

    // ------------------------------------------------------------------
    // 5. a CANCEL point never absorbs another turn's files
    // ------------------------------------------------------------------

    @Test
    void aCancelPointNeverAbsorbsAnotherTurnsFiles() throws Exception {
        Path cwd = work();
        CodeCheckpointStore store = new CodeCheckpointStore();

        // the point before the first cancelled turn
        store.save("p0", List.of(Message.userText("turn one")), 0, 0, cwd,
                "turn one", CodeCheckpointStore.KIND_TURN);

        // turn one creates a file and is cancelled
        Path first = cwd.resolve("first.txt");
        store.beforeWrite(first);
        Files.writeString(first, "FIRST", StandardCharsets.UTF_8);
        store.save("cp-cancel-1", List.of(
                        Message.userText("turn one"),
                        wroteFile(first.toString()),
                        Message.userText("turn two")),
                3, 1, cwd, "(stopped here)", CodeCheckpointStore.KIND_CANCEL);

        // turn two starts: the point before it
        store.save("p1", List.of(
                        Message.userText("turn one"),
                        wroteFile(first.toString()),
                        Message.userText("turn two")),
                3, 1, cwd, "turn two", CodeCheckpointStore.KIND_TURN);

        // turn two creates a file and is cancelled too
        Path second = cwd.resolve("second.txt");
        store.beforeWrite(second);
        Files.writeString(second, "SECOND", StandardCharsets.UTF_8);
        store.save("cp-cancel-2", List.of(
                        Message.userText("turn one"),
                        wroteFile(first.toString()),
                        Message.userText("turn two"),
                        wroteFile(second.toString()),
                        Message.userText("turn three")),
                5, 2, cwd, "(stopped here)", CodeCheckpointStore.KIND_CANCEL);

        // ★ THE CANCEL POINT MUST NOT ABSORB ANOTHER TURN'S FILES ★ it
        // records the SCENE where the run stopped; a file another turn
        // created belongs to the point before THAT turn.
        var cancel1 = store.get("cp-cancel-1").orElseThrow();
        assertFalse(cancel1.files().stream()
                        .anyMatch(f -> f.path().equals(second.toAbsolutePath().normalize())),
                "a cancel point must never absorb another turn's files: " + cancel1.files());

        // and the point before the second cancelled turn has it
        var p1 = store.get("p1").orElseThrow();
        assertTrue(p1.files().stream()
                        .anyMatch(f -> f.path().equals(second.toAbsolutePath().normalize())),
                "the point before the cancelled turn must learn its files: " + p1.files());

        // ------------------------------------------------------------------
        // ★ THE SHAPE THAT MAKES THE RULE OBSERVABLE ★
        // ------------------------------------------------------------------
        // Turn three starts with NO turn point between it and cp-cancel-2.
        // So at the moment the next point is taken, the NEWEST point in the
        // store IS a cancel point -- and that is exactly the moment the
        // settlement runs. A settlement that grabs "the newest point" hands
        // the turn's files to the CANCEL point; a settlement that asks for
        // "the newest TURN point" hands them to the point before the turn.
        // (Until this third turn, the newest point was always a turn point,
        // so the two rules could not be told apart.)
        Path third = cwd.resolve("third.txt");
        store.beforeWrite(third);
        Files.writeString(third, "THIRD", StandardCharsets.UTF_8);
        store.save("p2", List.of(
                        Message.userText("turn one"),
                        wroteFile(first.toString()),
                        Message.userText("turn two"),
                        wroteFile(second.toString()),
                        Message.userText("turn three")),
                5, 2, cwd, "turn three", CodeCheckpointStore.KIND_TURN);

        var cancel2 = store.get("cp-cancel-2").orElseThrow();
        assertFalse(cancel2.files().stream()
                        .anyMatch(f -> f.path().equals(third.toAbsolutePath().normalize())),
                "a cancel point must never absorb another turn's files: " + cancel2.files());

        var amendedP1 = store.get("p1").orElseThrow();
        assertTrue(amendedP1.files().stream()
                        .anyMatch(f -> f.path().equals(third.toAbsolutePath().normalize())),
                "the point before the turn must learn its files: " + amendedP1.files());
    }

    // ------------------------------------------------------------------
    // 6. the amendment is persisted (a restart must not lose it)
    // ------------------------------------------------------------------

    @Test
    void theAmendedPointSurvivesAReload() throws Exception {
        Path cwd = work();
        Path dir = root.resolve("points");
        CodeCheckpointStore store = new CodeCheckpointStore(dir);

        store.save("p0", List.of(Message.userText("turn one")), 0, 0, cwd,
                "turn one", CodeCheckpointStore.KIND_TURN);
        Path created = cwd.resolve("new.txt");
        store.beforeWrite(created);
        Files.writeString(created, "HALF DONE", StandardCharsets.UTF_8);
        store.save("p1", List.of(
                        Message.userText("turn one"),
                        wroteFile(created.toString()),
                        Message.userText("turn two")),
                3, 1, cwd, "turn two", CodeCheckpointStore.KIND_TURN);

        // a brand-new store over the same directory
        CodeCheckpointStore reloaded = new CodeCheckpointStore(dir);
        reloaded.loadFromDisk();
        var back = reloaded.get("p0").orElseThrow();
        assertEquals(1, back.files().size(),
                "the amendment must be on the disk, not only in the heap: " + back.files());
        assertTrue(back.files().get(0).created());
    }

    // ------------------------------------------------------------------
    // 6. without a report, the behaviour is exactly what it was
    // ------------------------------------------------------------------

    @Test
    void nothingIsInventedWhenNobodyReports() throws Exception {
        Path cwd = work();
        CodeCheckpointStore store = new CodeCheckpointStore();
        store.save("p0", List.of(Message.userText("turn one")), 0, 0, cwd,
                "turn one", CodeCheckpointStore.KIND_TURN);
        store.save("p1", List.of(Message.userText("turn one"), Message.userText("turn two")),
                2, 1, cwd, "turn two", CodeCheckpointStore.KIND_TURN);
        assertTrue(store.get("p0").orElseThrow().files().isEmpty(),
                "no report ⇒ no invention: the point stays as it was");
    }
}
