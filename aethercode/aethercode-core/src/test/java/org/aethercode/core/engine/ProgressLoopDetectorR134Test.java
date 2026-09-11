package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the adaptive loop detector. The R133 RAG
 * end-to-end run cut off every module at {@code loop_detected}
 * with the default thresholds. R134 makes two changes:
 *
 * <ol>
 *   <li>{@link ProgressLoopDetector#forComplexTask()} —
 *       a factory that scales all thresholds up (window 20,
 *       fingerprint 5, longOutput 5000, warnBeforeStop 4)
 *       so a real multi-file project can complete.</li>
 *   <li>{@link ProgressLoopDetector#notifyProgress()} —
 *       "auto-finish": when the engine sees real progress
 *       (a file_write succeeded), the tier is reset to 0
 *       so the long thinking phase AFTER a successful
 *       write doesn't trip the loop detector.</li>
 * </ol>
 */
class ProgressLoopDetectorR134Test {

    private static ContentBlock.ToolUseBlock tool(String name, Map<String, Object> input) {
        return new ContentBlock.ToolUseBlock("id-" + name, name, input);
    }

    @Test
    void forComplexTaskUsesScaledThresholds() {
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();
        assertEquals(20, d.window(), "complex-task window should be 20");
        assertEquals(5, d.fingerprintThreshold(), "complex-task fingerprint threshold should be 5");
        assertEquals(10000, d.longOutputThreshold(), "complex-task long-output threshold should be 10000");
        assertEquals(2, d.longOutputConsecutive(), "complex-task longOutputConsecutive should be 2");
        assertEquals(4, d.warnBeforeStop(), "complex-task warnBeforeStop should be 4");
    }

    @Test
    void defaultBuilderStillUsesR101Defaults() {
        // The R101 defaults must be preserved so existing
        // callers (TUI / desktop / unit tests) don't
        // suddenly get a more permissive detector.
        ProgressLoopDetector d = ProgressLoopDetector.builder().build();
        assertEquals(8, d.window());
        assertEquals(3, d.fingerprintThreshold());
        assertEquals(1500, d.longOutputThreshold());
        assertEquals(ProgressLoopDetector.WARN_BEFORE_STOP, d.warnBeforeStop());
    }

    @Test
    void complexTaskDetectorTakes5FingerprintsBeforeWarning() {
        // The RAG use case: model writes file1, file2, file3, file4, file5.
        // Each is a different fingerprint (different file_path), so the
        // "same fingerprint" check doesn't fire. The "long output"
        // check is the actual risk. We test both here.
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();

        // 4 same-fingerprint batches: should NOT fire (threshold is 5).
        for (int i = 0; i < 4; i++) {
            ProgressLoopDetector.LoopInfo info = d.recordBatch(
                    List.of(tool("file_write", Map.of("file_path", "a.py"))));
            assertNull(info, "fingerprint #" + (i + 1) + " should not trigger (below threshold 5)");
        }
        // 5th batch: should fire a warning, NOT a hard stop.
        ProgressLoopDetector.LoopInfo info5 = d.recordBatch(
                List.of(tool("file_write", Map.of("file_path", "a.py"))));
        assertNotNull(info5, "5th same-fingerprint batch should warn");
        assertTrue(info5.isWarning(), "should be a warning, not a hard stop");
        assertEquals(1, info5.tier(), "first warning should be tier 1");
    }

    @Test
    void complexTaskDetectorWarnBeforeStopIs4() {
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();
        // Complex-task fingerprint threshold is 5, so the
        // first 4 hits prime the count, the 5th hit fires
        // a tier-1 warning, the 6th fires tier 2, 7th fires
        // tier 3, 8th fires tier 4, 9th hits the hard-stop
        // (tier 5 > warnBeforeStop 4).
        // 4 priming hits (no fire).
        for (int i = 0; i < 4; i++) {
            ProgressLoopDetector.LoopInfo info = d.recordBatch(
                    List.of(tool("bash", Map.of("command", "rm -rf /"))));
            assertNull(info, "priming hit " + (i + 1) + " should not fire");
        }
        // 4 warning-tier hits: tier 1, 2, 3, 4.
        for (int expectedTier = 1; expectedTier <= 4; expectedTier++) {
            ProgressLoopDetector.LoopInfo info = d.recordBatch(
                    List.of(tool("bash", Map.of("command", "rm -rf /"))));
            assertNotNull(info, "warning tier " + expectedTier + " should fire");
            assertTrue(info.isWarning(), "tier " + expectedTier + " should be a warning");
            assertEquals(expectedTier, info.tier());
        }
        // 5th warn-level hit (9th total) → hard stop.
        ProgressLoopDetector.LoopInfo stopInfo = d.recordBatch(
                List.of(tool("bash", Map.of("command", "rm -rf /"))));
        assertNotNull(stopInfo);
        assertTrue(stopInfo.shouldStop(), "9th hit should hard-stop");
        assertEquals("loop_detected", stopInfo.kind());
    }

    @Test
    void notifyProgressResetsTierToZero() {
        // The RAG "I just wrote file 1, now thinking about file 2"
        // scenario. The model is in a legitimate thinking phase;
        // we just don't want the tier to climb.
        // Complex-task fingerprint threshold is 5, so we need
        // 5 hits to get to tier 1, then notifyProgress to reset.
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();
        // Generate a tier-1 warning (5 hits → count >= 5).
        for (int i = 0; i < 5; i++) {
            d.recordBatch(List.of(tool("file_write", Map.of("file_path", "a.py"))));
        }
        assertEquals(1, d.currentTier(), "tier should be 1 after 5 same-fingerprint hits");

        // Engine sees the file_write succeeded → notifyProgress.
        d.notifyProgress();
        assertEquals(0, d.currentTier(), "notifyProgress should reset tier to 0");
        assertEquals(1, d.progressEvents(), "progressEvents counter should be 1");
    }

    @Test
    void notifyProgressBetweenHitsPreventsHardStop() {
        // The canonical RAG end-to-end pattern:
        // 1. model writes file1 → notifyProgress resets tier
        // 2. model thinks
        // 3. model writes file2 → notifyProgress resets tier again
        // 4. ... etc.
        // Even if every batch is the SAME fingerprint, the tier
        // never climbs past 1 because each hit is followed by
        // a progress event.
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();
        for (int i = 0; i < 20; i++) {
            d.recordBatch(List.of(tool("file_write", Map.of("file_path", "a.py"))));
            d.notifyProgress();
        }
        assertEquals(0, d.currentTier(), "tier should never climb with notifyProgress between hits");
        assertEquals(20, d.progressEvents(), "should have 20 progress events");
    }

    @Test
    void setWarnBeforeStopInvalidatesCurrentTier() {
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();
        // 5 hits → tier 1.
        for (int i = 0; i < 5; i++) {
            d.recordBatch(List.of(tool("file_write", Map.of("file_path", "a.py"))));
        }
        assertEquals(1, d.currentTier());
        d.setWarnBeforeStop(99);  // effectively disable hard stop
        assertEquals(0, d.currentTier(), "setWarnBeforeStop should reset tier");
        // Now a "stop" hit should be just another warning.
        for (int i = 0; i < 50; i++) {
            d.recordBatch(List.of(tool("file_write", Map.of("file_path", "a.py"))));
        }
        ProgressLoopDetector.LoopInfo info = d.recordBatch(
                List.of(tool("file_write", Map.of("file_path", "a.py"))));
        // 56 hits but tier=99 → all warnings, no hard stop.
        if (info != null) {
            assertTrue(info.isWarning(), "with warnBeforeStop=99, never hard-stop");
        }
    }

    @Test
    void longOutputAllowedInComplexTaskMode() {
        // The RAG pattern: model writes 3 files, then a 3000-char
        // thinking turn about the next 3. With default
        // longOutputThreshold=1500, this would fire. With
        // complex-task threshold=10000 + consecutive=2, even
        // a single 9000-char turn passes.
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();
        // Single 9000-char turn (under 10000): no fire.
        ProgressLoopDetector.LoopInfo info = d.recordBatch(List.of(), List.of(), 9000);
        assertNull(info, "9000 chars single turn should not fire (threshold 10000)");
        // Reset by calling reset() so we test the consecutive
        // logic from scratch.
        d.reset();
        // First long turn: no fire (need 2 consecutive).
        ProgressLoopDetector.LoopInfo info1 = d.recordBatch(List.of(), List.of(), 11000);
        assertNull(info1, "1st 11000-char turn should not fire (consecutive=2)");
        assertEquals(1, d.longOutputStreak());
        // Second long turn: fires.
        ProgressLoopDetector.LoopInfo info2 = d.recordBatch(List.of(), List.of(), 12000);
        assertNotNull(info2, "2nd consecutive long turn should fire");
        assertTrue(info2.isWarning());
    }

    @Test
    void toolCallResetsLongOutputStreak() {
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();
        // 1st long turn: streak=1, no fire.
        d.recordBatch(List.of(), List.of(), 11000);
        assertEquals(1, d.longOutputStreak());
        // Now a tool call turn with normal text: streak resets.
        d.recordBatch(List.of(tool("bash", Map.of("command", "ls"))), List.of(), 100);
        assertEquals(0, d.longOutputStreak(), "tool call should reset longOutputStreak");
        // Another long turn: streak=1 again, no fire.
        ProgressLoopDetector.LoopInfo info = d.recordBatch(List.of(), List.of(), 11000);
        assertNull(info, "after reset, 1st long turn should not fire");
    }

    @Test
    void resetClearsProgressCounter() {
        ProgressLoopDetector d = ProgressLoopDetector.forComplexTask();
        d.notifyProgress();
        d.notifyProgress();
        assertEquals(2, d.progressEvents());
        d.reset();
        assertEquals(0, d.progressEvents(), "reset() should clear progressEvents");
    }
}
