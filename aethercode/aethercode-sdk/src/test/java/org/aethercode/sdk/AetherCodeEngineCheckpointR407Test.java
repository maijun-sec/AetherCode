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

package org.aethercode.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.aethercode.core.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R407: a rewind past a turn must UNDO that turn.
 *
 * <h2>★ WHAT WAS BROKEN ★</h2>
 *
 * <p>A turn-boundary checkpoint is taken at the START of a turn, from the
 * tool calls the transcript has recorded so far. So a file the session
 * touched for the FIRST time in that turn was not in that turn's point —
 * and restoring that point changed nothing while reporting nothing:
 *
 * <pre>
 *   boundary point files = []
 *   restore -&gt; restored=[] deleted=[] skipped=[] failed=[]
 *   the file the turn created: still on disk
 * </pre>
 *
 * <p>The cancel point's own javadoc promises the opposite: "undoing the
 * cancelled turn's partial work is the PREVIOUS turn-boundary point's
 * job".
 *
 * <h2>★ THE FIX ★</h2>
 *
 * <p>The write path reports what a file looked like before the write
 * (the only moment that knows), and the point BEFORE a turn learns the
 * files that turn touched for the first time: a file the turn CREATED is
 * recorded as created (the restore deletes it), a file the turn OVERWROTE
 * is recorded with the content it had (the restore writes it back).
 *
 * <p>These tests drive the ENGINE's own boundary path, not the store's
 * API — the point that matters is the one the user rewinds to.
 */
class AetherCodeEngineCheckpointR407Test {

    @TempDir Path root;

    private AetherCodeEngine engine(Path cwd, String sessionId) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .agentsDir(cwd)
                .tools(List.of())
                .sessionId(sessionId)
                .build();
    }

    @Test
    void theWritePathIsWiredToTheRecorder() {
        // ★ THE WIRING IS THE PART THAT ROTS QUIETLY ★ a recorder that
        // exists but was never installed leaves every rewind silently
        // undoing nothing — and no other test would notice.
        Path cwd = root.resolve("work");
        AetherCodeEngine e = engine(cwd, "s1");
        assertNotNull(e.writePathRecorder(),
                "the write path must carry a recorder");
        assertSame(e.fileTouchRecorder(), e.writePathRecorder(),
                "the executor must carry the engine's own recorder");
    }

    @Test
    void aRewindPastTheTurnThatCreatedAFileDeletesIt() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        AetherCodeEngine e = engine(cwd, "s1");

        // the point BEFORE turn one
        var before = e.saveCodeCheckpoint(0, "turn one");
        assertTrue(before.files().isEmpty(),
                "nothing has been touched yet: " + before.files());

        // turn one creates a file — the write path reports what it looked
        // like before the write (it did not exist)
        Path created = cwd.resolve("new.txt");
        e.fileTouchRecorder().beforeWrite(created);
        Files.writeString(created, "HALF DONE", StandardCharsets.UTF_8);

        // the point BEFORE turn two — this is where the previous point
        // learns about the file the finished turn created
        e.saveCodeCheckpoint(1, "turn two");

        var result = e.restoreCodeCheckpoint(before.id());
        assertEquals(List.of("new.txt"), result.deleted(),
                "a rewind past the turn must delete the file it created: " + result);
        assertFalse(Files.exists(created),
                "the file the turn created must be gone after the rewind");
    }

    @Test
    void aRewindPastTheTurnThatOverwroteAFilePutsItBack() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        AetherCodeEngine e = engine(cwd, "s1");

        var before = e.saveCodeCheckpoint(0, "turn one");

        Path existing = cwd.resolve("notes.md");
        Files.writeString(existing, "ORIGINAL", StandardCharsets.UTF_8);
        e.fileTouchRecorder().beforeWrite(existing);
        Files.writeString(existing, "MANGLED BY THE TURN", StandardCharsets.UTF_8);

        e.saveCodeCheckpoint(1, "turn two");

        var result = e.restoreCodeCheckpoint(before.id());
        assertEquals(List.of("notes.md"), result.restored(), "the restore: " + result);
        assertEquals("ORIGINAL", Files.readString(existing),
                "the file must be back to what it held before the turn");
    }

    @Test
    void thePointTheCancelPointPrecedesLearnsTheCancelledTurnsFiles() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        AetherCodeEngine e = engine(cwd, "s1");

        // the point BEFORE the cancelled turn
        var before = e.saveCodeCheckpoint(0, "turn one");

        // the cancelled turn creates a file
        Path created = cwd.resolve("half.txt");
        e.fileTouchRecorder().beforeWrite(created);
        Files.writeString(created, "HALF DONE", StandardCharsets.UTF_8);
        e.appState().appendMessage(Message.userText("turn two"));

        var cancel = e.markCancelCheckpoint("test").orElseThrow();
        assertTrue(cancel.isCancelPoint());

        // the point before the cancelled turn must know the file
        var learned = e.codeCheckpoints().stream()
                .filter(cp -> cp.id().equals(before.id()))
                .findFirst().orElseThrow();
        assertEquals(1, learned.files().size(),
                "the point before the cancelled turn must learn its files: " + learned.files());

        var result = e.restoreCodeCheckpoint(before.id());
        assertEquals(List.of("half.txt"), result.deleted(),
                "rewinding past the cancelled turn must delete what it created: " + result);
        assertFalse(Files.exists(created));
    }
}
