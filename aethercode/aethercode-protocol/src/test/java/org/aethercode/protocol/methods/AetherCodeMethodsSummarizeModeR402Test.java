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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aethercode.core.compact.Compactor;
import org.aethercode.core.message.Message;
import org.aethercode.core.transcript.CodeCheckpointStore;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R402 — what the menu's TWO summarize buttons actually do.
 *
 * <h2>★ WHY THIS GATE HOLDS ★</h2>
 *
 * <p>The rewind menu offers "summarize from here" and "summarize up to
 * here". Before this round the DESKTOP had no menu at all, and the
 * daemon's own javadoc described the two modes as mirror images:
 * "the summarised side flipped", {@code up_to} = "keep the head".
 *
 * <p><b>That was wrong, and it was wrong in the copy a reader trusts.</b>
 * The implementation has always done the same thing in both modes — the
 * HEAD is summarised and the TAIL survives. The ONLY difference is the
 * boundary: {@code from} splits BEFORE the point's own message (so that
 * message survives) and {@code up_to} splits AFTER it (so that message
 * is folded into the summary).
 *
 * <p>Two copies of the truth already disagreed — the method's inline
 * comment and the TUI's menu comment both said "the head is what gets
 * summarised", while the javadoc paragraphs said the opposite. A third
 * copy (the desktop menu) was about to be written against the javadoc.
 *
 * <ul>
 *   <li><b>"from" keeps the point's own message.</b> The user asked to
 *       go back to this point and keep talking from it — the message
 *       they are looking at must survive.</li>
 *   <li><b>"up to" folds the point's own message into the summary.</b>
 *       The boundary is inclusive.</li>
 *   <li><b>NEITHER mode keeps the head.</b> This is the direct inverse
 *       of what the javadoc claimed, and it is the half that a
 *       "keep the head" implementation would silently get wrong.</li>
 * </ul>
 *
 * <p>★ THE GATE IS BEHAVIOUR, NOT A SOURCE PIN ★ it drives the real
 * RPC with a recording compactor and reads the transcript that comes
 * back, so a refactor that flips the boundary turns this red while a
 * rename of a local variable does not.
 */
class AetherCodeMethodsSummarizeModeR402Test {

    /**
     * A compactor that RECORDS the head it was asked to summarise and
     * returns one summary message.
     *
     * <p>★ WHY A FAKE ★ the real summariser needs an LLM. The thing
     * under test is WHICH MESSAGES reach the summariser — so the fake
     * records them and the gate asserts on the record, not on a model.
     */
    private static final class RecordingCompactor implements Compactor {
        final List<Message> summarized = new ArrayList<>();

        @Override
        public boolean shouldCompact(List<Message> messages) {
            return false;
        }

        @Override
        public List<Message> compact(List<Message> messages) {
            summarized.addAll(messages);
            return List.of(Message.assistantText("SUMMARY"));
        }
    }

    private static final class Rig {
        final AetherCodeEngine engine;
        final AetherCodeMethods methods;
        final RecordingCompactor compactor = new RecordingCompactor();
        final List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();

        Rig(java.nio.file.Path cwd) {
            engine = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .agentsDir(cwd)
                    .tools(List.of())
                    .compactor(compactor)
                    .build();
            methods = new AetherCodeMethods(engine, notifications::add);
        }

        /** Four messages: two turns, each a user message + a reply. */
        void seed() {
            engine.appState().appendMessage(Message.userText("turn one"));
            engine.appState().appendMessage(Message.assistantText("reply one"));
            engine.appState().appendMessage(Message.userText("turn two"));
            engine.appState().appendMessage(Message.assistantText("reply two"));
        }

        /** The point at the SECOND turn — its boundary is "turn two". */
        CodeCheckpointStore.Checkpoint pointAtSecondTurn() {
            CodeCheckpointStore.Checkpoint cp = engine.saveCodeCheckpoint(1, "turn two");
            assertEquals(2, cp.transcriptIndex(),
                    "the point's boundary must be the second turn's user message");
            return cp;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<String> texts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message m : messages) out.add(m.textContent());
        return out;
    }

    private static boolean contains(List<Message> messages, String text) {
        return texts(messages).contains(text);
    }

    // ------------------------------------------------------------------
    // "summarize from here"
    // ------------------------------------------------------------------

    @Test
    void summarizeFromHereKeepsThePointsOwnMessage(@TempDir java.nio.file.Path root)
            throws Exception {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();
        CodeCheckpointStore.Checkpoint cp = rig.pointAtSecondTurn();

        Map<String, Object> res = asMap(rig.methods.checkpointsSummarize(
                Map.of("checkpointId", cp.id(), "mode", "from")));
        assertEquals(true, res.get("ok"), "the summarise must succeed: " + res);

        // ★ THE BOUNDARY SURVIVES ★ the user is looking at "turn two"
        // and asked to summarise everything BEFORE it.
        assertTrue(contains(rig.engine.appState().transcript(), "turn two"),
                "\"from\" must KEEP the point's own message — the transcript is: "
                        + texts(rig.engine.appState().transcript()));
        assertFalse(contains(rig.compactor.summarized, "turn two"),
                "\"from\" must NOT summarise the point's own message — it saw: "
                        + texts(rig.compactor.summarized));
    }

    @Test
    void summarizeFromHereSummarisesTheHeadAndKeepsTheTail(@TempDir java.nio.file.Path root)
            throws Exception {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();
        CodeCheckpointStore.Checkpoint cp = rig.pointAtSecondTurn();

        Map<String, Object> res = asMap(rig.methods.checkpointsSummarize(
                Map.of("checkpointId", cp.id(), "mode", "from")));

        // the summariser got the HEAD
        assertTrue(contains(rig.compactor.summarized, "turn one"),
                "\"from\" must summarise the earlier turn: " + texts(rig.compactor.summarized));
        assertTrue(contains(rig.compactor.summarized, "reply one"),
                "\"from\" must summarise the earlier turn: " + texts(rig.compactor.summarized));
        // and the transcript keeps the TAIL
        assertTrue(contains(rig.engine.appState().transcript(), "reply two"),
                "\"from\" must keep the later turn: "
                        + texts(rig.engine.appState().transcript()));
        assertEquals(2, res.get("kept"),
                "the tail is two messages (the point's message + its reply): " + res);
        assertEquals(3, res.get("transcriptSize"),
                "one summary + two kept: " + res);
    }

    // ------------------------------------------------------------------
    // "summarize up to here"
    // ------------------------------------------------------------------

    @Test
    void summarizeUpToHereFoldsThePointsOwnMessageIntoTheSummary(
            @TempDir java.nio.file.Path root) throws Exception {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();
        CodeCheckpointStore.Checkpoint cp = rig.pointAtSecondTurn();

        Map<String, Object> res = asMap(rig.methods.checkpointsSummarize(
                Map.of("checkpointId", cp.id(), "mode", "up_to")));
        assertEquals(true, res.get("ok"), "the summarise must succeed: " + res);

        // ★ THE BOUNDARY IS INCLUSIVE ★
        assertTrue(contains(rig.compactor.summarized, "turn two"),
                "\"up to\" must summarise the point's own message too — it saw: "
                        + texts(rig.compactor.summarized));
        assertFalse(contains(rig.engine.appState().transcript(), "turn two"),
                "\"up to\" must NOT leave the point's own message in the transcript — it is: "
                        + texts(rig.engine.appState().transcript()));
    }

    /**
     * ★ THE INVERSE OF THE OLD JAVADOC ★
     *
     * <p>The javadoc said {@code up_to} "keeps the head". It does not:
     * the head is summarised in BOTH modes, and the tail survives in
     * both. If an implementation ever really kept the head, this gate
     * turns red — which is the whole point of writing it.
     */
    @Test
    void summarizeUpToHereDoesNotKeepTheHead(@TempDir java.nio.file.Path root)
            throws Exception {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();
        CodeCheckpointStore.Checkpoint cp = rig.pointAtSecondTurn();

        Map<String, Object> res = asMap(rig.methods.checkpointsSummarize(
                Map.of("checkpointId", cp.id(), "mode", "up_to")));

        assertFalse(contains(rig.engine.appState().transcript(), "turn one"),
                "\"up to\" must NOT keep the head — the transcript is: "
                        + texts(rig.engine.appState().transcript()));
        assertFalse(contains(rig.engine.appState().transcript(), "reply one"),
                "\"up to\" must NOT keep the head — the transcript is: "
                        + texts(rig.engine.appState().transcript()));
        assertTrue(contains(rig.engine.appState().transcript(), "reply two"),
                "\"up to\" must keep the tail — the transcript is: "
                        + texts(rig.engine.appState().transcript()));
        assertEquals(1, res.get("kept"),
                "the tail is ONE message — the point's own message went into the summary: " + res);
        assertEquals(2, res.get("transcriptSize"),
                "one summary + one kept: " + res);
    }

    // ------------------------------------------------------------------
    // the two modes differ by EXACTLY the boundary message
    // ------------------------------------------------------------------

    @Test
    void theTwoModesDifferByExactlyTheBoundaryMessage(@TempDir java.nio.file.Path root)
            throws Exception {
        Rig from = new Rig(root.toAbsolutePath().resolve("a"));
        from.seed();
        CodeCheckpointStore.Checkpoint cpFrom = from.pointAtSecondTurn();
        asMap(from.methods.checkpointsSummarize(
                Map.of("checkpointId", cpFrom.id(), "mode", "from")));

        Rig upTo = new Rig(root.toAbsolutePath().resolve("b"));
        upTo.seed();
        CodeCheckpointStore.Checkpoint cpUpTo = upTo.pointAtSecondTurn();
        asMap(upTo.methods.checkpointsSummarize(
                Map.of("checkpointId", cpUpTo.id(), "mode", "up_to")));

        List<String> a = texts(from.engine.appState().transcript());
        List<String> b = texts(upTo.engine.appState().transcript());
        assertEquals(3, a.size(), "\"from\" leaves three messages: " + a);
        assertEquals(2, b.size(), "\"up to\" leaves two messages: " + b);
        assertTrue(a.contains("turn two") && !b.contains("turn two"),
                "the ONLY difference must be the boundary message: " + a + " vs " + b);
        // everything else is identical
        assertEquals(a.get(0), b.get(0), "both keep the same summary: " + a + " vs " + b);
        assertEquals("reply two", a.get(a.size() - 1), "the tail is the same: " + a);
        assertEquals("reply two", b.get(b.size() - 1), "the tail is the same: " + b);
    }

    // ------------------------------------------------------------------
    // the ORDINAL path (the other caller of the same primitive)
    // ------------------------------------------------------------------

    @Test
    void theOrdinalPathBehavesTheSame(@TempDir java.nio.file.Path root) throws Exception {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();

        Map<String, Object> res = asMap(rig.methods.checkpointsSummarize(
                Map.of("target", 1, "mode", "up_to")));
        assertEquals(true, res.get("ok"), "the summarise must succeed: " + res);
        assertFalse(contains(rig.engine.appState().transcript(), "turn two"),
                "the ordinal path must fold the boundary in too — the transcript is: "
                        + texts(rig.engine.appState().transcript()));
        assertTrue(contains(rig.engine.appState().transcript(), "reply two"),
                "the ordinal path must keep the tail — the transcript is: "
                        + texts(rig.engine.appState().transcript()));
    }
}
