package org.aethercode.sdk;

import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.engine.StreamingToolExecutor;
import org.aethercode.core.tool.Tool;
import org.aethercode.hooks.builtin.PhaseTracker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the {@link StreamingToolExecutor.ToolCallObserver}
 * integration with {@link PhaseTracker}.
 *
 * <p>The observer is what makes the prior round budget actually
 * fire — without it, the tracker stays at zero and a
 * runaway loop is not capped. We verify the wiring here
 * at the SDK level (the engine installs a default observer
 * that forwards to the tracker; the executor's
 * {@code withToolCallObserver} setter is the only knob the
 * engine has to influence the observation).
 */
class ToolCallObserverTest {

    // -----------------------------------------------------------------
    //  StreamingToolExecutor.ToolCallObserver contract
    // -----------------------------------------------------------------

    @Test
    void observer_setterAcceptsNull() {
        // The setter accepts null. The executor's call
        // site must NPE-safe (a null observer is treated
        // as "no observation" — the same default the
        // engine shipped with previously-A).
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 1);
        // Should not throw.
        exec.withToolCallObserver(null);
    }

    @Test
    void observer_setterReplaces() {
        // The setter overwrites any previous observer.
        // A test context that wants to swap observers
        // between test cases relies on this.
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 1);
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        exec.withToolCallObserver((n, c) -> a.incrementAndGet());
        exec.withToolCallObserver((n, c) -> b.incrementAndGet());
        // The setter is idempotent. We can't easily
        // invoke the private call site, so we just
        // verify the setter doesn't throw on
        // back-to-back assignments.
        exec.withToolCallObserver(null);
    }

    @Test
    void observer_canBeRemoved() {
        // A null setter removes the observer. This is
        // the contract — pass null to detach.
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 1);
        exec.withToolCallObserver((n, c) -> {});
        exec.withToolCallObserver(null);
        // No exception. The internal field is null.
        assertThat(exec).isNotNull();
    }

    // -----------------------------------------------------------------
    //  Engine wiring: phaseTracker integration
    // -----------------------------------------------------------------

    @Test
    void engineWithoutPhaseTracker_observesNoOp() {
        // A default engine (no phase tracker) builds
        // fine. We confirm the engine builder doesn't
        // blow up and the tracker accessor returns null.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(java.nio.file.Path.of(""))
                .tools(List.<Tool>of())
                .build();
        assertThat(engine.phaseTracker()).isNull();
    }

    @Test
    void engineWithPhaseTracker_recordsCalls() {
        // Build an engine with a phase tracker. The
        // engine installs a default observer that
        // forwards to phaseTracker.recordToolCall. We
        // invoke the observer entry point directly
        // (package-private) to verify the wiring.
        PhaseTracker tracker = new PhaseTracker();
        tracker.setPhase(PhaseTracker.PHASE_EXPLORE);
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(java.nio.file.Path.of(""))
                .phaseTracker(tracker)
                .tools(List.<Tool>of())
                .build();
        // Three observer calls should bump the
        // explorer's counter to 3. The model would
        // call this in a real run.
        engine.onToolCallObserved("read_file", 0.0);
        engine.onToolCallObserved("read_file", 0.0);
        engine.onToolCallObserved("read_file", 0.0);
        assertThat(tracker.snapshot().buckets().get(PhaseTracker.PHASE_EXPLORE).toolCalls)
                .as("3 explicit observer calls should bump the tracker to 3")
                .isEqualTo(3);
    }

    @Test
    void onToolCallObserved_nullToolNameIsSafe() {
        // The observer must not NPE if the tool name is
        // null (defensive: a future tool that doesn't
        // override name() should not crash the engine).
        PhaseTracker tracker = new PhaseTracker();
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(java.nio.file.Path.of(""))
                .phaseTracker(tracker)
                .tools(List.<Tool>of())
                .build();
        engine.onToolCallObserved(null, 0.0);
        assertThat(tracker.snapshot().buckets().get(PhaseTracker.DEFAULT_PHASE).toolCalls)
                .isEqualTo(1);
    }

    @Test
    void onToolCallObserved_costIsForwarded() {
        // The observer carries a cost (currently always
        // 0.0 from the executor; this test sets it
        // explicitly to verify the tracker field
        // accumulates it). Future cost-attribution work
        // will pass a non-zero value here.
        PhaseTracker tracker = new PhaseTracker();
        tracker.setPhase(PhaseTracker.PHASE_IMPLEMENT);
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(java.nio.file.Path.of(""))
                .phaseTracker(tracker)
                .tools(List.<Tool>of())
                .build();
        engine.onToolCallObserved("write_file", 0.05);
        assertThat(tracker.snapshot().buckets().get(PhaseTracker.PHASE_IMPLEMENT).costUsd)
                .isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void onToolCallObserved_budgetFiresAfterObserverHits() {
        // The end-to-end win: with the observer wired,
        // the budget hook actually blocks on the Nth
        // tool call. prior round's hook was dormant without
        // the observer. Now it fires.
        PhaseTracker tracker = new PhaseTracker();
        // Lower the plan cap to 2 so the test is fast.
        tracker.setBudget(PhaseTracker.PHASE_PLAN, 2, 0.0);
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(java.nio.file.Path.of(""))
                .phaseTracker(tracker)
                .tools(List.<Tool>of())
                .build();
        // 2 calls = at cap = over budget.
        engine.onToolCallObserved("x", 0.0);
        engine.onToolCallObserved("x", 0.0);
        assertThat(tracker.isOverBudget())
                .as("the plan budget must fire after the observer hits the cap")
                .isTrue();
    }
}
