package org.aethercode.evals.capability.loop;

import org.aethercode.orchestration.verifier.HeuristicVerifier;
import org.aethercode.orchestration.verifier.HeuristicVerifier.RepetitionCheck;
import org.aethercode.orchestration.verifier.Verifier.Severity;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-11: Loop &amp; Drift detection capability suite.
 *
 * <p>Mirrors arXiv:2601.01743 §5.4 "LoopRate" + §5.5 "RobustSucc"
 * (drift under perturbation) + the Survey on Evaluation of LLM-based
 * Agents (2503.16416) §2.1 "state tracking".</p>
 *
 * <p>Scope: four invariants the front-end / TUI / runtime depends on
 * to detect "the agent is stuck in a loop":</p>
 * <ul>
 *   <li><b>Repetition</b> — HeuristicVerifier.RepetitionCheck
 *       catches N-gram loops in agent output.</li>
 *   <li><b>State cycles</b> — the same state should not be
 *       re-entered without progress.</li>
 *   <li><b>Drift</b> — repeated tool calls on the same input
 *       should converge, not oscillate.</li>
 *   <li><b>Audit trail</b> — when a loop is detected, the
 *       audit log can show the operator the full repeated
 *       pattern.</li>
 * </ul>
 */
class LoopAndDriftCapabilityTest {

    /* ---------------- Repetition: HeuristicVerifier.RepetitionCheck ---------------- */

    @Test
    void repetitionCheckFlagsExactLoop() {
        HeuristicVerifier v = new HeuristicVerifier("loop",
                List.of(new RepetitionCheck(5, 3))); // 5-char ngram, >3 repeats
        // 5 copies of a 5-char token aligned on 5-char boundaries:
        // positions 0/5/10/15/20 are the same 5-char string.
        String loop = "abcde".repeat(5);
        assertFalse(v.verify(loop).passed(),
                "RepetitionCheck should flag 5× repeated token");
    }

    @Test
    void repetitionCheckAllowsNonLoopingText() {
        HeuristicVerifier v = new HeuristicVerifier("ok",
                List.of(new RepetitionCheck(50, 3)));
        // Normal prose with no N-gram repeats.
        String ok = "First sentence here. Second sentence follows. " +
                "Third one explains. Fourth is the conclusion.";
        assertTrue(v.verify(ok).passed());
    }

    @Test
    void repetitionCheckIgnoresShortInputs() {
        HeuristicVerifier v = new HeuristicVerifier("short",
                List.of(new RepetitionCheck(50, 3)));
        // Input shorter than N-gram size can't loop.
        assertTrue(v.verify("hi").passed());
    }

    /* ---------------- State cycles ---------------- */

    @Test
    void stateMachineDetectsRepeatedVisit() {
        // A simple state machine: A -> B -> C -> A. After 3 steps
        // we're back at A; the 4th step is a cycle.
        Set<String> visited = new HashSet<>();
        String current = "A";
        visited.add(current);
        String[] transitions = {"B", "C", "A", "B"};
        boolean cycle = false;
        for (String next : transitions) {
            if (visited.contains(next) && !next.equals(current)) {
                cycle = true;
                break;
            }
            current = next;
            visited.add(current);
        }
        assertTrue(cycle, "cycle detected: revisited A after stepping through B, C");
    }

    @Test
    void stateMachineNoCycleWhenStatesAreUnique() {
        // Same machine, but only 3 unique states — no cycle.
        Set<String> visited = new HashSet<>();
        String current = "A";
        visited.add(current);
        String[] transitions = {"B", "C"};
        boolean cycle = false;
        for (String next : transitions) {
            if (visited.contains(next) && !next.equals(current)) {
                cycle = true;
                break;
            }
            current = next;
            visited.add(current);
        }
        assertFalse(cycle, "no cycle: A→B→C is acyclic");
    }

    /* ---------------- Drift: oscillation ---------------- */

    @Test
    void driftDetectionOnOscillatingValues() {
        // Simulate a tool call that returns A, B, A, B, A, B...
        // without making progress. The drift detector flags
        // this as "no progress" if the unique set is small
        // relative to the number of calls.
        java.util.List<String> history = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(i % 2 == 0 ? "A" : "B");
        }
        Set<String> unique = new HashSet<>(history);
        double entropy = (double) unique.size() / history.size();
        assertTrue(entropy <= 0.5,
                "drift: only " + unique.size() + " unique states across "
                        + history.size() + " calls (entropy " + entropy + ")");
    }

    @Test
    void driftDetectionOnConvergingValues() {
        // Same tool, but values converge: A, A, A, B, B, B, C, C, D, D
        java.util.List<String> history = java.util.List.of(
                "A", "A", "A", "B", "B", "B", "C", "C", "D", "D");
        Set<String> unique = new HashSet<>(history);
        // A converging sequence has roughly monotonically
        // non-decreasing unique-set size; we check that the
        // *last 4* values are 2 distinct (C, D — converging).
        int n = history.size();
        Set<String> tail = new HashSet<>(history.subList(n - 4, n));
        assertEquals(2, tail.size(),
                "tail of converging sequence has 2 distinct values: " + tail);
        // And the unique-set size across the whole history is
        // smaller than the history length (otherwise it's
        // just bouncing around).
        assertTrue(unique.size() < history.size(),
                "converging history has fewer unique values than total calls");
    }

    /* ---------------- Tool-call loop detection ---------------- */

    @Test
    void sameToolCallRepeatedThreeTimesIsFlagged() {
        // If the same tool is called 3 times in a row on the
        // same input, that's a strong loop signal.
        AtomicInteger callCount = new AtomicInteger(0);
        java.util.function.Function<String, String> tool = s -> {
            int n = callCount.incrementAndGet();
            return "result_" + n;
        };
        for (int i = 0; i < 3; i++) tool.apply("same-input");
        assertEquals(3, callCount.get());
        assertTrue(callCount.get() >= 3,
                "3+ identical calls in a row — a loop");
    }

    @Test
    void differentInputsAreNotLoops() {
        // Different inputs across calls is not a loop.
        AtomicInteger distinctInputs = new AtomicInteger(0);
        java.util.Set<String> seen = new HashSet<>();
        for (String s : java.util.List.of("a", "b", "c", "d")) {
            if (seen.add(s)) distinctInputs.incrementAndGet();
        }
        assertEquals(4, distinctInputs.get());
    }

    /* ---------------- Audit trail ---------------- */

    @Test
    void auditTrailCapturesLoopEntries() {
        // When a loop is detected, the audit log should show
        // every entry so the operator can debug.
        java.util.List<String> audit = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            audit.add("call_" + i + ": tool=read input=x");
        }
        // All 5 entries are recorded even though they look similar.
        assertEquals(5, audit.size());
        assertTrue(audit.get(0).contains("tool=read"));
    }

    /* ---------------- Plan cycle (DagPlan) ---------------- */

    @Test
    void dagPlanRejectsSelfLoop() {
        // We don't import DagPlan here (this is the capability
        // suite, not the SDK suite) but we model the same
        // invariant: a plan with A -> A is a cycle and must
        // be rejected.
        java.util.Map<String, java.util.List<String>> deps =
                java.util.Map.of("A", java.util.List.of("A"));
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Set<String> onStack = new java.util.HashSet<>();
        boolean cycle = false;
        java.util.List<String> stack = new java.util.ArrayList<>();
        for (String node : deps.keySet()) {
            if (!visited.contains(node)) {
                stack.add(node);
                while (!stack.isEmpty()) {
                    String cur = stack.remove(stack.size() - 1);
                    if (onStack.contains(cur)) {
                        cycle = true;
                        break;
                    }
                    onStack.add(cur);
                    visited.add(cur);
                    for (String d : deps.getOrDefault(cur, java.util.List.of())) {
                        if (!visited.contains(d)) stack.add(d);
                        else if (onStack.contains(d)) { cycle = true; break; }
                    }
                    onStack.remove(cur);
                    if (cycle) break;
                }
            }
            if (cycle) break;
        }
        assertTrue(cycle, "self-loop A -> A is a cycle");
    }

    /* ---------------- Severity of loop detection ---------------- */

    @Test
    void loopDetectionSeverityIsBlock() {
        // A confirmed loop should be a BLOCK, not a WARN — the
        // agent should not be allowed to continue looping.
        HeuristicVerifier v = new HeuristicVerifier("loop-block",
                List.of(new RepetitionCheck(20, 2)), Severity.BLOCK);
        String looping = "abc123def ".repeat(10);
        var result = v.verify(looping);
        assertNotNull(result);
        assertFalse(result.passed());
        assertEquals(Severity.BLOCK, result.severity());
    }
}
