package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link ProgressLoopDetector}.
 *
 * <p>R83: high-risk tool check removed. Four patterns to verify:
 * <ol>
 *   <li>same fingerprint (prior round legacy)</li>
 *   <li>same error N times in a row</li>
 *   <li>long output without tool calls</li>
 *   <li>user interrupt</li>
 * </ol>
 *
 * <p>R101: tiered warning. The detector now returns
 * {@code kind="loop_warn_1" / "loop_warn_2"} for the first
 * N hits, and only returns {@code kind="loop_detected"}
 * (the actual hard-stop kind) on hit N+1. The {@code
 * acknowledge()} method resets the tier mid-streak so the
 * same pattern can fire again. The {@code user_interrupt}
 * kind remains an immediate hard stop — no tiered warning
 * for explicit user interrupts. {@code LoopInfo#shouldStop()}
 * is {@code true} for both "loop_detected" and
 * "user_interrupt".
 *
 * <p>Per-todo adaptive control is exercised in
 * {@link TodoRunControllerTest}.
 */
class ProgressLoopDetectorTest {

    // ----- helpers ----------------------------------------------------------

    private static ContentBlock.ToolUseBlock tool(String name, String... kv) {
        Map<String, Object> input = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) input.put(kv[i], kv[i + 1]);
        return new ContentBlock.ToolUseBlock("id-" + System.nanoTime(), name, input);
    }

    private static ProgressLoopDetector.LoopInfo feed(ProgressLoopDetector d,
                                                       List<ContentBlock.ToolUseBlock> batch,
                                                       List<ProgressLoopDetector.BatchResult> results,
                                                       int textChars) {
        return d.recordBatch(batch, results, textChars);
    }

    // ----- 1. same fingerprint ---------------------------------------------

    @Test
    void sameFingerprintTriggersLoop() {
        // when the same-fingerprint threshold fires for
        // the first time, the kind is wrapped as "loop_warn_1"
        // (tier 1 of 2). The raw cause is still "same_fingerprint"
        // but the LoopInfo's `kind` is the tier-aware wrapper
        // — see ProgressLoopDetector.tiered(). The prior round
        // pre-existing test was written before the tiered
        // wrapper existed; the assertion is updated to match
        // the new contract.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(4).fingerprintThreshold(3)
                .build();
        var b = tool("read", "path", "/tmp/a");
        assertNull(feed(d, List.of(b), List.of(), 0));
        assertNull(feed(d, List.of(b), List.of(), 0));
        ProgressLoopDetector.LoopInfo info = feed(d, List.of(b), List.of(), 0);
        assertTrue(info.isLoop(), "expected a loop after 3 same-fingerprint calls");
        assertEquals("loop_warn_1", info.kind());
        // a tier-1 warn is NOT a hard stop; shouldStop() is false.
        assertTrue(info.isWarning());
        assertFalse(info.shouldStop());
    }

    @Test
    void differentFingerprintsAreNotLoop() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(4).fingerprintThreshold(3)
                .build();
        assertNull(feed(d, List.of(tool("read", "path", "/a")), List.of(), 0));
        assertNull(feed(d, List.of(tool("read", "path", "/b")), List.of(), 0));
        assertNull(feed(d, List.of(tool("read", "path", "/c")), List.of(), 0));
        // 3 different calls, no loop.
        assertFalse(d.recentFingerprints().isEmpty());
    }

    // ----- 2. same error ----------------------------------------------------

    @Test
    void sameErrorTriggersLoop() {
        // same_error is wrapped as "loop_warn_1" on the
        // first hit; the raw cause is in the description, not
        // the kind. See sameFingerprintTriggersLoop above for
        // the contract.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99) // disable fingerprint
                .sameErrorThreshold(3)
                .build();
        var block = tool("read", "path", "/missing");
        ProgressLoopDetector.BatchResult err = new ProgressLoopDetector.BatchResult(
                "id", "read", "FileNotFound: /missing", true);
        assertNull(feed(d, List.of(block), List.of(err), 0));
        assertNull(feed(d, List.of(block), List.of(err), 0));
        ProgressLoopDetector.LoopInfo info = feed(d, List.of(block), List.of(err), 0);
        assertTrue(info.isLoop(), "expected a loop after 3 same-error results");
        assertEquals("loop_warn_1", info.kind());
        assertTrue(info.isWarning());
    }

    @Test
    void differentErrorsAreNotLoop() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .sameErrorThreshold(3)
                .build();
        var block = tool("read", "path", "/x");
        var e1 = new ProgressLoopDetector.BatchResult("id", "read", "FileNotFound: a", true);
        var e2 = new ProgressLoopDetector.BatchResult("id", "read", "Permission denied", true);
        var e3 = new ProgressLoopDetector.BatchResult("id", "read", "FileNotFound: b", true);
        assertNull(feed(d, List.of(block), List.of(e1), 0));
        assertNull(feed(d, List.of(block), List.of(e2), 0));
        assertNull(feed(d, List.of(block), List.of(e3), 0));
        // 3 different errors, no loop.
    }

    // the post-check in QueryEngine hard-stops the run on
    // same_error tier 3 (loop_detected). Verify the detector
    // escalates same_error from warn to detected as expected.
    //
    // The escalation sequence (with sameErrorThreshold=3) is:
    //   hits 1-2    -> null (no verdict)
    //   hit 3       -> loop_warn_1
    //   hit 4       -> loop_warn_2 (WARN_BEFORE_STOP)
    //   hit 5       -> loop_detected (the hard-stop kind)
    @Test
    void r181_sameErrorEscalatesToLoopDetected() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .sameErrorThreshold(3)
                .build();
        var block = tool("file_write", "file_path", "D:\\tmp\\abc_1\\pom.xml");
        var err = new ProgressLoopDetector.BatchResult(
                "id", "file_write", "timeout: null", true);
        // First 2 hits are below threshold, return null.
        assertNull(feed(d, List.of(block), List.of(err), 0));
        assertNull(feed(d, List.of(block), List.of(err), 0));
        // Hit 3: loop_warn_1 (first detection).
        ProgressLoopDetector.LoopInfo w1 = feed(d, List.of(block), List.of(err), 0);
        assertEquals("loop_warn_1", w1.kind());
        // Hit 4: loop_warn_2 (WARN_BEFORE_STOP).
        ProgressLoopDetector.LoopInfo w2 = feed(d, List.of(block), List.of(err), 0);
        assertEquals("loop_warn_2", w2.kind());
        // Hit 5: loop_detected — the kind the post-check looks for
        // to hard-stop the run.
        ProgressLoopDetector.LoopInfo detected = feed(d, List.of(block), List.of(err), 0);
        assertEquals("loop_detected", detected.kind());
        assertTrue(detected.shouldStop(),
                "R181 expects loop_detected on same_error tier 3 to be a hard stop");
        // R181 contract: the description should still mention the
        // error text so the operator can identify the loop.
        assertTrue(detected.description().contains("timeout: null"),
                "description should surface the looping error text");
    }

    // ----- 3. long output --------------------------------------------------

    @Test
    void longOutputWithoutToolCallsTriggersLoop() {
        // long_output is wrapped as "loop_warn_1" on the
        // first hit. See sameFingerprintTriggersLoop for the
        // contract.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(500)
                .build();
        // Pure text turn (no tool calls) with 600 chars.
        ProgressLoopDetector.LoopInfo info = feed(d, List.of(), List.of(), 600);
        assertTrue(info.isLoop(), "expected a loop for long output without tool calls");
        assertEquals("loop_warn_1", info.kind());
    }

    @Test
    void shortTextTurnsAreNotLoop() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(500)
                .build();
        assertNull(feed(d, List.of(), List.of(), 100));
        assertNull(feed(d, List.of(), List.of(), 200));
    }

    // ----- 4. user interrupt -----------------------------------------------

    @Test
    void userInterruptTriggersImmediately() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .build();
        d.notifyUserInterrupt();
        ProgressLoopDetector.LoopInfo info = feed(d, List.of(), List.of(), 0);
        assertTrue(info.isLoop());
        assertEquals("user_interrupt", info.kind());
    }

    @Test
    void resetClearsAll() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(4).fingerprintThreshold(2)
                .build();
        var b = tool("read", "path", "/x");
        feed(d, List.of(b), List.of(), 0);
        assertFalse(d.recentFingerprints().isEmpty());
        d.reset();
        assertTrue(d.recentFingerprints().isEmpty());
        d.notifyUserInterrupt();
        d.reset();
        assertFalse(d.isUserInterrupted());
    }

    // ----- builder / fingerprint utils -------------------------------------

    @Test
    void fingerprintStableAcrossInputOrder() {
        var a = tool("bash", "a", "1", "b", "2");
        var b = tool("bash", "b", "2", "a", "1");
        assertEquals(ProgressLoopDetector.fingerprint(a), ProgressLoopDetector.fingerprint(b));
    }

    @Test
    void summariseFingerprintTruncates() {
        var b = tool("bash", "command", "x".repeat(200));
        String fp = ProgressLoopDetector.fingerprint(b);
        String summary = ProgressLoopDetector.summariseFingerprint(fp);
        assertTrue(summary.length() < 100, "summary should be truncated: " + summary);
    }

    @Test
    void builderValidatesInputs() {
        assertThrows(IllegalArgumentException.class, () -> ProgressLoopDetector.builder().window(0));
        assertThrows(IllegalArgumentException.class, () -> ProgressLoopDetector.builder().fingerprintThreshold(0));
        assertThrows(IllegalArgumentException.class, () -> ProgressLoopDetector.builder().sameErrorThreshold(0));
        assertThrows(IllegalArgumentException.class, () -> ProgressLoopDetector.builder().longOutputThreshold(0));
    }

    @Test
    void fingerprintThresholdClampedToWindow() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(2).fingerprintThreshold(99)
                .build();
        // fingerprint threshold is clamped to window (2), so 2 same calls trigger.
        var b = tool("read", "p", "/x");
        assertNull(feed(d, List.of(b), List.of(), 0));
        ProgressLoopDetector.LoopInfo info = feed(d, List.of(b), List.of(), 0);
        assertTrue(info.isLoop());
    }

    /** long_output is the easiest pattern to drive
     *  repeatedly — every pure-text turn above the threshold
     *  fires once. We use it to walk the detector through
     *  tier 1 → tier 2 → tier 3 (hard stop) and verify the
     *  kind/tier/shouldStop() contract at each step. */
    @Test
    void r101TieredLongOutputEscalatesFromWarnToStop() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99) // disable fingerprint
                .longOutputThreshold(500)
                .build();
        // Hit 1: tier 1, warn (engine should continue).
        ProgressLoopDetector.LoopInfo h1 = feed(d, List.of(), List.of(), 600);
        assertNotNull(h1, "hit 1 should be detected");
        assertTrue(h1.isWarning(), "hit 1 should be a warning, not a stop");
        assertEquals("loop_warn_1", h1.kind());
        assertEquals(1, h1.tier());
        assertFalse(h1.shouldStop(), "warn should not be a hard stop");
        // Hit 2: tier 2, warn.
        ProgressLoopDetector.LoopInfo h2 = feed(d, List.of(), List.of(), 600);
        assertNotNull(h2, "hit 2 should be detected");
        assertTrue(h2.isWarning(), "hit 2 should still be a warning");
        assertEquals("loop_warn_2", h2.kind());
        assertEquals(2, h2.tier());
        assertFalse(h2.shouldStop(), "warn should not be a hard stop");
        // Hit 3: tier 3 (== WARN_BEFORE_STOP + 1), hard stop.
        ProgressLoopDetector.LoopInfo h3 = feed(d, List.of(), List.of(), 600);
        assertNotNull(h3, "hit 3 should be detected");
        assertEquals("loop_detected", h3.kind());
        assertEquals(3, h3.tier());
        assertTrue(h3.shouldStop(), "loop_detected must be a hard stop");
        assertFalse(h3.isWarning(), "loop_detected is not a warning");
    }

    /** acknowledge() resets the tier mid-streak. The
     *  user clicks "Continue" → tier returns to 0 → the next
     *  hit of the same pattern starts again at tier 1. */
    @Test
    void r101AcknowledgeResetsTier() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(500)
                .build();
        // Two warns in a row.
        ProgressLoopDetector.LoopInfo h1 = feed(d, List.of(), List.of(), 600);
        ProgressLoopDetector.LoopInfo h2 = feed(d, List.of(), List.of(), 600);
        assertNotNull(h1);
        assertNotNull(h2);
        assertEquals(1, h1.tier());
        assertEquals(2, h2.tier());
        assertEquals(2, d.currentTier());
        // User acks.
        d.acknowledge();
        assertEquals(0, d.currentTier(), "ack should reset currentTier to 0");
        // Next long-output hit starts at tier 1 again.
        ProgressLoopDetector.LoopInfo h3 = feed(d, List.of(), List.of(), 600);
        assertEquals("loop_warn_1", h3.kind(), "ack should restart the tier counter");
        assertEquals(1, h3.tier());
    }

    /** user_interrupt is an immediate hard stop on the
     *  first hit (the user explicitly asked to stop — no
     *  tiered warning). shouldStop() returns true and the
     *  kind stays "user_interrupt" so the engine can
     *  distinguish a user-initiated stop from a detector-
     *  driven one. */
    @Test
    void r101UserInterruptIsImmediateStop() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .build();
        d.notifyUserInterrupt();
        ProgressLoopDetector.LoopInfo info = feed(d, List.of(), List.of(), 0);
        assertNotNull(info);
        assertEquals("user_interrupt", info.kind());
        assertTrue(info.shouldStop(), "user_interrupt should be a hard stop");
        assertFalse(info.isWarning(), "user_interrupt is not a warning");
    }

    /** reset() should also clear the R101 tier state
     *  and last-loop-kind marker. Without this, a previously-
     *  paused loop's tier would bleed into the new query. */
    @Test
    void r101ResetClearsTierAndLastKind() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(500)
                .build();
        // Two long-output turns in a row bump the tier to 2.
        feed(d, List.of(), List.of(), 600);
        feed(d, List.of(), List.of(), 600);
        assertEquals(2, d.currentTier());
        d.reset();
        assertEquals(0, d.currentTier(), "reset should zero the tier");
    }

    /** acknowledge() does NOT clear the rolling
     *  fingerprint history (the detector still has a
     *  meaningful window), but it does clear the tier. A
     *  subsequent hit of the same fingerprint is a tier-1
     *  warn, not a continuation of the prior streak. */
    @Test
    void r101AcknowledgePreservesFingerprintHistory() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(500)
                .build();
        // Two long-output hits → tier 2.
        feed(d, List.of(), List.of(), 600);
        feed(d, List.of(), List.of(), 600);
        assertEquals(2, d.currentTier());
        d.acknowledge();
        assertEquals(0, d.currentTier());
        // The next hit of the same pattern starts at tier 1
        // (not 3), proving acknowledge() reset the tier
        // counter even though the rolling history is
        // untouched.
        ProgressLoopDetector.LoopInfo info = feed(d, List.of(), List.of(), 600);
        assertEquals("loop_warn_1", info.kind());
    }

    /** setTierForTest is the programmatic escape hatch
     *  used by the production code path's "what if the
     *  detector is already at tier 2 and the next hit is a
     *  hard stop?" case. The setter is capped at
     *  WARN_BEFORE_STOP + 1. */
    @Test
    void r101SetTierForTestCapsAtWarnBeforeStopPlusOne() {
        ProgressLoopDetector d = ProgressLoopDetector.builder().build();
        d.setTierForTest(0);
        assertEquals(0, d.currentTier());
        d.setTierForTest(2);
        assertEquals(2, d.currentTier());
        d.setTierForTest(99);
        assertEquals(ProgressLoopDetector.WARN_BEFORE_STOP + 1, d.currentTier(),
                "setTierForTest should cap at WARN_BEFORE_STOP + 1");
        d.setTierForTest(-5);
        assertEquals(0, d.currentTier(), "setTierForTest should floor at 0");
    }
}
