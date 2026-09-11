package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriftTest {

    private static ReasoningUnit unit(String id, String kind, String fix, double utility) {
        return new ReasoningUnit(id, kind, "err-" + id, fix, "ex", utility, 0L, Instant.now());
    }

    @Test
    void emptyBankProducesNoop(@TempDir Path tmp) {
        ReasoningBank bank = new ReasoningBank();
        DriftResult r = Drift.runOnce(bank, tmp.resolve("AGENTS.md"), DriftConfig.defaults());
        assertEquals(0, r.unitsScanned());
        assertEquals(0, r.unitsWritten());
        assertFalse(r.changed());
    }

    @Test
    void missingFileIsCreated(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "file_edit", "ensure dir exists first", 0.9));
        Path md = tmp.resolve("AGENTS.md");
        assertFalse(Files.exists(md));
        DriftResult r = Drift.runOnce(bank, md, DriftConfig.defaults());
        assertTrue(r.changed());
        assertTrue(Files.exists(md));
        String body = Files.readString(md);
        assertTrue(body.contains("<!-- DRIFT:START -->"));
        assertTrue(body.contains("<!-- DRIFT:END -->"));
        assertTrue(body.contains("### file_edit"));
        assertTrue(body.contains("- ensure dir exists first"));
    }

    @Test
    void preservesHostContentOutsideMarkers(@TempDir Path tmp) throws Exception {
        Path md = tmp.resolve("AGENTS.md");
        String original = """
                # My Assistant

                Some hand-written instructions.

                <!-- DRIFT:START -->
                ## Learned strategies
                ### old_kind
                - OLD STRATEGY
                <!-- DRIFT:END -->

                More hand-written content at the end.
                """;
        Files.writeString(md, original);

        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "file_edit", "ensure dir exists first", 0.9));
        Drift.runOnce(bank, md, DriftConfig.defaults());

        String body = Files.readString(md);
        assertTrue(body.contains("# My Assistant"));
        assertTrue(body.contains("Some hand-written instructions."));
        assertTrue(body.contains("More hand-written content at the end."));
        assertFalse(body.contains("OLD STRATEGY"));
        assertTrue(body.contains("ensure dir exists first"));
    }

    @Test
    void topKPerKindCapsOutput(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        for (int i = 0; i < 5; i++) {
            bank.add(unit("u" + i, "build", "strategy-" + i, 0.95));
        }
        Path md = tmp.resolve("AGENTS.md");
        Drift.runOnce(bank, md, DriftConfig.defaults());
        String body = Files.readString(md);
        long count = body.lines().filter(l -> l.startsWith("- strategy-")).count();
        assertEquals(3, count, "topKPerKind=3 should keep only 3 strategies");
    }

    @Test
    void minUtilityFiltersOutLowUtilityUnits(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "high-utility strategy", 0.95));
        bank.add(unit("u2", "k1", "low-utility strategy", 0.4));
        bank.add(unit("u3", "k2", "k2 high", 0.8));
        Path md = tmp.resolve("AGENTS.md");
        DriftResult r = Drift.runOnce(bank, md, DriftConfig.defaults());
        assertEquals(3, r.unitsScanned());
        assertEquals(1, r.unitsBelowThreshold());
        assertEquals(2, r.unitsWritten());
        String body = Files.readString(md);
        assertTrue(body.contains("high-utility strategy"));
        assertFalse(body.contains("low-utility strategy"));
        assertTrue(body.contains("k2 high"));
    }

    @Test
    void multipleKindsAreGrouped(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "file_edit", "ensure dir exists", 0.9));
        bank.add(unit("u2", "build", "use --no-daemon", 0.85));
        bank.add(unit("u3", "test", "isolate flaky tests", 0.8));
        Path md = tmp.resolve("AGENTS.md");
        Drift.runOnce(bank, md, DriftConfig.defaults());
        String body = Files.readString(md);
        assertTrue(body.contains("### file_edit"));
        assertTrue(body.contains("### build"));
        assertTrue(body.contains("### test"));
    }

    @Test
    void idempotentRunLeavesFileUnchanged(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "strategy-a", 0.9));
        Path md = tmp.resolve("AGENTS.md");
        DriftResult first = Drift.runOnce(bank, md, DriftConfig.defaults());
        assertTrue(first.changed());
        long sizeAfterFirst = Files.size(md);

        DriftResult second = Drift.runOnce(bank, md, DriftConfig.defaults());
        assertFalse(second.changed(),
                "second run on unchanged bank should not rewrite the file");
        assertEquals(sizeAfterFirst, Files.size(md),
                "file size should be identical between idempotent runs");
    }

    @Test
    void customMarkersCoexistWithDefaults(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "strategy-x", 0.9));
        Path md = tmp.resolve("AGENTS.md");
        DriftConfig custom = new DriftConfig(
                0.7, 3, "Auto-learned rules",
                "<!-- AUTOGEN:BEGIN -->", "<!-- AUTOGEN:END -->", null);
        Drift.runOnce(bank, md, custom);
        String body = Files.readString(md);
        assertTrue(body.contains("<!-- AUTOGEN:BEGIN -->"));
        assertTrue(body.contains("<!-- AUTOGEN:END -->"));
        assertTrue(body.contains("## Auto-learned rules"));
        assertFalse(body.contains("<!-- DRIFT:START -->"));
    }

    @Test
    void appendsBlockWhenNoMarkerPresent(@TempDir Path tmp) throws Exception {
        Path md = tmp.resolve("AGENTS.md");
        String original = "# Preamble\n\nNothing else here yet.\n";
        Files.writeString(md, original);

        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "new strategy", 0.9));
        Drift.runOnce(bank, md, DriftConfig.defaults());

        String body = Files.readString(md);
        assertTrue(body.contains("# Preamble"));
        assertTrue(body.contains("Nothing else here yet."));
        assertTrue(body.contains("<!-- DRIFT:START -->"));
        assertTrue(body.contains("new strategy"));
        int preambleIdx = body.indexOf("# Preamble");
        int driftIdx = body.indexOf("<!-- DRIFT:START -->");
        assertTrue(preambleIdx < driftIdx,
                "host preamble should come before DRIFT block");
    }

    @Test
    void blankFixStrategyIsSkipped(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "   ", 0.9));
        bank.add(unit("u2", "k1", "real strategy", 0.9));
        Path md = tmp.resolve("AGENTS.md");
        Drift.runOnce(bank, md, DriftConfig.defaults());
        String body = Files.readString(md);
        assertTrue(body.contains("real strategy"));
        long dashes = body.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(1, dashes);
    }

    @Test
    void talonSelfReflectWiringDriftOnceConvenience(@TempDir Path tmp) throws Exception {
        Path md = tmp.resolve("AGENTS.md");
        TalonSelfReflectWiring.Result wiring = TalonSelfReflectWiring.build(tmp, null, Map.of());
        assertTrue(wiring.enabled());
        wiring.bank().add(unit("u1", "k1", "via-helper", 0.9));
        DriftResult r = TalonSelfReflectWiring.driftOnce(wiring, md, DriftConfig.defaults());
        assertTrue(r.changed());
        assertEquals(1, r.unitsWritten());
        assertTrue(Files.readString(md).contains("via-helper"));
    }

    /** R245.4 helper: 10-arg constructor with ok/notOk
     *  counters (Laplace-smoothed confidence = okCount /
     *  (okCount + notOkCount + 1)). */
    private static ReasoningUnit unitWithOutcomes(
            String id, String kind, String fix, double utility,
            long okCount, long notOkCount) {
        return new ReasoningUnit(
                id, kind, "err-" + id, fix, "ex",
                utility, 0L, okCount, notOkCount, Instant.now());
    }

    @Test
    void confidenceAware_highConfidence_beats_higherUtilityZeroObs(@TempDir Path tmp) throws Exception {
        // R245.4: ranking is confidence × utility, not raw
        // utility. A unit with utility 0.95 but 0 observations
        // (Laplace confidence = 0/1 = 0) should rank BELOW a
        // unit with utility 0.75 and 3 ok / 0 notOk
        // (confidence = 3/4 = 0.75; score = 0.5625).
        //
        // previously.4 the 0.95 unit would have won on raw
        // utility. This test pins the new ranking so a future
        // refactor can't silently drift back.
        Path md = tmp.resolve("AGENTS.md");
        ReasoningBank bank = new ReasoningBank();
        bank.add(unitWithOutcomes("high-utility-no-obs", "file_edit",
                "zero-obs loud strategy", 0.95, 0L, 0L));
        bank.add(unitWithOutcomes("lower-utility-trusted", "file_edit",
                "trusted quiet strategy", 0.75, 3L, 0L));

        Drift.runOnce(bank, md, DriftConfig.defaults());

        String body = Files.readString(md);
        int trustedAt = body.indexOf("trusted quiet strategy");
        int loudAt = body.indexOf("zero-obs loud strategy");
        assertTrue(trustedAt > 0, "trusted strategy should be in body");
        assertTrue(loudAt > 0, "loud strategy should be in body");
        assertTrue(trustedAt < loudAt,
                "trusted strategy (0.5625) should rank before loud strategy (0.0)");
    }

    @Test
    void confidenceAware_breaks_ties_by_uses(@TempDir Path tmp) throws Exception {
        // R245.4: when confidence × utility scores tie, the
        // secondary sort is by uses desc. Two units with the
        // same outcome ratio (3 ok / 0 notOk → 0.75 confidence)
        // and the same utility should be broken by uses.
        Path md = tmp.resolve("AGENTS.md");
        ReasoningBank bank = new ReasoningBank();
        // Both have utility 0.8 and confidence 0.75 (3 ok / 0 notOk);
        // score 0.6 each. Tie. Differ only in uses.
        bank.add(new ReasoningUnit(
                "a-low-uses", "file_edit", "err", "low-uses strategy", "ex",
                0.8, 1L, 3L, 0L, Instant.now()));
        bank.add(new ReasoningUnit(
                "b-high-uses", "file_edit", "err", "high-uses strategy", "ex",
                0.8, 5L, 3L, 0L, Instant.now()));

        Drift.runOnce(bank, md, DriftConfig.defaults());

        String body = Files.readString(md);
        int highAt = body.indexOf("high-uses strategy");
        int lowAt = body.indexOf("low-uses strategy");
        assertTrue(highAt > 0 && lowAt > 0);
        assertTrue(highAt < lowAt,
                "high-uses unit should win the tie-break on uses desc");
    }
}
