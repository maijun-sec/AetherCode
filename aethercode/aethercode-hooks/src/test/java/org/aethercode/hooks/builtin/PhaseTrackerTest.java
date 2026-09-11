package org.aethercode.hooks.builtin;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tests for the {@link PhaseTracker} primitive.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Default budget (4 phases with sensible caps).</li>
 *   <li>Counter accumulation on {@link PhaseTracker#recordToolCall}.</li>
 *   <li>{@link PhaseTracker#isOverBudget} detects both
 *       tool-call and USD-cap exhaustion.</li>
 *   <li>Phase transition resets per-phase counters
 *       (the new phase starts at 0).</li>
 *   <li>{@link PhaseTracker#setBudget} reconfigures caps
 *       (0 = unlimited, negative rejected).</li>
 *   <li>{@link PhaseTracker#inferPhaseFromTool} heuristic
 *       classifies read / write / test tools.</li>
 *   <li>{@link PhaseTracker#snapshot} is immutable from
 *       the caller's perspective.</li>
 * </ul>
 */
class PhaseTrackerTest {

    // -----------------------------------------------------------------
    //  Default budget
    // -----------------------------------------------------------------

    @Test
    void defaultBudgets_coverAllFourPhases() {
        Map<String, PhaseTracker.Bucket> b = PhaseTracker.defaultBudgets();
        assertThat(b).containsKeys(
                PhaseTracker.PHASE_PLAN,
                PhaseTracker.PHASE_EXPLORE,
                PhaseTracker.PHASE_IMPLEMENT,
                PhaseTracker.PHASE_VERIFY);
    }

    @Test
    void defaultBudgets_haveNonZeroCaps() {
        // Each phase has a tool-call cap > 0 so the
        // budget enforcement actually does something.
        // (A cap of 0 is treated as "unlimited" and
        // would be useless in the default config.)
        for (Map.Entry<String, PhaseTracker.Bucket> e : PhaseTracker.defaultBudgets().entrySet()) {
            assertThat(e.getValue().maxToolCalls)
                    .as("phase %s should have a non-zero default cap", e.getKey())
                    .isGreaterThan(0);
        }
    }

    @Test
    void newTracker_seedsAllFourPhases() {
        PhaseTracker t = new PhaseTracker();
        // After construction, every canonical phase has
        // a bucket (so getBucket(plan) never NPEs even
        // before the user calls setBudget).
        assertThat(t.snapshot().buckets()).containsKeys(
                PhaseTracker.PHASE_PLAN,
                PhaseTracker.PHASE_EXPLORE,
                PhaseTracker.PHASE_IMPLEMENT,
                PhaseTracker.PHASE_VERIFY);
    }

    @Test
    void newTracker_startsAtDefaultPhase() {
        assertThat(new PhaseTracker().currentPhase()).isEqualTo(PhaseTracker.DEFAULT_PHASE);
    }

    // -----------------------------------------------------------------
    //  Counter accumulation
    // -----------------------------------------------------------------

    @Test
    void recordToolCall_incrementsCount() {
        PhaseTracker t = new PhaseTracker();
        t.recordToolCall("read_file", 0.0);
        t.recordToolCall("read_file", 0.0);
        PhaseTracker.Bucket b = t.snapshot().buckets().get(PhaseTracker.DEFAULT_PHASE);
        assertThat(b.toolCalls).isEqualTo(2);
    }

    @Test
    void recordToolCall_accumulatesCost() {
        PhaseTracker t = new PhaseTracker();
        t.recordToolCall("read_file", 0.01);
        t.recordToolCall("read_file", 0.02);
        PhaseTracker.Bucket b = t.snapshot().buckets().get(PhaseTracker.DEFAULT_PHASE);
        assertThat(b.costUsd).isCloseTo(0.03, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void recordToolCall_isolatedToCurrentPhase() {
        PhaseTracker t = new PhaseTracker();
        // 3 calls in the default (plan) phase.
        t.recordToolCall("todo_write", 0.0);
        t.recordToolCall("todo_write", 0.0);
        t.recordToolCall("todo_write", 0.0);
        // Transition to explore. New phase starts fresh.
        t.setPhase(PhaseTracker.PHASE_EXPLORE);
        t.recordToolCall("read_file", 0.0);
        PhaseTracker.Snapshot s = t.snapshot();
        // The plan bucket still shows the 3 calls
        // (we never reset historical buckets — the
        // user can audit a full session via the wire
        // snapshot).
        assertThat(s.buckets().get(PhaseTracker.PHASE_PLAN).toolCalls).isEqualTo(3);
        // The explore bucket shows just the 1 call.
        assertThat(s.buckets().get(PhaseTracker.PHASE_EXPLORE).toolCalls).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    //  Over-budget detection
    // -----------------------------------------------------------------

    @Test
    void isOverBudget_falseAtStart() {
        assertThat(new PhaseTracker().isOverBudget()).isFalse();
    }

    @Test
    void isOverBudget_trueWhenToolCallsExhausted() {
        PhaseTracker t = new PhaseTracker();
        // Plan default cap is 2.
        t.recordToolCall("todo_write", 0.0);
        t.recordToolCall("todo_write", 0.0);
        assertThat(t.isOverBudget())
                .as("two calls in plan = at cap = over budget")
                .isTrue();
    }

    @Test
    void isOverBudget_trueWhenCostExhausted() {
        // Build a tracker with a tight cost cap on plan.
        Map<String, PhaseTracker.Bucket> b = new LinkedHashMap<>();
        b.put(PhaseTracker.PHASE_PLAN, new PhaseTracker.Bucket(100, 0.05));
        PhaseTracker t = new PhaseTracker(b);
        t.recordToolCall("todo_write", 0.06);
        assertThat(t.isOverBudget())
                .as("$0.06 > $0.05 cap = over budget")
                .isTrue();
    }

    @Test
    void isOverBudget_zeroCapMeansUnlimited() {
        // A maxToolCalls of 0 is documented as "unlimited".
        PhaseTracker t = new PhaseTracker();
        t.setBudget(PhaseTracker.DEFAULT_PHASE, 0, 0.0);
        for (int i = 0; i < 1000; i++) {
            t.recordToolCall("x", 1.0);
        }
        assertThat(t.isOverBudget()).isFalse();
    }

    @Test
    void isOverBudget_resetsAfterPhaseTransition() {
        PhaseTracker t = new PhaseTracker();
        t.recordToolCall("x", 0.0);
        t.recordToolCall("x", 0.0);
        assertThat(t.isOverBudget()).isTrue();
        // Transition resets the *new* phase's counters.
        t.setPhase(PhaseTracker.PHASE_EXPLORE);
        assertThat(t.isOverBudget()).isFalse();
    }

    // -----------------------------------------------------------------
    //  setBudget reconfiguration
    // -----------------------------------------------------------------

    @Test
    void setBudget_updatesMaxToolCalls() {
        PhaseTracker t = new PhaseTracker();
        t.setBudget(PhaseTracker.PHASE_EXPLORE, 100, 0.0);
        assertThat(t.snapshot().buckets().get(PhaseTracker.PHASE_EXPLORE).maxToolCalls).isEqualTo(100);
    }

    @Test
    void setBudget_doesNotResetCounters() {
        PhaseTracker t = new PhaseTracker();
        t.setPhase(PhaseTracker.PHASE_EXPLORE);
        t.recordToolCall("x", 0.0);
        t.recordToolCall("x", 0.0);
        // Raising the cap doesn't reset the
        // already-spent counter.
        t.setBudget(PhaseTracker.PHASE_EXPLORE, 100, 0.0);
        assertThat(t.snapshot().buckets().get(PhaseTracker.PHASE_EXPLORE).toolCalls).isEqualTo(2);
    }

    @Test
    void setBudget_negativeRejected() {
        assertThatThrownBy(() -> new PhaseTracker().setBudget(PhaseTracker.PHASE_PLAN, -1, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseTracker().setBudget(PhaseTracker.PHASE_PLAN, 0, -0.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setBudget_blankPhaseRejected() {
        assertThatThrownBy(() -> new PhaseTracker().setBudget("", 10, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseTracker().setBudget(null, 10, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------
    //  setPhase transition
    // -----------------------------------------------------------------

    @Test
    void setPhase_changesCurrentPhase() {
        PhaseTracker t = new PhaseTracker();
        t.setPhase(PhaseTracker.PHASE_EXPLORE);
        assertThat(t.currentPhase()).isEqualTo(PhaseTracker.PHASE_EXPLORE);
    }

    @Test
    void setPhase_unknownPhaseCreatesBucket() {
        PhaseTracker t = new PhaseTracker();
        t.setPhase("custom-debug");
        assertThat(t.snapshot().buckets()).containsKey("custom-debug");
    }

    @Test
    void setPhase_blankRejected() {
        assertThatThrownBy(() -> new PhaseTracker().setPhase(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseTracker().setPhase(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------
    //  inferPhaseFromTool heuristic
    // -----------------------------------------------------------------

    @Test
    void inferPhaseFromTool_readOnlyMapsToExplore() {
        assertThat(PhaseTracker.inferPhaseFromTool("read_file")).isEqualTo(PhaseTracker.PHASE_EXPLORE);
        assertThat(PhaseTracker.inferPhaseFromTool("file_search")).isEqualTo(PhaseTracker.PHASE_EXPLORE);
        assertThat(PhaseTracker.inferPhaseFromTool("ls")).isEqualTo(PhaseTracker.PHASE_EXPLORE);
        assertThat(PhaseTracker.inferPhaseFromTool("grep")).isEqualTo(PhaseTracker.PHASE_EXPLORE);
    }

    @Test
    void inferPhaseFromTool_mutatingMapsToImplement() {
        assertThat(PhaseTracker.inferPhaseFromTool("write_file")).isEqualTo(PhaseTracker.PHASE_IMPLEMENT);
        assertThat(PhaseTracker.inferPhaseFromTool("file_edit")).isEqualTo(PhaseTracker.PHASE_IMPLEMENT);
        assertThat(PhaseTracker.inferPhaseFromTool("bash")).isEqualTo(PhaseTracker.PHASE_IMPLEMENT);
        assertThat(PhaseTracker.inferPhaseFromTool("delete_file")).isEqualTo(PhaseTracker.PHASE_IMPLEMENT);
    }

    @Test
    void inferPhaseFromTool_testMapsToVerify() {
        assertThat(PhaseTracker.inferPhaseFromTool("run_tests")).isEqualTo(PhaseTracker.PHASE_VERIFY);
        assertThat(PhaseTracker.inferPhaseFromTool("lint")).isEqualTo(PhaseTracker.PHASE_VERIFY);
        assertThat(PhaseTracker.inferPhaseFromTool("check_types")).isEqualTo(PhaseTracker.PHASE_VERIFY);
    }

    @Test
    void inferPhaseFromTool_nullDefaultsToExplore() {
        // A null tool name is rare (the engine always
        // passes a name) but the heuristic must not
        // NPE — explore is the safe default.
        assertThat(PhaseTracker.inferPhaseFromTool(null)).isEqualTo(PhaseTracker.PHASE_EXPLORE);
    }

    // -----------------------------------------------------------------
    //  Snapshot immutability
    // -----------------------------------------------------------------

    @Test
    void snapshot_isImmutable() {
        PhaseTracker t = new PhaseTracker();
        t.recordToolCall("x", 0.0);
        PhaseTracker.Snapshot s1 = t.snapshot();
        // Mutate the live tracker. The previously
        // returned snapshot must not change.
        t.recordToolCall("x", 0.0);
        assertThat(s1.buckets().get(PhaseTracker.DEFAULT_PHASE).toolCalls).isEqualTo(1);
        // The outer Map<String, Bucket> is unmodifiable
        // (the Snapshot's compact constructor wraps it
        // in Map.copyOf). Mutating it must throw.
        assertThatThrownBy(() -> s1.buckets().put("oops", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
