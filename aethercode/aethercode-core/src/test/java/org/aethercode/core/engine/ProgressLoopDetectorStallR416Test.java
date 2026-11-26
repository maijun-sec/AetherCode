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

import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R416 — the STALL detector: the bound that replaced the turn cap does
 * not cover a turn without todos.
 *
 * <h2>★ WHY THIS TEST EXISTS ★</h2>
 * <p>R83 removed the per-query turn cap and named {@code TodoRunController}
 * as the replacement ("the source of truth for 'this is taking too long'").
 * The todo controller bounds a turn that HAS a todo list. A research turn
 * has none — and R415's live matrix caught exactly that: scenario K9
 * fetched the SAME document from twenty mirrors and kept going. Nothing
 * bounded that turn.
 *
 * <p>The bound R416 adds is measured in INFORMATION, not in turns: a turn
 * that tried something new and got the same answer back is not making
 * progress, however different the calls look.
 *
 * <h2>★ WHAT MUST NOT HAPPEN ★</h2>
 * <ul>
 *   <li>a turn that repeats a call the window already knows is the
 *       CALL loop's business (R171: warn, don't end the run) — the stall
 *       must not take that policy away from the pattern that owns it;</li>
 *   <li>a turn that keeps LEARNING (every result is new) must never
 *       fire, however long the turn runs;</li>
 *   <li>the engine's PRE-batch call (no results yet) must not count —
 *       otherwise every turn looks information-free.</li>
 * </ul>
 */
class ProgressLoopDetectorStallR416Test {

    // ------------------------------------------------------------------
    // the detector's own behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: new calls that keep returning the same answer are a stall")
    void newCallsSameAnswerIsAStall() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .noNewInfoThreshold(3)
                .build();
        // Six turns, each with a DIFFERENT call (so the call-level
        // fingerprint pattern never fires) and the SAME result (so no
        // turn brings anything new).
        ProgressLoopDetector.LoopInfo stop = null;
        for (int i = 1; i <= 6; i++) {
            ProgressLoopDetector.LoopInfo info = d.recordBatch(
                    List.of(call("call-" + i, "fetch", Map.of("url", "https://mirror-" + i + ".example/doc"))),
                    List.of(new ProgressLoopDetector.BatchResult(
                            "call-" + i, "fetch", "THE SAME DOCUMENT", false)),
                    0);
            if (info != null && info.shouldStop()) stop = info;
        }
        assertNotNull(stop, "six turns of 'a different call, the same answer' must end in a hard stop");
        assertEquals("loop_detected", stop.kind(),
                "the stall's hard stop must use the EXISTING loop kind so no client needs a new word");
        assertEquals("no_new_information", d.lastLoopKind(),
                "the specific cause travels in lastLoopKind (R385: a detector's kind is not a stop reason)");
        assertTrue(d.noNewInfoStreak() >= d.noNewInfoStopStreak(),
                "the streak must have reached the stop threshold, was " + d.noNewInfoStreak());
    }

    @Test
    @DisplayName("R416: a turn that keeps learning never stalls")
    void learningNeverStalls() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .noNewInfoThreshold(3)
                .build();
        for (int i = 1; i <= 12; i++) {
            ProgressLoopDetector.LoopInfo info = d.recordBatch(
                    List.of(call("call-" + i, "read", Map.of("file", "src/File" + i + ".java"))),
                    List.of(new ProgressLoopDetector.BatchResult(
                            "call-" + i, "read", "contents of file " + i, false)),
                    0);
            assertNull(info, "turn " + i + " brought new information — the detector must stay quiet, got " + info);
        }
        assertEquals(0, d.noNewInfoStreak(),
                "new information resets the stall streak");
    }

    @Test
    @DisplayName("R416: repeating a call the window knows is the CALL loop's business, not a stall")
    void repeatedCallIsNotAStall() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .noNewInfoThreshold(3)
                .build();
        for (int i = 1; i <= 8; i++) {
            ProgressLoopDetector.LoopInfo info = d.recordBatch(
                    List.of(call("call-" + i, "echo", Map.of("msg", "tick"))),
                    List.of(new ProgressLoopDetector.BatchResult(
                            "call-" + i, "echo", "ok", false)),
                    0);
            if (info != null) {
                assertNotEquals("no_new_information", d.lastLoopKind(),
                        "a repeated CALL is the call-level pattern's business (R171: warn, don't stop); "
                                + "the stall must not take that policy away");
            }
        }
        assertEquals(0, d.noNewInfoStreak(),
                "a turn that only repeats known calls must not advance the stall streak");
    }

    @Test
    @DisplayName("R416: a write is progress — the stall streak resets")
    void aWriteIsProgress() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .noNewInfoThreshold(3)
                .build();
        // Two stalling turns, then a write.
        for (int i = 1; i <= 2; i++) {
            d.recordBatch(
                    List.of(call("call-" + i, "fetch", Map.of("url", "https://m" + i + ".example/d"))),
                    List.of(new ProgressLoopDetector.BatchResult(
                            "call-" + i, "fetch", "THE SAME DOCUMENT", false)),
                    0);
        }
        assertTrue(d.noNewInfoStreak() > 0, "precondition: the streak grew");
        d.recordBatch(
                List.of(call("call-w", "file_write", Map.of("file_path", "src/A.java"))),
                List.of(new ProgressLoopDetector.BatchResult(
                        "call-w", "file_write", "wrote 12 bytes to src/A.java", false)),
                0);
        assertEquals(0, d.noNewInfoStreak(),
                "a write changed the tree — the stall streak must reset");
    }

    @Test
    @DisplayName("R416: the engine's PRE-batch call (no results yet) does not advance the stall")
    void preBatchCallDoesNotAdvanceTheStall() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .noNewInfoThreshold(3)
                .build();
        for (int i = 1; i <= 6; i++) {
            // The engine calls recordBatch TWICE per turn: once before the
            // tools run (empty outputs) and once after. Only the second
            // call can answer "did this turn bring anything new".
            d.recordBatch(
                    List.of(call("call-" + i, "fetch", Map.of("url", "https://m" + i + ".example/d"))),
                    List.of(),
                    0);
        }
        assertEquals(0, d.noNewInfoStreak(),
                "a call with no results carries no information about progress and must not count");
    }

    @Test
    @DisplayName("R416: reset() clears the stall state — one query's information is not the next one's")
    void resetClearsTheStall() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .noNewInfoThreshold(3)
                .build();
        for (int i = 1; i <= 3; i++) {
            d.recordBatch(
                    List.of(call("call-" + i, "fetch", Map.of("url", "https://m" + i + ".example/d"))),
                    List.of(new ProgressLoopDetector.BatchResult(
                            "call-" + i, "fetch", "THE SAME DOCUMENT", false)),
                    0);
        }
        assertTrue(d.noNewInfoStreak() > 0, "precondition: the streak grew");
        d.reset();
        assertEquals(0, d.noNewInfoStreak(),
                "reset() must clear the stall streak — otherwise a previous query's stall bleeds into the next");
    }

    @Test
    @DisplayName("R416: searches that found nothing are exploration, not a stall")
    void searchesThatFoundNothingAreNotAStall() {
        // ★ THE FALSE POSITIVE THIS ROUND HAD TO AVOID ★ three searches
        // that find nothing is legitimate exploration — the model is
        // narrowing down where the answer is. The handover said so
        // explicitly, and a stall detector that stops here would punish
        // exactly the behaviour the user wants.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .noNewInfoThreshold(3)
                .build();
        for (int i = 1; i <= 8; i++) {
            ProgressLoopDetector.LoopInfo info = d.recordBatch(
                    List.of(call("call-" + i, "grep", Map.of("pattern", "needle" + i))),
                    List.of(new ProgressLoopDetector.BatchResult(
                            "call-" + i, "grep", "(no matches)\n", false)),
                    0);
            assertNull(info, "turn " + i + " found nothing — the detector must stay quiet, got " + info);
        }
        assertEquals(0, d.noNewInfoStreak(),
                "a turn that was told nothing neither advances nor resets the stall");
    }

    // ------------------------------------------------------------------
    // the digest — the unit "new information" is measured in
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: the digest drops the volatile parts and keeps the content")
    void digestDropsVolatileParts() {
        // The same document served by two mirrors, with a timestamp and a
        // tool-call id in the body: the INFORMATION is the same.
        String a = "Source: https://mirror-a.example/doc\ncall_abcdef012345\n2026-11-26T10:00:00Z\ntook 1.2s\nBODY";
        String b = "Source: https://mirror-b.example/doc\ncall_zzzzzz999999\n2026-11-26T11:22:33Z\ntook 4.8s\nBODY";
        assertEquals(ProgressLoopDetector.informationDigest(a),
                ProgressLoopDetector.informationDigest(b),
                "the URL, the call id, the timestamp and the duration carry no information — "
                        + "twenty mirrors of one document must look like ONE answer");

        // A DIFFERENT body is different information.
        assertNotEquals(ProgressLoopDetector.informationDigest(a),
                ProgressLoopDetector.informationDigest(a.replace("BODY", "OTHER BODY")),
                "different content must produce a different digest");

        // A hex blob is CONTENT (e.g. a git SHA), not a volatile token.
        assertNotEquals(ProgressLoopDetector.informationDigest("commit deadbeefdeadbeef"),
                ProgressLoopDetector.informationDigest("commit cafebabecafebabe"),
                "a hash in the output is content — normalising it away would merge two real results");

        assertNull(ProgressLoopDetector.informationDigest(null));
        assertNull(ProgressLoopDetector.informationDigest("   \n  "),
                "an empty result carries no information at all");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ContentBlock.ToolUseBlock call(String id, String name, Map<String, Object> input) {
        return new ContentBlock.ToolUseBlock(id, name, input);
    }
}
