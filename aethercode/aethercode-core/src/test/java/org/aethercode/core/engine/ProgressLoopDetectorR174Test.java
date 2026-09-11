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
 * tests for the {@code empty_tool_input} pattern in
 * {@link ProgressLoopDetector}. The pattern fires when the model
 * emits a tool call with an empty / missing input map for
 * {@code emptyInputStreakThreshold} consecutive turns. The
 * canonical case is a confused model emitting {@code bash {}} in
 * a 50-turn "fix the tool call format" loop (the v0.2.19
 * real-prompt regression).
 *
 * <p>These tests sit alongside the prior round detector tests
 * and follow the same pattern: pin the firing shape, the
 * accessors, the reset semantics, and the interaction with
 * {@code notifyProgress} / {@code acknowledge}.
 */
class ProgressLoopDetectorR174Test {

    private static ContentBlock.ToolUseBlock emptyTool(String name) {
        // input is the canonical "empty" map: present, but no
        // keys. This is what the engine sees when the model
        // emits `<invoke name="bash" />` with no `<parameter>` tags.
        return new ContentBlock.ToolUseBlock("id-" + name, name, Map.of());
    }

    private static ContentBlock.ToolUseBlock populatedTool(String name, String key, Object value) {
        return new ContentBlock.ToolUseBlock("id-" + name, name, Map.of(key, value));
    }

    private static ProgressLoopDetector.BatchResult okResult(String name, String out) {
        return new ProgressLoopDetector.BatchResult("id-" + name, name, out, false);
    }

    // ---- core firing shape ---------------------------------------------

    @Test
    void emptyInputStreakFiresAfterThreshold() {
        // 3 consecutive empty bash calls → loop_detected
        // (the v0.2.19 regression signature).
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(999_999).longOutputConsecutive(99)
                .warnBeforeStop(2).build();
        // Disable the R138 research_mode detector so it
        // doesn't pre-empt the empty_tool_input verdict
        // when the test uses small-output shell (which
        // the bash "command is required" error responses
        // would otherwise look like).
        d.setMaxSmallOutputStreak(99);
        d.setEmptyInputStreakThreshold(3);
        // Turns 1-2 (priming): the detector's turnCount > 2
        // guard (mirroring prior round.3) means we don't
        // count or fire on the first two turns. This
        // gives the model a small grace window for
        // legitimate "list everything" empty-args calls
        // like `glob {}`.
        assertNull(d.recordBatch(List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50));
        assertEquals(0, d.emptyInputStreak(),
                "priming turn 1 should not bump the streak");
        assertNull(d.recordBatch(List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50));
        assertEquals(0, d.emptyInputStreak(),
                "priming turn 2 should not bump the streak");
        // Turn 3: streak=1, threshold not yet reached.
        assertNull(d.recordBatch(List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50));
        assertEquals(1, d.emptyInputStreak());
        // Turn 4: streak=2, threshold not yet reached.
        assertNull(d.recordBatch(List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50));
        assertEquals(2, d.emptyInputStreak());
        // Turn 5: threshold reached → fire.
        ProgressLoopDetector.LoopInfo r = d.recordBatch(
                List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50);
        assertNotNull(r, "should fire at streak 3");
        assertTrue(r.shouldStop(), "empty_tool_input is a hard stop");
        assertEquals("loop_detected", r.kind(),
                "the kind should be loop_detected, was: " + r.kind());
        assertEquals("empty_tool_input", d.lastLoopKind(),
                "lastLoopKind should be empty_tool_input");
        assertTrue(r.description().contains("empty input"),
                "description should mention empty input: " + r.description());
    }

    @Test
    void emptyInputStreakResetsOnPopulatedBatch() {
        // A single populated batch in the middle of the
        // streak resets the counter to 0. The detector only
        // fires on CONSECUTIVE empty batches.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(999_999).longOutputConsecutive(99)
                .warnBeforeStop(2).build();
        d.setMaxSmallOutputStreak(99);  // disable R138 pre-emption
        d.setEmptyInputStreakThreshold(3);
        // 2 priming turns (turns 1-2 don't bump the streak).
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        // Turn 3: streak=1.
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        assertEquals(1, d.emptyInputStreak());
        // Turn 4: streak=2.
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        assertEquals(2, d.emptyInputStreak());
        // Turn 5: A real bash call (with a command) — streak resets.
        d.recordBatch(List.of(populatedTool("bash", "command", "ls")),
                List.of(okResult("bash", "ok")), 50);
        assertEquals(0, d.emptyInputStreak());
        // Now two more empty calls — not enough to fire.
        assertNull(d.recordBatch(List.of(emptyTool("bash")),
                List.of(okResult("bash", "err")), 50));
        assertNull(d.recordBatch(List.of(emptyTool("bash")),
                List.of(okResult("bash", "err")), 50));
        assertEquals(2, d.emptyInputStreak());
    }

    @Test
    void emptyInputStreakDoesNotFireOnMixedBatch() {
        // A batch that has BOTH empty and populated tool
        // calls is NOT counted as empty — the populated
        // tool indicates the model is doing real work.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(999_999).longOutputConsecutive(99)
                .warnBeforeStop(2).build();
        d.setEmptyInputStreakThreshold(2);
        // Mixed batch: one empty + one populated
        d.recordBatch(
                List.of(emptyTool("bash"), populatedTool("file_read", "path", "/tmp/x")),
                List.of(okResult("bash", "err"), okResult("file_read", "hello")), 50);
        d.recordBatch(
                List.of(emptyTool("bash"), populatedTool("file_read", "path", "/tmp/y")),
                List.of(okResult("bash", "err"), okResult("file_read", "world")), 50);
        assertEquals(0, d.emptyInputStreak(),
                "mixed batch should not bump emptyInputStreak");
    }

    // ---- grace period + early-empties ---------------------------------

    @Test
    void emptyInputStreakDoesNotFireOnFirstTwoTurns() {
        // R174 mirrors prior round.3: the first 2 turns are
        // "priming" (the model is exploring the tool
        // surface, the engine doesn't have enough context
        // to call it a loop yet). An empty batch on turn
        // 1 or 2 is treated as priming and does not
        // contribute to the streak counter.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(999_999).longOutputConsecutive(99)
                .warnBeforeStop(2).build();
        d.setMaxSmallOutputStreak(99);  // disable R138 pre-emption
        d.setEmptyInputStreakThreshold(1);  // tight: fire on first empty batch past grace
        // Turn 1: no fire
        assertNull(d.recordBatch(List.of(emptyTool("bash")),
                List.of(okResult("bash", "err")), 50));
        // Turn 2: no fire
        assertNull(d.recordBatch(List.of(emptyTool("bash")),
                List.of(okResult("bash", "err")), 50));
        assertEquals(0, d.emptyInputStreak(),
                "priming turns should not bump the streak");
        // Turn 3: NOW we start counting — streak=1, threshold=1 → fire.
        ProgressLoopDetector.LoopInfo r = d.recordBatch(
                List.of(emptyTool("bash")),
                List.of(okResult("bash", "err")), 50);
        assertNotNull(r, "should fire on the FIRST post-grace empty batch when threshold=1");
        assertEquals(1, d.emptyInputStreak());
    }

    // ---- interaction with other detector counters ---------------------

    @Test
    void emptyInputStreakResetsOnAcknowledge() {
        // The user pressed "Continue" on the LoopGuardBanner — the
        // engine calls acknowledge() to reset the tier. The
        // emptyInputStreak should ALSO clear so a model that
        // re-emits empty input after the user's ack doesn't
        // re-fire the same pattern on the very next batch.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(999_999).longOutputConsecutive(99)
                .warnBeforeStop(2).build();
        d.setMaxSmallOutputStreak(99);
        d.setEmptyInputStreakThreshold(3);
        // Skip the 2 priming turns.
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        // Build up the streak: turn 3 = 1, turn 4 = 2.
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        assertEquals(2, d.emptyInputStreak());
        d.acknowledge();
        assertEquals(0, d.emptyInputStreak(),
                "acknowledge() should clear emptyInputStreak");
    }

    @Test
    void emptyInputStreakResetsOnNotifyProgress() {
        // Successful file_write / real work → notifyProgress
        // → emptyInputStreak resets. This is the RAG
        // "model just wrote 5 files, now thinking about
        // the next batch" path: a long thinking turn AFTER
        // progress is real work, not a loop.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(999_999).longOutputConsecutive(99)
                .warnBeforeStop(2).build();
        d.setMaxSmallOutputStreak(99);
        d.setEmptyInputStreakThreshold(3);
        // 2 priming turns.
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        // 2 more empty to build streak.
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        assertEquals(2, d.emptyInputStreak());
        d.notifyProgress();
        assertEquals(0, d.emptyInputStreak());
    }

    // ---- setter + threshold configuration ------------------------------

    @Test
    void setEmptyInputStreakThresholdChangesThreshold() {
        ProgressLoopDetector d = ProgressLoopDetector.builder().build();
        assertEquals(3, d.emptyInputStreakThreshold(), "default should be 3");
        d.setEmptyInputStreakThreshold(5);
        assertEquals(5, d.emptyInputStreakThreshold());
    }

    @Test
    void setEmptyInputStreakThresholdRejectsZero() {
        ProgressLoopDetector d = ProgressLoopDetector.builder().build();
        try {
            d.setEmptyInputStreakThreshold(0);
            assertFalse(true, "should have thrown");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }

    @Test
    void setEmptyInputStreakThresholdClearsInFlightTier() {
        // A user lowering the threshold mid-run shouldn't
        // immediately fire on a model that already has
        // 2 empty batches in flight. The setter clears
        // the current tier + lastLoopKind so the new
        // threshold applies from a clean slate.
        ProgressLoopDetector d = ProgressLoopDetector.builder().build();
        d.setEmptyInputStreakThreshold(3);
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        d.recordBatch(List.of(emptyTool("bash")), List.of(okResult("bash", "err")), 50);
        d.setTierForTest(2);  // pretend we already fired
        d.setEmptyInputStreakThreshold(1);
        assertEquals(0, d.currentTier(),
                "setter should clear the in-flight tier");
        assertNull(d.lastLoopKind(),
                "setter should clear the in-flight lastLoopKind");
    }

    // ---- integration: the v0.2.19 regression scenario -----------------

    @Test
    void v0_2_19_regression_scenario_firesWithin5Turns() {
        // The exact failure mode observed on the v0.2.19
        // desktop: model emits bash with no command
        // parameter on every turn, gets
        // "command is required ..." back, and keeps
        // trying. The detector must hard-stop within
        // ~5 turns (2 priming + 3 streak), NOT let it
        // run for 50+ turns like v0.2.19 did.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(999_999).longOutputConsecutive(99)
                .warnBeforeStop(2).build();
        // Use big-output shell to dodge the R138
        // research_mode pre-emption (which would fire on
        // small-output shell). The v0.2.19 real-prompt
        // was a long stream of identical empty bash calls,
        // and the bash "command is required" error
        // response IS small — but a production detector
        // would have to handle both cases. Here we test
        // the empty_input pattern with big-output shell
        // to isolate it.
        d.setMaxSmallOutputStreak(99);
        // Turn 1: a normal file_read (so the model isn't in
        // trouble yet).
        d.recordBatch(List.of(populatedTool("file_read", "path", "/tmp/x")),
                List.of(okResult("file_read", "...")), 50);
        // Turn 2: priming empty bash (no fire).
        ProgressLoopDetector.LoopInfo r2 = d.recordBatch(
                List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50);
        assertNull(r2, "priming turn 2 should not fire");
        // Turn 3: streak=1 (no fire yet).
        ProgressLoopDetector.LoopInfo r3 = d.recordBatch(
                List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50);
        assertNull(r3, "streak 1 should not fire");
        // Turn 4: streak=2 (no fire yet).
        ProgressLoopDetector.LoopInfo r4 = d.recordBatch(
                List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50);
        assertNull(r4, "streak 2 should not fire");
        // Turn 5: streak=3 → FIRE.
        ProgressLoopDetector.LoopInfo r5 = d.recordBatch(
                List.of(emptyTool("bash")),
                List.of(okResult("bash", "command is required ...")), 50);
        assertNotNull(r5, "must hard-stop at streak 3 (turn 5), "
                + "not let the empty-bash storm run for 50+ turns like v0.2.19 did");
        assertTrue(r5.shouldStop());
        assertEquals("empty_tool_input", d.lastLoopKind());
    }
}
