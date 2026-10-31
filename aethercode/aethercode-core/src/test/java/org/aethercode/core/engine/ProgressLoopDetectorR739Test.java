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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R739 (2026-10-20) — {@code same_error} in
 * {@link ProgressLoopDetector} must hard-stop the run on the
 * {@code sameErrorThreshold}-th consecutive hit, NOT climb
 * through the warn→stop ladder.
 *
 * <p>Pre-R739 the same_error branch called {@code tiered(...)}
 * which maps hits 1..N to {@code loop_warn_<tier>} and hit N+1 to
 * {@code loop_detected}. With the default {@code warnBeforeStop=4},
 * a model that hit the same error 5 times in a row would finally
 * stop — but the user-reported bug was a model stuck on
 * {@code ask_user_question} with the same missing-params error
 * firing once per turn. 5 turns of paying tokens to repeat the
 * same broken call is far too forgiving; the user asked us to
 * "end the run when the model is clearly stuck" (Lesson 1059).
 *
 * <p>The fix bypasses {@code tiered()} for same_error: hit the
 * threshold and return {@code "loop_detected"} directly. The
 * engine's pre-existing R181 branch keys on
 * {@code lastLoopKind() == "same_error"} to surface the
 * {@code same_error_hard_stop} SideNote + RunEnd, so the desktop
 * flips to {@code ● Failed (same_error)} with a retry button.
 *
 * <p>Companion to R266h (which early-rejects the missing-params
 * call) and R181 (which wrote the original hard-stop branch).
 *
 * <p>All tests in this class deliberately use
 * {@code ask_user_question} as the tool name, NOT {@code bash}.
 * {@code bash} would trip the {@code research_mode} detector
 * (consecutive small-output shell calls) before {@code same_error}
 * has a chance to fire — three shell calls in a row look like
 * research even when the error message is the same. Using a
 * non-shell tool isolates the {@code same_error} path.
 */
class ProgressLoopDetectorR739Test {

    /**
     * R266h-shaped call: the input map is non-empty (the model
     * DID try to fill in fields — it just missed a required one),
     * so {@code empty_tool_input} doesn't fire and {@code same_error}
     * is the verdict that should reach the engine. This is the
     * shape the user reported as "the model keeps calling
     * ask_user_question but always forgets the options.label".
     */
    private static ContentBlock.ToolUseBlock askUserCall() {
        return new ContentBlock.ToolUseBlock("id-ask_user_question",
                "ask_user_question",
                Map.of("questions", List.of(
                        Map.of("question", "what now?", "header", "Plan"))));
    }

    private static ProgressLoopDetector.BatchResult errorResult(
            String id, String name, String errMsg) {
        return new ProgressLoopDetector.BatchResult(id, name, errMsg, true);
    }

    private static ProgressLoopDetector newDetector(int sameErrorThreshold) {
        // fingerprintThreshold is set very high (99) so that
        // same_fingerprint cannot beat same_error to the punch.
        // recordBatch checks same_fingerprint BEFORE same_error,
        // and the user's R739 regression test fires 3-5 identical
        // tool calls in a row; at the default fingerprintThreshold=3
        // (or even 5), same_fingerprint fires first. The point of
        // these tests is the same_error path; fingerprint is a
        // sibling detector that would otherwise steal the
        // verdict on the same input.
        return ProgressLoopDetector.builder()
                .window(8)
                .fingerprintThreshold(99)
                .sameErrorThreshold(sameErrorThreshold)
                .build();
    }

    /**
     * The headline regression: a model that hits the same error
     * {@code sameErrorThreshold} times in a row must produce a
     * {@code LoopInfo} with {@code kind="loop_detected"} (the
     * {@code shouldStop()==true} contract). Pre-R739 the kind was
     * {@code loop_warn_<tier>}.
     */
    @Test
    void sameErrorThresholdHitsReturnLoopDetected() {
        ProgressLoopDetector d = newDetector(3);
        String sameErr = "tool 'ask_user_question': missing required parameter 'options[0].label'";
        ContentBlock.ToolUseBlock call = askUserCall();

        // 2 hits — not yet at threshold.
        for (int i = 0; i < 2; i++) {
            ProgressLoopDetector.LoopInfo info = d.recordBatch(
                    List.of(call),
                    List.of(errorResult("id-ask_user_question-" + i,
                            "ask_user_question", sameErr)),
                    0);
            assertNull(info, "2 hits must not fire (threshold is 3)");
        }
        // 3rd hit — fires immediately and hard-stops.
        ProgressLoopDetector.LoopInfo info = d.recordBatch(
                List.of(call),
                List.of(errorResult("id-ask_user_question-2",
                        "ask_user_question", sameErr)),
                0);
        assertNotNull(info, "3rd hit must fire same_error");
        assertEquals("loop_detected", info.kind(),
                "R739: same_error must bypass tiered() and return loop_detected on the threshold hit");
        assertTrue(info.shouldStop(),
                "R739: shouldStop() must be true for the same_error hard-stop verdict");
        assertEquals("same_error", d.lastLoopKind(),
                "R739: lastLoopKind() must be same_error so the engine's R181 branch "
                        + "(which keys on the kind for same_error_hard_stop SideNote + RunEnd) "
                        + "fires correctly");
    }

    /**
     * Two DIFFERENT error messages in a row must NOT fire
     * {@code same_error}; the detector resets the streak on a
     * different key. The post-fix invariant: a one-off error
     * followed by a different one-off error is NOT a loop.
     */
    @Test
    void differentErrorMessagesDoNotAccumulate() {
        ProgressLoopDetector d = newDetector(3);
        ContentBlock.ToolUseBlock call = askUserCall();

        ProgressLoopDetector.LoopInfo a = d.recordBatch(
                List.of(call),
                List.of(errorResult("id-a", "ask_user_question", "missing options[0].label")),
                0);
        assertNull(a);

        ProgressLoopDetector.LoopInfo b = d.recordBatch(
                List.of(call),
                List.of(errorResult("id-b", "ask_user_question", "missing options[1].title")),
                0);
        assertNull(b);

        ProgressLoopDetector.LoopInfo c = d.recordBatch(
                List.of(call),
                List.of(errorResult("id-c", "ask_user_question", "missing header field")),
                0);
        assertNull(c, "three different error messages must not trip same_error");
    }

    /**
     * The first occurrence of a repeated error must NOT fire —
     * the detector's {@code lastErrorCount} starts at 0 and
     * counts up only when the key matches. This is the same
     * threshold=3 behaviour as before; only the
     * post-threshold kind changed in R739.
     */
    @Test
    void firstHitOfRepeatedErrorIsCountedButNotFired() {
        ProgressLoopDetector d = newDetector(3);
        String sameErr = "permission denied";
        ContentBlock.ToolUseBlock call = askUserCall();

        assertNull(d.recordBatch(List.of(call),
                List.of(errorResult("id-1", "ask_user_question", sameErr)), 0));
        assertNull(d.recordBatch(List.of(call),
                List.of(errorResult("id-2", "ask_user_question", sameErr)), 0));
        ProgressLoopDetector.LoopInfo info = d.recordBatch(List.of(call),
                List.of(errorResult("id-3", "ask_user_question", sameErr)), 0);
        assertNotNull(info);
        assertEquals("loop_detected", info.kind(),
                "R739: 3rd hit must hard-stop");
        assertTrue(info.shouldStop());
    }

    /**
     * A successful (non-error) batch in the middle of a same-error
     * streak must reset {@code lastErrorCount} via the
     * aggregate-error-key returning null when there are no errors.
     * The pre-fix detector's documentation said this; the
     * post-fix behaviour must match.
     */
    @Test
    void successInTheMiddleResetsStreak() {
        ProgressLoopDetector d = newDetector(3);
        String sameErr = "connection refused";
        ContentBlock.ToolUseBlock call = askUserCall();

        assertNull(d.recordBatch(List.of(call),
                List.of(errorResult("id-1", "ask_user_question", sameErr)), 0));
        assertNull(d.recordBatch(List.of(call),
                List.of(errorResult("id-2", "ask_user_question", sameErr)), 0));
        // success resets the streak — uses ask_user_question too,
        // but with no error, so no error result is present.
        assertNull(d.recordBatch(List.of(call),
                List.of(new ProgressLoopDetector.BatchResult("id-ok",
                        "ask_user_question", "ok", false)),
                0));
        // now we need 3 fresh errors to fire
        assertNull(d.recordBatch(List.of(call),
                List.of(errorResult("id-3", "ask_user_question", sameErr)), 0));
        assertNull(d.recordBatch(List.of(call),
                List.of(errorResult("id-4", "ask_user_question", sameErr)), 0));
        ProgressLoopDetector.LoopInfo info = d.recordBatch(List.of(call),
                List.of(errorResult("id-5", "ask_user_question", sameErr)), 0);
        assertNotNull(info);
        assertEquals("loop_detected", info.kind());
    }

    /**
     * With a configured {@code sameErrorThreshold=2}, two hits
     * fire immediately — confirms the new behaviour scales with
     * the user's threshold setting, not a hardcoded N.
     */
    @Test
    void configuredThreshold2FiresOnSecondHit() {
        ProgressLoopDetector d = newDetector(2);
        ContentBlock.ToolUseBlock call = askUserCall();
        String sameErr = "missing options[0].label";

        assertNull(d.recordBatch(List.of(call),
                List.of(errorResult("id-1", "ask_user_question", sameErr)), 0));
        ProgressLoopDetector.LoopInfo info = d.recordBatch(List.of(call),
                List.of(errorResult("id-2", "ask_user_question", sameErr)), 0);
        assertNotNull(info, "threshold=2 → 2nd hit fires");
        assertEquals("loop_detected", info.kind());
        assertTrue(info.shouldStop());
    }

    /**
     * The post-fix same_error must NOT be reported as a warning.
     * {@code isWarning()} keys on the {@code loop_warn_} prefix;
     * a {@code loop_detected} verdict is a stop, not a warn. The
     * engine's {@code isWarning() ? SideNote : nothing} branch
     * relies on this distinction to decide whether to keep going
     * or hand off to the R181 hard-stop branch.
     */
    @Test
    void sameErrorHitIsNotAWarning() {
        ProgressLoopDetector d = newDetector(2);
        ContentBlock.ToolUseBlock call = askUserCall();
        String sameErr = "missing options[0].label";

        d.recordBatch(List.of(call),
                List.of(errorResult("id-1", "ask_user_question", sameErr)), 0);
        ProgressLoopDetector.LoopInfo info = d.recordBatch(List.of(call),
                List.of(errorResult("id-2", "ask_user_question", sameErr)), 0);
        assertNotNull(info);
        assertFalse(info.isWarning(),
                "R739: same_error must NOT report isWarning()=true — it is a stop, "
                        + "not a warning tier hit");
        assertTrue(info.shouldStop(),
                "R739: same_error must report shouldStop()=true so the engine's "
                        + "R181 hard-stop branch fires");
    }
}