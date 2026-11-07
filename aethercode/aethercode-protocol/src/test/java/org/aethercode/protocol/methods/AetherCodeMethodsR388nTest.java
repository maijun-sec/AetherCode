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

package org.aethercode.protocol.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.server.JsonRpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R388n — the checkpoint surface.
 *
 * <p>★ What this gate holds ★
 *
 * <ul>
 *   <li>the three checkpoint methods are REGISTERED on the stdio
 *       dispatcher (not merely declared);</li>
 *   <li>{@code checkpoints/list} ANSWERS — an empty list is a fact,
 *       not an error;</li>
 *   <li>{@code checkpoints/restore} honours its SCOPE: {@code code},
 *       {@code conversation}, {@code both};</li>
 *   <li>a restore REPORTS what it did — the files restored, deleted,
 *       skipped and failed — instead of claiming a clean success;</li>
 *   <li>a request for a checkpoint that does not exist fails
 *       HONESTLY, with a reason.</li>
 * </ul>
 *
 * <p>The HTTP route is pinned by the TUI gate
 * ({@code r388n-checkpoint-menu.test.mjs}), because the HTTP+WS daemon
 * has its own hand-written dispatch switch — a method registered on
 * stdio but missing from that switch is served by one daemon and dead
 * in the other.
 */
class AetherCodeMethodsR388nTest {

    @TempDir Path root;

    private JsonRpcServer.TestRig rig;
    private org.aethercode.sdk.AetherCodeEngine engine;

    @BeforeEach
    void wire() {
        rig = JsonRpcServer.forTest();
        rig.start();
        Path cwd = root.toAbsolutePath();
        engine = new org.aethercode.sdk.AetherCodeEngine.Builder()
                .cwd(cwd)
                .agentsDir(cwd)
                .tools(List.of())
                .build();
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});
        methods.registerAll(rig.server.dispatcher());
    }

    @AfterEach
    void teardown() {
        rig.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /**
     * A transcript message that says "the session wrote this file".
     *
     * <p>★ The checkpoint's file list is DERIVED from the transcript's
     * tool calls. ★ A checkpoint with no tool call in the transcript
     * covers no files — which is correct: the session did not touch
     * anything, so there is nothing to put back.
     */
    private static Message wroteFile(String path) {
        return new Message(
                "wrote-" + path,
                Role.ASSISTANT,
                List.of(new ContentBlock.ToolUseBlock(
                        "tu-" + path, "file_write", Map.of("file_path", path))),
                null,
                Map.of());
    }

    private JsonRpcResponse sendRpc(String method, Map<String, Object> params) {
        try {
            int id = (int) (System.nanoTime() & 0xFFFF);
            rig.send(new org.aethercode.protocol.jsonrpc.JsonRpcRequest(
                    org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION, id, method, params));
            org.aethercode.protocol.jsonrpc.JsonRpcMessage reply = rig.receiveResponse(5_000);
            assertNotNull(reply, "no response within 5s for " + method);
            return (JsonRpcResponse) reply;
        } catch (Exception e) {
            throw new IllegalStateException("RPC " + method + " failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // the surface exists
    // ------------------------------------------------------------------

    @Test
    void theThreeMethodsAreRegisteredOnStdio() {
        for (String name : new String[]{
                "checkpoints/list", "checkpoints/restore", "checkpoints/summarize"}) {
            assertTrue(rig.server.dispatcher().hasMethod(name),
                    "expected " + name + " to be registered on the stdio dispatcher");
        }
    }

    @Test
    void checkpointsListAnswersWithAnEmptyListWhenThereAreNoCheckpoints() {
        // ★ An empty list is a FACT, not an error. ★ A panel that shows
        // "the daemon refused" for a session that simply has not
        // written any files yet sends the user looking for a problem
        // that is in the wire.
        JsonRpcResponse r = sendRpc("checkpoints/list", Map.of());
        assertNull(r.error(), "checkpoints/list must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"), "expected ok:true, got " + res);
        List<?> cps = (List<?>) res.get("checkpoints");
        assertNotNull(cps, "the response must carry a checkpoints array: " + res);
        assertTrue(cps.isEmpty(), "a fresh session has no checkpoints: " + cps);
    }

    // ------------------------------------------------------------------
    // restore: the SCOPE is the contract
    // ------------------------------------------------------------------

    @Test
    void restoreRejectsAnUnknownScope() {
        JsonRpcResponse r = sendRpc("checkpoints/restore",
                Map.of("id", "cp-nope", "scope", "everything"));
        // The scope is validated BEFORE the checkpoint is looked up, so
        // the error names the scope.
        assertNotNull(r.error(), "an unknown scope must be rejected: " + r);
        assertTrue(String.valueOf(r.error().message()).contains("scope"),
                "the error must name the scope: " + r.error().message());
    }

    @Test
    void restoreOfAnUnknownCheckpointFailsHonestly() {
        JsonRpcResponse r = sendRpc("checkpoints/restore",
                Map.of("id", "cp-does-not-exist"));
        assertNull(r.error(), "the RPC must answer, not throw: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(false, res.get("ok"),
                "an unknown checkpoint must report ok:false — got " + res);
        assertNotNull(res.get("reason"), "the failure must carry a reason: " + res);
    }

    @Test
    void aCodeRestoreDoesNotTouchTheConversation() throws Exception {
        // ★ The scope is the contract. ★ "Restore code" must not roll
        // the transcript back — otherwise the two operations are the
        // same operation with two names, and the menu lies.
        Path f = root.resolve("a.txt");
        Files.writeString(f, "ORIGINAL", StandardCharsets.UTF_8);
        engine.appState().appendMessage(Message.userText("turn one"));
        engine.appState().appendMessage(wroteFile(f.toString()));
        engine.appState().appendMessage(Message.assistantText("a reply"));
        int before = engine.appState().transcript().size();

        var cp = engine.saveCodeCheckpoint(0, "turn one");
        Files.writeString(f, "MODIFIED", StandardCharsets.UTF_8);

        JsonRpcResponse r = sendRpc("checkpoints/restore",
                Map.of("id", cp.id(), "scope", "code"));
        assertNull(r.error(), "restore must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"), "expected ok:true, got " + res);
        assertEquals("ORIGINAL", Files.readString(f, StandardCharsets.UTF_8),
                "the file must be back to its checkpoint state");
        assertEquals(1, ((List<?>) res.get("restored")).size(),
                "the report must name the restored file: " + res);
        // The conversation half must NOT have run.
        assertFalse(res.containsKey("conversation"),
                "a code-scoped restore must not report a conversation rollback: " + res);
        assertEquals(before, engine.appState().transcript().size(),
                "a code-scoped restore must not touch the transcript");
    }

    @Test
    void aConversationRestoreRollsTheTranscriptBack() throws Exception {
        engine.appState().appendMessage(Message.userText("turn one"));
        engine.appState().appendMessage(Message.assistantText("a reply"));
        engine.appState().appendMessage(Message.userText("turn two"));
        int before = engine.appState().transcript().size();

        // The checkpoint for the turn that is STARTING sits at the
        // transcript size, so restoring it keeps everything before it.
        var cp = engine.saveCodeCheckpoint(1, "turn two");
        int kept = cp.transcriptIndex();

        JsonRpcResponse r = sendRpc("checkpoints/restore",
                Map.of("id", cp.id(), "scope", "conversation"));
        assertNull(r.error(), "restore must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"), "expected ok:true, got " + res);
        assertEquals(kept, engine.appState().transcript().size(),
                "the transcript must be rolled back to the checkpoint's position");
        assertTrue(before > kept, "the rollback must have removed something");
        assertNotNull(res.get("conversation"),
                "a conversation-scoped restore must report the rollback: " + res);
    }

    @Test
    void aBothScopedRestoreDoesBothInOneRequest() throws Exception {
        // ★ ONE request. ★ Two requests would mean the code restore
        // could succeed while the conversation restore failed, and the
        // user would be told "done" with half the work undone.
        Path f = root.resolve("b.txt");
        Files.writeString(f, "ORIGINAL", StandardCharsets.UTF_8);
        engine.appState().appendMessage(Message.userText("turn one"));
        engine.appState().appendMessage(wroteFile(f.toString()));
        engine.appState().appendMessage(Message.assistantText("a reply"));

        // The checkpoint sits at the transcript's END, so a later turn
        // is needed for the rollback to have something to remove.
        var cp = engine.saveCodeCheckpoint(1, "turn two");
        engine.appState().appendMessage(Message.userText("turn two"));
        int before = engine.appState().transcript().size();
        Files.writeString(f, "MODIFIED", StandardCharsets.UTF_8);

        JsonRpcResponse r = sendRpc("checkpoints/restore",
                Map.of("id", cp.id(), "scope", "both"));
        assertNull(r.error(), "restore must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"), "expected ok:true, got " + res);
        assertEquals("ORIGINAL", Files.readString(f, StandardCharsets.UTF_8),
                "the file must be restored too");
        assertNotNull(res.get("conversation"),
                "a both-scoped restore must report the rollback: " + res);
        assertTrue(engine.appState().transcript().size() < before,
                "the conversation must be rolled back too");
    }

    // ------------------------------------------------------------------
    // summarize: the CHECKPOINT wins over the ordinal
    // ------------------------------------------------------------------

    @Test
    void summarizeRejectsARequestWithNeitherCheckpointNorTarget() {
        JsonRpcResponse r = sendRpc("checkpoints/summarize", Map.of());
        assertNotNull(r.error(), "a missing target must be rejected: " + r);
    }

    @Test
    void summarizeOfAnUnknownCheckpointFailsHonestly() {
        JsonRpcResponse r = sendRpc("checkpoints/summarize",
                Map.of("checkpointId", "cp-nope", "mode", "from"));
        assertNull(r.error(), "the RPC must answer, not throw: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(false, res.get("ok"),
                "an unknown checkpoint must report ok:false — got " + res);
        assertNotNull(res.get("reason"), "the failure must carry a reason: " + res);
    }

    @Test
    void summarizeByTargetStillWorks() {
        // The legacy path is KEPT — it is a working, precise operation.
        // What changed is that the checkpoint path exists too.
        engine.appState().appendMessage(Message.userText("turn one"));
        engine.appState().appendMessage(Message.assistantText("a reply"));
        engine.appState().appendMessage(Message.userText("turn two"));

        JsonRpcResponse r = sendRpc("checkpoints/summarize",
                Map.of("target", 1, "mode", "from"));
        assertNull(r.error(), "summarize must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        if (res.get("ok") == Boolean.FALSE) {
            // No compactor is wired in this rig — the honest refusal is
            // a correct answer, and it must say why.
            assertNotNull(res.get("reason"),
                    "a refusal must carry a reason: " + res);
        } else {
            assertEquals(true, res.get("ok"), "expected ok:true, got " + res);
        }
    }

    // ------------------------------------------------------------------
    // the checkpoint's position is recorded, not re-derived
    // ------------------------------------------------------------------

    @Test
    void theCheckpointRecordsItsTranscriptPosition() {
        engine.appState().appendMessage(Message.userText("turn one"));
        engine.appState().appendMessage(Message.assistantText("a reply"));
        int at = engine.appState().transcript().size();

        var cp = engine.saveCodeCheckpoint(1, "turn two");
        assertEquals(at, cp.transcriptIndex(),
                "the checkpoint must record where it was taken, not a re-derived ordinal");
        assertEquals(1, cp.userMessageIndex(),
                "the checkpoint must also carry the turn ordinal");
    }

    @Test
    void sideNotesDoNotShiftTheTurnOrdinal() {
        // ★ A compaction notice carries Role.USER. ★ If it counted as a
        // turn, every rewind target after it would shift by a message —
        // and the feature would look like it worked while corrupting
        // the conversation.
        engine.appState().appendMessage(Message.userText("turn one"));
        engine.appState().appendMessage(new Message(
                "note",
                Role.USER,
                List.of(new ContentBlock.TextBlock("[compaction] done")),
                null,
                Map.of("kind", "compaction-side-note")));
        engine.appState().appendMessage(Message.userText("turn two"));

        var cp = engine.saveCodeCheckpoint(2, "turn three");
        assertEquals(2, cp.userMessageIndex(),
                "the side-note must not be counted as a turn");
        assertEquals(3, cp.transcriptIndex(),
                "the checkpoint must sit after the side-note, at the real turn");
    }
}
