package org.aethercode.core.eval;

import org.aethercode.core.cost.CostTracker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R697: verify AgentRunner's per-run cost + token diff helpers.
 *
 * <p>Before the fix, result.costUsd / result.tokensInput / result.tokensOutput
 * were cumulative across all evalRunTest calls on the same engine, so the
 * harness saw the same numbers grow across runs. After the fix, the runner
 * snapshots cost + token counters at the start of each run and reports the
 * diff via {@link AgentRunner#snapshot} / {@link AgentRunner#computeDelta}.
 *
 * <p>We test the helpers directly (no QueryEngine mock needed) — those
 * helpers are pure functions of a {@link CostLedger} state, which is
 * itself trivially fakeable.
 */
public class AgentRunnerDiffTest {

    /**
     * Mutable CostLedger stub whose totals can be set from outside.
     * We don't subclass CostLedger (it's final) — instead, we inject
     * a real CostLedger backed by a real CostTracker and pump values
     * through it via record().
     */
    static class FakeCostLedger {
        final CostTracker tracker = new CostTracker();
        final String model;
        FakeCostLedger(String model, long in, long out) {
            this.model = model;
            if (in > 0 || out > 0) {
                tracker.record(model, new CostTracker.Usage((int) in, (int) out));
            }
        }
        CostLedger ledger() { return new CostLedger(tracker); }
        void add(long in, long out) {
            tracker.record(model, new CostTracker.Usage((int) in, (int) out));
        }
    }

    @Test
    public void snapshotCapturesCurrentLedgerState() {
        FakeCostLedger fake = new FakeCostLedger("minimax", 500, 100);
        CostLedger ledger = fake.ledger();
        AgentRunner.Snapshot s = AgentRunner.snapshot(ledger);
        assertEquals(500, s.in());
        assertEquals(100, s.out());
        // 500 in @ 0.003 + 100 out @ 0.015 = 1.5 + 1.5 = 3.0 cents = 0.003 USD
        // Actually 500/1000 * 0.003 + 100/1000 * 0.015 = 0.0015 + 0.0015 = 0.003
        assertEquals(0.003, s.usd(), 0.0001);
    }

    @Test
    public void deltaReportsOnlyTheRunIncrement() {
        // Engine already has 1000 input + 500 output tokens, $X cost.
        FakeCostLedger fake = new FakeCostLedger("minimax", 1000, 500);
        CostLedger ledger = fake.ledger();
        AgentRunner.Snapshot start = AgentRunner.snapshot(ledger);

        // During this run, the engine adds 250 input + 50 output tokens.
        fake.add(250, 50);

        AgentRunner.Delta d = AgentRunner.computeDelta(ledger, start);
        assertEquals(250, d.in(),
                "delta should equal only the run's increment, not cumulative");
        assertEquals(50, d.out());
        // Cost delta: 250/1000*0.003 + 50/1000*0.015 = 0.00075 + 0.00075 = 0.0015
        assertEquals(0.0015, d.usd(), 0.0001);
    }

    @Test
    public void secondRunDiffIsOnlyNewlyAdded() {
        // Run 1: tracker has 100 in, $X
        FakeCostLedger fake = new FakeCostLedger("minimax", 100, 0);
        CostLedger ledger = fake.ledger();
        AgentRunner.Snapshot s1 = AgentRunner.snapshot(ledger);
        fake.add(100, 0); // run 1 added 100
        AgentRunner.Delta d1 = AgentRunner.computeDelta(ledger, s1);
        assertEquals(100, d1.in());

        // Run 2: ledger now has 200 in. Snapshot before run 2.
        AgentRunner.Snapshot s2 = AgentRunner.snapshot(ledger);
        fake.add(50, 0); // run 2 adds 50
        AgentRunner.Delta d2 = AgentRunner.computeDelta(ledger, s2);
        assertEquals(50, d2.in(),
                "second run diff must exclude prior runs' tokens");
    }

    @Test
    public void emptyRunProducesZeroDelta() {
        FakeCostLedger fake = new FakeCostLedger("minimax", 5000, 1000);
        CostLedger ledger = fake.ledger();
        AgentRunner.Snapshot s = AgentRunner.snapshot(ledger);
        // no add() — simulate a run that did no LLM work
        AgentRunner.Delta d = AgentRunner.computeDelta(ledger, s);
        assertEquals(0, d.in());
        assertEquals(0, d.out());
        assertEquals(0.0, d.usd(), 0.0001);
    }

    @Test
    public void nullLedgerSnapshotsAndDeltasToZero() {
        AgentRunner.Snapshot s = AgentRunner.snapshot(null);
        assertEquals(0, s.in());
        assertEquals(0, s.out());
        assertEquals(0.0, s.usd(), 0.0001);

        AgentRunner.Delta d = AgentRunner.computeDelta(null, s);
        assertEquals(0, d.in());
        assertEquals(0, d.out());
        assertEquals(0.0, d.usd(), 0.0001);
    }

    @Test
    public void truncatedLedgerSnapshotStillProducesNonNegativeDelta() {
        // Edge case: ledger somehow shrank (e.g. engine reset). Diff should
        // clamp to 0, not go negative.
        FakeCostLedger fake = new FakeCostLedger("minimax", 1000, 100);
        CostLedger ledger = fake.ledger();
        AgentRunner.Snapshot s = AgentRunner.snapshot(ledger);
        // simulate tracker reset
        new java.util.concurrent.atomic.AtomicLong();
        // Direct manipulation: re-create ledger with lower values.
        FakeCostLedger fake2 = new FakeCostLedger("minimax", 100, 10);
        AgentRunner.Delta d = AgentRunner.computeDelta(fake2.ledger(), s);
        assertEquals(0, d.in(), "delta must clamp to 0, never negative");
        assertEquals(0, d.out());
        assertEquals(0.0, d.usd(), 0.0001);
    }
}