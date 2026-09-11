package org.aethercode.hooks.builtin;

import org.aethercode.hooks.Hook;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the {@link PhaseBudgetHook}.
 *
 * <p>The hook is a thin policy layer on top of
 * {@link PhaseTracker} — these tests focus on the
 * policy:
 * <ul>
 *   <li>Continue when the current phase is under budget.</li>
 *   <li>Block when the current phase is over budget
 *       (with an actionable message).</li>
 *   <li>The block message includes the spent / cap
 *       numbers and the next-step hint.</li>
 *   <li>Hook subscribes ONLY to PRE_TOOL_USE (so
 *       the registry doesn't fire it on other kinds).</li>
 * </ul>
 */
class PhaseBudgetHookTest {

    @Test
    void hook_kindIsPreToolUse() {
        PhaseTracker t = new PhaseTracker();
        PhaseBudgetHook h = new PhaseBudgetHook(t);
        assertThat(h.kind()).isEqualTo(Hook.Kind.PRE_TOOL_USE);
    }

    @Test
    void continue_whenUnderBudget() throws Exception {
        PhaseTracker t = new PhaseTracker();
        // Default plan cap is 2. One call is well under.
        t.recordToolCall("todo_write", 0.0);
        PhaseBudgetHook h = new PhaseBudgetHook(t);
        Hook.Outcome out = h.run(Hook.HookContext.forPre("s", "read_file", java.util.Map.of("path", "/a"))).get();
        assertThat(out).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void block_whenOverToolCallCap() throws Exception {
        PhaseTracker t = new PhaseTracker();
        // Plan cap is 2. Two calls = at cap.
        t.recordToolCall("todo_write", 0.0);
        t.recordToolCall("todo_write", 0.0);
        PhaseBudgetHook h = new PhaseBudgetHook(t);
        Hook.Outcome out = h.run(Hook.HookContext.forPre("s", "read_file", java.util.Map.of("path", "/a"))).get();
        assertThat(out).isInstanceOf(Hook.Outcome.Block.class);
        String reason = ((Hook.Outcome.Block) out).reason();
        // The block reason names the phase, the spent
        // count, the cap, and the next-step hint.
        assertThat(reason).contains("plan");
        assertThat(reason).contains("toolCalls=2/2");
        assertThat(reason).contains("/phase verify");
        assertThat(reason).contains("/budget plan");
    }

    @Test
    void block_whenOverCostCap() throws Exception {
        // Tight cost cap so a single $0.06 call blows it.
        java.util.Map<String, PhaseTracker.Bucket> b = new java.util.LinkedHashMap<>();
        b.put(PhaseTracker.PHASE_PLAN, new PhaseTracker.Bucket(100, 0.05));
        PhaseTracker t = new PhaseTracker(b);
        t.recordToolCall("todo_write", 0.06);
        PhaseBudgetHook h = new PhaseBudgetHook(t);
        Hook.Outcome out = h.run(Hook.HookContext.forPre("s", "read_file", java.util.Map.of("path", "/a"))).get();
        assertThat(out).isInstanceOf(Hook.Outcome.Block.class);
        String reason = ((Hook.Outcome.Block) out).reason();
        assertThat(reason).contains("$0.06");
        assertThat(reason).contains("$0.05");
    }

    @Test
    void block_carriesActionableMessage() {
        // A user reading the block reason should know
        // exactly what to do: either transition to
        // the next phase, or raise the cap. The
        // message surfaces both options.
        PhaseTracker t = new PhaseTracker();
        for (int i = 0; i < 5; i++) t.recordToolCall("x", 0.0);  // over plan cap
        PhaseBudgetHook h = new PhaseBudgetHook(t);
        try {
            Hook.Outcome out = h.run(Hook.HookContext.forPre("s", "x", java.util.Map.of())).get();
            String reason = ((Hook.Outcome.Block) out).reason();
            assertThat(reason).contains("transition to next phase");
            assertThat(reason).contains("raise the cap");
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void hook_continuesAfterPhaseTransition() throws Exception {
        PhaseTracker t = new PhaseTracker();
        t.recordToolCall("x", 0.0);
        t.recordToolCall("x", 0.0);  // over plan cap
        PhaseBudgetHook h = new PhaseBudgetHook(t);
        // After transition, the new phase is fresh.
        t.setPhase(PhaseTracker.PHASE_EXPLORE);
        Hook.Outcome out = h.run(Hook.HookContext.forPre("s", "read_file", java.util.Map.of("path", "/a"))).get();
        assertThat(out).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void constructor_nullTrackerRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PhaseBudgetHook(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tracker");
    }
}
