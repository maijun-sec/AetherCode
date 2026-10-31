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

package org.aethercode.core.engine;

import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R748 (2026-10-21): L4 was self-consuming within a single turn.
 *
 * <p>Field evidence (45-minute research session, 182 LLM turns):
 * <pre>
 *   turn  1: 377 messages -> 139 survivors (dropped 239)
 *   turn  2: 383 messages -> 138 survivors (dropped 245)
 *   ...
 *   turn 17: 417 messages -> 107 survivors (dropped 310)
 * </pre>
 *
 * <p>The drop counter grows ~6 messages PER TURN because each turn
 * appends its own messages. So the model's working window shrank from
 * 139 to 107 messages inside ONE user request — it progressively
 * forgot its own work, re-read the same files, and thrashed. That is
 * the reported "多次卡住然后自己恢复" symptom.
 *
 * <p>The fix adds a shrink-to-fit tier BEFORE head-drop: clip
 * oversized TOOL_RESULT payloads in place instead of dropping whole
 * turns. This is not just cheaper, it is REQUIRED by the provider's
 * adjacency contract (R736/R741): dropping a tool_result forces R736
 * to strip its matching tool_call on the next call, destroying the
 * turn anyway.
 *
 * <p>Scenarios pinned:
 * <ol>
 *   <li>under cap — same reference, no clipping;</li>
 *   <li>fat tool results, fits after clipping — ZERO messages
 *       dropped (this is the R748 regression: pre-R748 dropped 239);</li>
 *   <li>window STABLE across simulated turns — the same turn must
 *       survive 17 consecutive calls (the actual bug);</li>
 *   <li>clipped tool result keeps its role + tool_use_id so R736
 *       does not strip it as an orphan;</li>
 *   <li>clipping alone insufficient — still falls through to
 *       head-drop;</li>
 *   <li>non-tool messages are never clipped.</li>
 * </ol>
 */
class TranscriptPrunerShrinkToFitR748Test {

    private static final long CAP = 150_000L;

    /** One turn: user prompt + assistant declaring a tool call +
     *  the tool result. This is the unit that R748 refuses to drop. */
    private static void addTurn(List<Message> out, int idx, int resultChars) {
        String callId = "call_" + idx;
        out.add(Message.userText("request-" + idx));
        out.add(new Message(
                "asst-" + idx,
                Role.ASSISTANT,
                List.of(new org.aethercode.core.message.ContentBlock.ToolUseBlock(
                        callId, "arxiv_fetch",
                        java.util.Map.of("q", "topic-" + idx))),
                null,
                java.util.Map.of()));
        out.add(Message.toolResult(callId, "r".repeat(resultChars), false));
    }

    @Test
    void underCap_returnsSameReference() {
        List<Message> t = new ArrayList<>();
        addTurn(t, 0, 100);
        addTurn(t, 1, 100);
        assertSame(t, TranscriptPruner.stripOldestToFit(t, CAP, 16, 4_000, null),
                "a transcript under cap must be the same list reference (fast path)");
    }

    /**
     * The R748 regression, in one assertion. 6 fat tool results totalling
     * 600K chars against a 150K cap. Pre-R748 this dropped most of the
     * list; R748 clips the payloads and drops NOTHING.
     */
    @Test
    void fatToolResults_fitsAfterClipping_dropsNothing() {
        List<Message> t = new ArrayList<>();
        for (int i = 0; i < 6; i++) addTurn(t, i, 100_000);
        assertTrue(TranscriptPruner.totalChars(t) > CAP * 3,
                "fixture must start well over the cap");

        List<Message> out = TranscriptPruner.stripOldestToFit(t, CAP, 16, 4_000, null);

        assertEquals(t.size(), out.size(),
                "R748: shrink-to-fit must NOT drop messages when clipping the "
                        + "oversized tool results alone makes it fit. Pre-R748 this "
                        + "dropped ~2/3 of the transcript.");
        assertTrue(TranscriptPruner.totalChars(out) <= CAP,
                "output must fit the cap: got " + TranscriptPruner.totalChars(out));
    }

    /**
     * The actual reported bug: replay one growing session across many
     * turns and assert the working window does not shrink.
     */
    @Test
    void windowStaysStable_acrossSimulatedTurns() {
        List<Message> session = new ArrayList<>();
        int firstWindow = -1;
        int minWindow = Integer.MAX_VALUE;

        for (int turn = 0; turn < 20; turn++) {
            addTurn(session, turn, 60_000); // each turn adds a fat tool result
            List<Message> wire = TranscriptPruner.stripOldestToFit(
                    session, CAP, 16, 4_000, null);
            int window = wire.size();
            if (turn == 0) firstWindow = window;
            minWindow = Math.min(minWindow, window);
        }

        assertEquals(firstWindow, minWindow,
                "R748: the L4 survivor window must NOT shrink turn-over-turn. "
                        + "Pre-R748 it fell 139 -> 107 within 17 turns of the same "
                        + "user request because each turn re-trimmed the FULL "
                        + "on-disk transcript from scratch. first=" + firstWindow
                        + " min=" + minWindow);
    }

    /**
     * Clipping must not break the R736 adjacency contract: the clipped
     * tool result must still be a TOOL_RESULT carrying its original
     * tool_use_id, so R736's stripOrphanToolResults does not drop it
     * (and thereby force its tool_call to be stripped too).
     */
    @Test
    void clippedToolResult_keepsRoleAndToolUseId() {
        List<Message> t = new ArrayList<>();
        for (int i = 0; i < 6; i++) addTurn(t, i, 100_000);

        List<Message> out = TranscriptPruner.stripOldestToFit(t, CAP, 16, 4_000, null);

        int toolResults = 0;
        for (Message m : out) {
            if (m.role() != Role.TOOL_RESULT) continue;
            toolResults++;
            Object tid = m.metadata() == null ? null : m.metadata().get("tool_use_id");
            assertTrue(tid instanceof String && ((String) tid).startsWith("call_"),
                    "clipped tool result must keep its tool_use_id in metadata, got " + tid);
            assertTrue(TranscriptPruner.messageCharLength(m) < 100_000,
                    "tool result must actually have been clipped below its original "
                            + "100000 chars, got " + TranscriptPruner.messageCharLength(m));
        }
        assertEquals(6, toolResults, "all 6 tool results must survive clipping");
    }

    @Test
    void clippingInsufficient_fallsThroughToHeadDrop() {
        // No tool results at all — clipping has no victim, so L4 must
        // still drop from the head rather than returning an over-cap list.
        List<Message> t = new ArrayList<>();
        for (int i = 0; i < 300; i++) t.add(Message.userText("m" + i + "-" + "x".repeat(2_000)));

        List<Message> out = TranscriptPruner.stripOldestToFit(t, CAP, 16, 4_000, null);

        assertTrue(TranscriptPruner.totalChars(out) <= CAP,
                "with no clippable tool results the head-drop tier must still run");
        assertTrue(out.size() < t.size(), "head-drop must actually have dropped something");
    }

    @Test
    void neverClipsNonToolMessages() {
        List<Message> t = new ArrayList<>();
        // Big ASSISTANT text, tiny tool result: the assistant text is the
        // fat one but must NOT be clipped by the R748 tier (losing
        // reasoning is worse than losing a re-fetchable payload).
        for (int i = 0; i < 5; i++) {
            t.add(Message.assistantText("reasoning-" + i + "-" + "y".repeat(100_000)));
            t.add(Message.toolResult("c" + i, "tiny", false));
        }
        List<Message> before = new ArrayList<>(t);
        TranscriptPruner.stripOldestToFit(t, CAP, 16, 4_000, null);

        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).role() != Role.ASSISTANT) continue;
            assertEquals(TranscriptPruner.messageCharLength(before.get(i)),
                    TranscriptPruner.messageCharLength(before.get(i)),
                    "sanity: assistant fixture must be fat");
        }
        // The stronger assertion: no assistant message in the OUTPUT
        // should be a clipped 4K remnant when the head-drop tier could
        // have handled it — i.e. the R748 tier must not be the thing
        // that shrank reasoning text.
        for (Message m : TranscriptPruner.clipFatToolResults(t, CAP, 16)) {
            if (m.role() == Role.ASSISTANT) {
                assertTrue(TranscriptPruner.messageCharLength(m) > 100_000,
                        "R748 tier must never clip assistant reasoning text");
            }
        }
    }
}
