package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R136.3 + R136.4 tests for {@link ProgressLoopDetector}.
 *
 * <p>Covers the new "no file_write progress" detector
 * and the {@code forMaxContext} factory.
 */
class ProgressLoopDetectorR136Test {

    private static ContentBlock.ToolUseBlock toolUse(String name, String path) {
        return new ContentBlock.ToolUseBlock(
                "id-" + name, name,
                path == null ? Map.of() : Map.of("path", path));
    }

    private static ProgressLoopDetector.BatchResult result(String name, String out, boolean isError) {
        return new ProgressLoopDetector.BatchResult("id-" + name, name, out, isError);
    }

    @Test
    void forMaxContextScalesAllThresholds() {
        ProgressLoopDetector d = ProgressLoopDetector.forMaxContext();
        // R136.4: 1M context thresholds.
        assertEquals(50, d.window());
        assertEquals(10, d.fingerprintThreshold());
        assertEquals(100_000, d.longOutputThreshold());
        assertEquals(3, d.longOutputConsecutive());
        assertEquals(8, d.warnBeforeStop());
    }

    @Test
    void noFileWriteStreakIncrements() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        // 3 turns of "all read tools" — no file_write.
        for (int i = 0; i < 3; i++) {
            d.recordBatch(
                    List.of(toolUse("file_read", "a.txt"),
                            toolUse("glob", "**/*.py")),
                    List.of(result("file_read", "content", false)),
                    500);
        }
        assertEquals(0, d.fileWriteCount());
        assertEquals(3, d.noFileWriteStreak());
        assertEquals(3, d.turnCount());
    }

    @Test
    void noFileWriteStreakResetsOnWrite() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        // 2 turns of no fw
        d.recordBatch(List.of(toolUse("file_read", "a.txt")),
                List.of(), 100);
        d.recordBatch(List.of(toolUse("file_read", "b.txt")),
                List.of(), 100);
        assertEquals(2, d.noFileWriteStreak());
        // 1 turn with fw — resets streak
        d.recordBatch(List.of(toolUse("file_write", "c.txt")),
                List.of(result("file_write", "wrote 100 bytes to c.txt", false)),
                100);
        assertEquals(1, d.fileWriteCount());
        assertEquals(0, d.noFileWriteStreak());
    }

    @Test
    void noFileWriteWarnsAfterMaxTurns() {
        // the detector requires BOTH a high
        // no-file-write streak AND a high shell-only
        // ratio. Read-only turns (file_read) don't
        // bump the shell ratio, so a model that
        // reads but doesn't bash won't trip the
        // hard-stop. This test uses bash-only turns
        // to exercise the trigger.
        // we disable research-mode (set
        // small-output threshold very high) so the
        // test is purely about the noFileWriteProgress
        // path.
        // we use SMALL bash output so the
        // smart-skip (bigOutputCount >= 2) does NOT
        // relax the threshold. This is the "model
        // is making NO progress" case, not the
        // "model is doing real work via shell" case.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        d.setMaxNoFileWriteTurns(3);  // tighten for the test
        d.setMaxSmallOutputStreak(99);  // disable R138 path
        // 3 bash turns (priming, no fire — turnCount <= 3 guard).
        for (int i = 0; i < 3; i++) {
            ProgressLoopDetector.LoopInfo r = d.recordBatch(
                    List.of(toolUse("bash", null)),
                    List.of(result("bash", "(exit 0)\necho " + i + "\n", false)),
                    50);
            assertNull(r, "priming bash turn " + (i + 1) + " should not fire (turnCount guard)");
        }
        // 4th bash turn: streak=4 ≥ 3, turnCount=4 > 3,
        // shell ratio = 4/4 = 100% ≥ 50%. Hard stop.
        ProgressLoopDetector.LoopInfo r4 = d.recordBatch(
                List.of(toolUse("bash", null)),
                List.of(result("bash", "(exit 0)\necho done\n", false)),
                50);
        assertNotNull(r4);
        assertTrue(r4.shouldStop(),
                "no_file_write_progress should hard-stop on first qualifying hit");
        assertTrue(r4.description().contains("no file_write"),
                "description should mention no_file_write: " + r4.description());
    }

    @Test
    void noFileWriteHardStopDoesNotEmitWarning() {
        // R136.3: confirm the new "no tier warning" path —
        // shouldStop() is true on the very first hit.
        // also exercises the shell-ratio guard. The
        // guard must KEEP a normal Q&A run from tripping
        // (25% and 40% shell ratio turns return null), then
        // a 50% shell-ratio turn hard-stops immediately
        // (no warning tier — direct loop_detected).
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(2).build();
        d.setMaxNoFileWriteTurns(3);
        // 3 read-only turns prime the no-file-write streak.
        // shellOnly=0 because none of them were bash.
        for (int i = 0; i < 3; i++) {
            d.recordBatch(List.of(toolUse("file_read", "x" + i + ".txt")),
                    List.of(), 100);
        }
        // 4th turn: bash command (not read). Streak=4,
        // turnCount=4, shellOnly=1, ratio=25% — UNDER
        // the 50% threshold so no hard stop. The 50%
        // guard prevents false positives on a normal
        // Q&A run that happens to be light on writes.
        ProgressLoopDetector.LoopInfo r4 = d.recordBatch(
                List.of(toolUse("bash", null)),
                List.of(result("bash", "(exit 0)\nhello\n", false)),
                100);
        assertNull(r4, "50% guard should NOT trip at 25% shell ratio — Q&A run still alive");
        // 5th turn: another bash. Streak=5, shellOnly=2,
        // ratio=40% — still UNDER threshold, no trip.
        ProgressLoopDetector.LoopInfo r5 = d.recordBatch(
                List.of(toolUse("bash", null)),
                List.of(result("bash", "(exit 0)\nworld\n", false)),
                100);
        assertNull(r5, "50% guard should NOT trip at 40% shell ratio — Q&A run still alive");
        // 6th turn: another bash. Streak=6, shellOnly=3,
        // ratio=50% — AT threshold, hard stop immediately
        // (no warning tier, direct loop_detected).
        ProgressLoopDetector.LoopInfo r6 = d.recordBatch(
                List.of(toolUse("bash", null)),
                List.of(result("bash", "(exit 0)\nfoo\n", false)),
                100);
        assertNotNull(r6, "50% shell ratio + 6-turn streak should hard-stop");
        assertFalse(r6.isWarning(),
                "no_file_write_progress should NOT be a warning tier");
        assertTrue(r6.shouldStop(),
                "no_file_write_progress should be a hard stop on first hit");
    }

    @Test
    void fileWriteDuringMixedBatchCountsAsProgress() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        // Mixed: file_read + file_write in same turn.
        d.recordBatch(
                List.of(toolUse("file_read", "a.txt"),
                        toolUse("file_write", "b.txt")),
                List.of(result("file_write", "wrote 50 bytes", false)),
                100);
        assertEquals(1, d.fileWriteCount());
        assertEquals(0, d.noFileWriteStreak());
    }

    @Test
    void shellCallsIncrementShellOnlyCount() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        d.recordBatch(
                List.of(toolUse("bash", null)),
                List.of(result("bash", "(exit 0)\n--- stdout ---\nhello\n", false)),
                50);
        assertEquals(1, d.shellOnlyCount());
        assertEquals(0, d.fileWriteCount());
    }

    @Test
    void resetClearsAllCounters() {
        ProgressLoopDetector d = ProgressLoopDetector.forMaxContext();
        d.recordBatch(List.of(toolUse("file_write", "a.txt")),
                List.of(), 100);
        d.recordBatch(List.of(toolUse("file_write", "b.txt")),
                List.of(), 100);
        assertEquals(2, d.fileWriteCount());
        d.reset();
        assertEquals(0, d.fileWriteCount());
        assertEquals(0, d.turnCount());
        assertEquals(0, d.noFileWriteStreak());
        assertEquals(0, d.shellOnlyCount());
    }

    @Test
    void researchModeFiresOnSmallOutputShellStreak() {
        // 3 consecutive bash turns with small output
        // (mvn -version, java -version, echo "test")
        // should hard-stop BEFORE the noFileWriteProgress
        // detector at 10 batches. This is the
        // "model stuck in research mode" case the user
        // hit on the smoke test — model runs 5+ mvn -version
        // before getting stopped.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        d.setMaxNoFileWriteTurns(10);  // leave R137 default
        d.setMaxSmallOutputStreak(3);
        // 3 bash turns, each with a small output (~50 chars).
        for (int i = 0; i < 3; i++) {
            ProgressLoopDetector.LoopInfo r = d.recordBatch(
                    List.of(toolUse("bash", null)),
                    List.of(result("bash", "Apache Maven 3.9.1\nMaven home: D:\\maven\nJava version: 25", false)),
                    50);
            // First 2 should NOT fire (streak < 3); 3rd should.
            if (i < 2) {
                assertNull(r, "research-mode priming turn " + (i + 1) + " should not fire");
            } else {
                assertNotNull(r, "research-mode should fire on 3rd small-output bash turn");
                assertTrue(r.shouldStop(), "research-mode is a hard stop, not a warning");
                assertTrue(r.description().contains("research mode"),
                        "description should mention research mode: " + r.description());
            }
        }
        assertEquals(3, d.smallOutputStreak());
    }

    @Test
    void researchModeResetsOnBigOutputShell() {
        // a real shell command (mvn test, build) that
        // produces big output should NOT count as research.
        // The streak resets when shell output > threshold.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        d.setMaxSmallOutputStreak(3);
        // 2 small-output bash turns.
        d.recordBatch(List.of(toolUse("bash", null)),
                List.of(result("bash", "Apache Maven 3.9.1", false)), 50);
        d.recordBatch(List.of(toolUse("bash", null)),
                List.of(result("bash", "openjdk version 25", false)), 50);
        assertEquals(2, d.smallOutputStreak());
        // 3rd turn: BIG output (mvn test compilation logs).
        // Reset streak.
        String bigOut = "lots of maven output\n" + "x".repeat(2000);
        ProgressLoopDetector.LoopInfo r = d.recordBatch(
                List.of(toolUse("bash", null)),
                List.of(result("bash", bigOut, false)),
                50);
        assertNull(r, "big-output bash should not trip research-mode");
        assertEquals(0, d.smallOutputStreak());
    }

    @Test
    void researchModeResetsOnFileWrite() {
        // a file_write overrides any research-mode
        // signal — the model is making real progress.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(5)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        d.setMaxSmallOutputStreak(3);
        d.recordBatch(List.of(toolUse("bash", null)),
                List.of(result("bash", "Apache Maven 3.9.1", false)), 50);
        d.recordBatch(List.of(toolUse("bash", null)),
                List.of(result("bash", "openjdk version 25", false)), 50);
        assertEquals(2, d.smallOutputStreak());
        // 3rd turn: file_write. Resets.
        ProgressLoopDetector.LoopInfo r = d.recordBatch(
                List.of(toolUse("file_write", "pom.xml")),
                List.of(result("file_write", "wrote 200 bytes to pom.xml", false)),
                100);
        assertNull(r, "file_write should not trip research-mode");
        assertEquals(0, d.smallOutputStreak());
    }

    @Test
    void noFileWriteSmartSkipsWhenBigOutputBashPresent() {
        // when the model has been doing real work
        // (big-output shell like mvn test / dir /s),
        // the noFileWriteProgress detector should
        // RELAX its threshold to give the model more
        // time to write files. This is the user-reported
        // case where a model alternates between small
        // bash (mvn -version) and big bash (dir /s /b)
        // and trips no_file_write_progress after 10
        // turns even though it's clearly making
        // progress.
        // Disable the same_fingerprint detector (fp
        // threshold = 20) so 7+ bash calls don't
        // trip the wrong loop kind.
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(20)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        d.setMaxNoFileWriteTurns(3);  // tighten
        d.setMaxSmallOutputStreak(99);  // disable R138 path
        // this test exercises the R146 noFileWriteProgress
        // detector with `bash null` (empty input) on every
        // turn. We disable the R174 emptyInputStreak detector
        // so it doesn't pre-empt the noFileWriteProgress
        // verdict at streak 3. The emptyInputStreak detector
        // has its own dedicated tests in
        // {@code ProgressLoopDetectorR174Test}.
        d.setEmptyInputStreakThreshold(99);
        // 2 big-output bash turns. With R146,
        // bigOutputCount >= 2 doubles the effective
        // threshold to 6.
        d.recordBatch(List.of(toolUse("bash", null)),
                List.of(result("bash", "x".repeat(2000), false)), 50);
        d.recordBatch(List.of(toolUse("bash", null)),
                List.of(result("bash", "y".repeat(2000), false)), 50);
        assertEquals(2, d.bigOutputCount());
        // 3 more small-output bash turns (streak 3, 4, 5).
        // None should fire (effective max = 6).
        for (int i = 0; i < 3; i++) {
            ProgressLoopDetector.LoopInfo r = d.recordBatch(
                    List.of(toolUse("bash", null)),
                    List.of(result("bash", "small " + i, false)),
                    50);
            assertNull(r, "R146 smart-skip should hold off at streak " + (i + 3));
        }
        // 4th small-output bash turn: streak=6,
        // effective max=6, FIRE.
        ProgressLoopDetector.LoopInfo r6 = d.recordBatch(
                List.of(toolUse("bash", null)),
                List.of(result("bash", "small 4", false)),
                50);
        assertNotNull(r6, "R146 should fire at 2x threshold (6) when big-output present");
        assertTrue(r6.description().contains("big_output_bash=2"),
                "description should mention the big-output count: " + r6.description());
    }

    @Test
    void noFileWriteFiresNormallyWithoutBigOutput() {
        // control test — without big-output
        // bash, the noFileWriteProgress detector
        // fires at the normal threshold (no
        // smart-skip relaxation).
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(20).fingerprintThreshold(20)
                .longOutputThreshold(10_000).longOutputConsecutive(2)
                .warnBeforeStop(4).build();
        d.setMaxNoFileWriteTurns(3);
        d.setMaxSmallOutputStreak(99);
        // 4 small-output bash turns (no big output).
        for (int i = 0; i < 3; i++) {
            d.recordBatch(List.of(toolUse("bash", null)),
                    List.of(result("bash", "small " + i, false)), 50);
        }
        assertEquals(0, d.bigOutputCount());
        // 4th turn: streak=4, threshold=3, FIRE.
        ProgressLoopDetector.LoopInfo r4 = d.recordBatch(
                List.of(toolUse("bash", null)),
                List.of(result("bash", "small 3", false)),
                50);
        assertNotNull(r4, "without R146 smart-skip, noFileWrite fires at normal threshold");
    }
}
