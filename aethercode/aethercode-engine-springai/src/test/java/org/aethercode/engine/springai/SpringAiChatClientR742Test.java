package org.aethercode.engine.springai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R742 (2026-10-20) — the {@code it == null} retry-exhaustion branch
 * of {@code SpringAiChatClient.runCall} must pair its terminal
 * {@link org.aethercode.core.stream.StreamEvent.RunEnd} with a
 * {@code queue.offer(POISON)}, exactly like its R738 sibling.
 *
 * <h2>Root cause</h2>
 * R738 fixed ONE branch: the {@code catch (Exception e)} around the
 * streaming {@code while (it.hasNext())} loop. The shape was
 * "offer a RunEnd, then {@code return;}" — which skips the
 * {@code queue.offer(POISON)} that the {@code Thread runner} lambda
 * only reaches on a NORMAL return from {@code runCall}. The
 * Spliterator then blocks forever on {@code queue.take()}, the
 * RunEnd sitting unread in the queue, and the desktop sits in a
 * misleading green {@code ● Idle} with no error banner and no ↻
 * retry.
 *
 * <p>R742 is the SAME bug in the branch R738 didn't touch:
 * <pre>
 *   if (it == null) {                       // all 3 R697 retries exhausted
 *       queue.offer(new StreamEvent.RunEnd(errMsg, List.of()));
 *       return;                             // ← no POISON  (pre-R742)
 *   }
 * </pre>
 *
 * <p>This is the single most reachable variant: {@code it == null}
 * is exactly what a sustained upstream outage, a persistently-400
 * MiniMax prompt, or an auth failure produces — every one of which
 * burns 3 attempts with 1s/2s backoff before giving up. The user's
 * input box stays disabled the whole time.
 *
 * <h2>Why the R738 test did not catch it</h2>
 * {@code SpringAiChatClientR738Test.r697AllAttemptsFailedPairsRunEndWithPoison}
 * asserts only {@code src.indexOf("queue.offer(POISON)", runEndIdx) > 0} —
 * i.e. "a POISON offer appears <em>somewhere</em> later in the
 * file". The R738 fix inside the {@code catch} block satisfies
 * that, so the assertion passed against a branch that was still
 * broken. Classic sibling-detector false negative (the R739
 * deployment lesson): the test looked at the right token but not at
 * the right <em>control-flow region</em>.
 *
 * <p>These tests are therefore written against a bounded window
 * (see {@link #poisonFollowsRetryExhaustionRunEndWithinSameBlock})
 * so a POISON from a different branch can never satisfy them.
 *
 * <h2>R742 rule for future edits</h2>
 * Every terminal {@code RunEnd} offer inside {@code runCall} must be
 * immediately followed by {@code queue.offer(POISON)} before any
 * {@code return;}. If you add a new early-return branch, pair them.
 */
class SpringAiChatClientR742Test {

    /**
     * The retry-exhaustion {@code it == null} block must offer the
     * RunEnd and then the POISON, with only whitespace/comments
     * between them — no {@code return;} in between, and no
     * unbounded search that a sibling branch could satisfy.
     */
    @Test
    void poisonFollowsRetryExhaustionRunEndWithinSameBlock() throws Exception {
        String src = readSource();
        int blockIdx = src.indexOf("if (it == null) {");
        assertTrue(blockIdx > 0,
                "R742: the R697 retry-exhaustion guard `if (it == null) {` "
                        + "must still exist — it is the branch that reports "
                        + "'all attempts failed' to the user");

        int runEndIdx = src.indexOf("queue.offer(new StreamEvent.RunEnd(errMsg",
                blockIdx);
        assertTrue(runEndIdx > blockIdx,
                "R742: the it==null branch must offer the terminal RunEnd so the "
                        + "renderer gets a diagnostic stopReason");

        // Bound the search to THIS block: the closing brace of the
        // `if (it == null) { ... }`.
        int blockEnd = src.indexOf("\n        }", runEndIdx);
        assertTrue(blockEnd > runEndIdx,
                "R742: expected to find the closing brace of the it==null block");

        String block = src.substring(runEndIdx, blockEnd);
        int poisonIdx = block.indexOf("queue.offer(POISON)");
        assertTrue(poisonIdx >= 0,
                "R742: the it==null (all-retries-exhausted) branch offers a terminal "
                        + "RunEnd but NO queue.offer(POISON) before returning. The "
                        + "Spliterator's queue.take() blocks forever, the RunEnd is "
                        + "never read, and the desktop shows ● Idle instead of the "
                        + "error. Block under test:\n" + block);

        assertTrue(poisonIdx > 0,
                "R742: the POISON must come AFTER the RunEnd offer, not before — "
                        + "otherwise the Spliterator stops at the poison and discards "
                        + "the RunEnd still sitting in the queue");
    }

    /**
     * Anchor the R742 marker comment so the fix's rationale stays
     * attached to the code (Lesson 1052: a fix you cannot see fire
     * in the log is a fix you cannot prove shipped). The marker sits
     * between the RunEnd and its POISON — i.e. immediately above the
     * line whose absence caused the bug.
     */
    @Test
    void retryExhaustionBlockCarriesR742Marker() throws Exception {
        String src = readSource();
        int blockIdx = src.indexOf("if (it == null) {");
        assertTrue(blockIdx > 0, "the it==null branch must exist");
        int runEndIdx = src.indexOf("queue.offer(new StreamEvent.RunEnd(errMsg",
                blockIdx);
        int markerIdx = src.indexOf("R742", runEndIdx);
        assertTrue(runEndIdx > blockIdx && markerIdx > runEndIdx,
                "R742: the marker comment must sit immediately after the it==null "
                        + "branch's RunEnd offer and before its POISON, so the next "
                        + "reader learns WHY the POISON is mandatory there. "
                        + "runEndIdx=" + runEndIdx + " markerIdx=" + markerIdx);
        // ...and it must be CLOSE — inside the block, not 5KB later.
        int blockEnd = src.indexOf("\n        }", runEndIdx);
        assertTrue(markerIdx < blockEnd,
                "R742: the marker must live inside the it==null block (before its "
                        + "closing brace at " + blockEnd + "), not somewhere else in "
                        + "the file. markerIdx=" + markerIdx);
    }

    /**
     * The success paths must NOT offer POISON themselves — the
     * runner lambda owns that for a normal return. R742 is a
     * surgical fix on the error path; if someone "helpfully" adds
     * POISON to the {@code RunEnd("stop")} / {@code RunEnd("tool_calls")}
     * branches, the runner would then double-poison and the second
     * poison would be consumed by a Spliterator that has already
     * returned false (harmless but confusing) — more importantly it
     * would signal the contract has drifted.
     */
    @Test
    void successPathRunEndsDoNotOfferPoisonInline() throws Exception {
        String src = readSource();
        for (String marker : new String[]{
                "queue.offer(new StreamEvent.RunEnd(\"stop\"",
                "queue.offer(new StreamEvent.RunEnd(\"tool_calls\""}) {
            int idx = src.indexOf(marker);
            assertTrue(idx > 0, "expected to find the success-path offer: " + marker);
            int blockEnd = src.indexOf("\n        }", idx);
            String block = blockEnd > idx ? src.substring(idx, blockEnd) : "";
            assertTrue(!block.contains("queue.offer(POISON)"),
                    "R742: the success paths must rely on the runner lambda's "
                            + "`runCall(); queue.offer(POISON)`; an inline POISON "
                            + "here would only mask a future missing-POISON bug. "
                            + "Offending block:\n" + block);
        }
    }

    private static String readSource() throws Exception {
        return Files.readString(Paths.get("src", "main", "java",
                "org", "aethercode", "engine", "springai",
                "SpringAiChatClient.java"), StandardCharsets.UTF_8);
    }
}