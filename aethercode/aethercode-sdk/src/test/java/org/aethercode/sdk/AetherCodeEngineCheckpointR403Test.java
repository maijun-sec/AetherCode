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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.transcript.CheckpointDiskStore;
import org.aethercode.core.transcript.CodeCheckpointStore;
import org.aethercode.core.transcript.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R403 — the ENGINE's half: the history survives a restart, and a
 * cancelled run leaves a point behind.
 *
 * <h2>★ WHAT THIS GATE HOLDS ★</h2>
 *
 * <ul>
 *   <li><b>The history survives a restart.</b> A second engine, built
 *       against the same session store, finds the points the first
 *       engine wrote. This is gap G1 closed: before R403 the points
 *       lived in an engine instance field and a daemon restart erased
 *       them — including the anchor that said where in the conversation
 *       the snapshot was taken.</li>
 *   <li><b>A cancelled run leaves a point BEHIND, at the cancelled
 *       turn.</b> The point's conversation anchor is the cancelled
 *       turn's user message — because that turn never finished, and its
 *       half-written messages are not a conversation anybody can
 *       continue from.</li>
 *   <li><b>The point's files are the ones the session actually
 *       wrote.</b> The file list is derived from the transcript's tool
 *       calls, and the engine appends the assistant message (which
 *       carries those tool calls) BEFORE the tools run — so a cancel
 *       that lands mid-turn still sees the files that were written.</li>
 *   <li><b>Deleting a session deletes its checkpoints.</b> The code
 *       snapshots of a session the user threw away are unreachable
 *       garbage.</li>
 * </ul>
 */
class AetherCodeEngineCheckpointR403Test {

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

    private AetherCodeEngine engine(Path cwd, SessionStore store, String sessionId) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .agentsDir(cwd)
                .tools(List.of())
                .sessionStore(store)
                .sessionId(sessionId)
                .build();
    }

    // ------------------------------------------------------------------
    // the history survives a restart
    // ------------------------------------------------------------------

    @Test
    void theCheckpointSurvivesANewEngine() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        Path f = cwd.resolve("a.txt");
        Files.writeString(f, "ORIGINAL", StandardCharsets.UTF_8);

        SessionStore store = new SessionStore(root.resolve("sessions"));
        AetherCodeEngine first = engine(cwd, store, "s1");
        first.appState().appendMessage(Message.userText("turn one"));
        first.appState().appendMessage(wroteFile(f.toString()));
        var cp = first.saveCodeCheckpoint(0, "turn one");
        assertNotNull(cp);

        // ★ THE RESTART ★ A brand-new engine over the same store.
        AetherCodeEngine second = engine(cwd, store, "s1");
        assertEquals(1, second.codeCheckpoints().size(),
                "the point must survive the restart: " + second.codeCheckpoints());
        var back = second.codeCheckpoints().get(0);
        assertEquals(cp.id(), back.id());
        assertEquals("ORIGINAL", back.files().get(0).content(),
                "the snapshot's content must survive too");
    }

    @Test
    void theCheckpointDirectoryIsTheSessionsOwn() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        SessionStore store = new SessionStore(root.resolve("sessions"));
        AetherCodeEngine e = engine(cwd, store, "s1");
        e.appState().appendMessage(Message.userText("turn one"));
        e.saveCodeCheckpoint(0, "turn one");

        Path expected = store.dir().resolve("s1").resolve(CheckpointDiskStore.DIR_NAME);
        assertTrue(Files.isDirectory(expected),
                "the points must live under <sessionsDir>/<sessionId>/checkpoints: " + expected);
        assertEquals(1, new CheckpointDiskStore(expected).loadAll().size());
    }

    @Test
    void switchingSessionsSwitchesTheCheckpointHistory() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        SessionStore store = new SessionStore(root.resolve("sessions"));

        AetherCodeEngine e = engine(cwd, store, "s1");
        e.appState().appendMessage(Message.userText("turn one"));
        e.saveCodeCheckpoint(0, "turn one");
        assertEquals(1, e.codeCheckpoints().size());

        // A second session has its OWN history.
        e.loadSession("s2");
        assertEquals(0, e.codeCheckpoints().size(),
                "the new session must not show the old session's points");
    }

    @Test
    void deletingASessionDeletesItsCheckpoints() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        SessionStore store = new SessionStore(root.resolve("sessions"));

        AetherCodeEngine e = engine(cwd, store, "s1");
        e.appState().appendMessage(Message.userText("turn one"));
        e.saveCodeCheckpoint(0, "turn one");
        Path cpDir = store.dir().resolve("s1").resolve(CheckpointDiskStore.DIR_NAME);
        assertTrue(Files.isDirectory(cpDir));

        // Switch away so the active session can be deleted.
        e.loadSession("s2");
        e.deleteSession("s1");
        assertFalse(Files.exists(cpDir),
                "the code snapshots of a deleted session are unreachable garbage");
    }

    // ------------------------------------------------------------------
    // the cancel point
    // ------------------------------------------------------------------

    @Test
    void theCancelPointIsMarkedAtTheCancelledTurn() {
        Path cwd = root.resolve("work");
        AetherCodeEngine e = engine(cwd, null, "s1");
        e.appState().appendMessage(Message.userText("turn one"));
        e.appState().appendMessage(Message.assistantText("a reply"));
        e.appState().appendMessage(Message.userText("turn two"));

        var marked = e.markCancelCheckpoint("test");
        assertTrue(marked.isPresent(), "the cancel must leave a point behind");
        var cp = marked.get();
        assertEquals(CodeCheckpointStore.KIND_CANCEL, cp.kind());
        assertTrue(cp.isCancelPoint());
        // The LAST real user turn is the one that was interrupted.
        assertEquals(1, cp.userMessageIndex(),
                "the cancelled turn is the second one");
        assertEquals(2, cp.transcriptIndex(),
                "the anchor must be the cancelled turn's user message");
    }

    @Test
    void theCancelPointNamesTheFilesTheSessionWrote() throws Exception {
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        Path f = cwd.resolve("a.txt");
        Files.writeString(f, "HALF DONE", StandardCharsets.UTF_8);

        AetherCodeEngine e = engine(cwd, null, "s1");
        e.appState().appendMessage(Message.userText("turn one"));
        e.appState().appendMessage(wroteFile(f.toString()));

        var marked = e.markCancelCheckpoint("test");
        assertTrue(marked.isPresent());
        var cp = marked.get();
        assertEquals(1, cp.files().size(),
                "the point must cover the file the session wrote");
        assertEquals("HALF DONE", cp.files().get(0).content(),
                "the cancel point records the HALF-FINISHED state, not the state before the turn");
    }

    @Test
    void aCancelWithNoUserTurnLeavesNoPoint() {
        AetherCodeEngine e = engine(root.resolve("work"), null, "s1");
        var marked = e.markCancelCheckpoint("test");
        assertTrue(marked.isEmpty(),
                "with no user turn there is nothing to anchor the point to");
    }
}
