package org.aethercode.hooks.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R142 tests: the research-mode auto-recovery budget
 * prevents the "loop of loops" failure mode where the
 * model keeps falling into research-mode after each
 * recovery. The FIRST research_mode hard-stop gets an
 * auto-recovery; the SECOND one (in the same task)
 * falls back to user ack.
 */
class TodoContinuationHookR142Test {

    private TodoContinuationHookTest.TestDispatcher dispatcher;
    private TodoContinuationHook hook;

    @BeforeEach
    void setUp() {
        dispatcher = new TodoContinuationHookTest.TestDispatcher();
        hook = new TodoContinuationHook(dispatcher);
        hook.setCountdownSecondsForTest(0);
        hook.setCooldownMsForTest(20);
    }

    @AfterEach
    void tearDown() {
        hook.shutdown();
    }

    private static Map<String, Object> todo(String status, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("content", content);
        return m;
    }

    @Test
    void firstResearchModeAllowsRecovery() throws Exception {
        // First research_mode in the session — budget
        // is 0, recovery is allowed.
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_research_mode", todos);
        TodoContinuationHookTest.TestDispatcher.DispatchCall call =
                dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
        // Let the runnable's finally block release
        // the inFlight flag before any subsequent
        // onSessionIdle (the poll returns when the
        // item hits the queue, but the inFlight
        // release is in the same runnable's finally).
        Thread.sleep(50);
        assertThat(hook.researchModeRecoveryCount("s1")).isEqualTo(1);
    }

    @Test
    void secondResearchModeExhaustsBudget() throws Exception {
        // After 1 recovery (default budget), the next
        // research_mode in the same task must fall
        // back to user ack. The "loop of loops"
        // failure mode is exactly this scenario.
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_research_mode", todos);
        TodoContinuationHookTest.TestDispatcher.DispatchCall first =
                dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        Thread.sleep(50);  // let inFlight release
        assertThat(hook.researchModeRecoveryCount("s1")).isEqualTo(1);

        // Drain any continuations to make the test
        // independent of timing.
        dispatcher.dispatches.clear();
        // Second research_mode hard-stop in the
        // same task — budget should be exhausted.
        hook.onSessionIdle("s1", "run-2", "loop_research_mode", todos);
        TodoContinuationHookTest.TestDispatcher.DispatchCall second =
                dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS);
        assertThat(second).isNull();
        // Budget unchanged (no recovery was dispatched).
        assertThat(hook.researchModeRecoveryCount("s1")).isEqualTo(1);
    }

    @Test
    void successfulEndTurnResetsBudget() throws Exception {
        // After a successful end_turn (model finished
        // a task), the research_mode budget resets so
        // a new task in the same session gets its
        // own recovery. This is the "per-task" reset
        // semantics — the user explicitly chose to
        // ack / move on, so a fresh task should start
        // clean.
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_research_mode", todos);
        TodoContinuationHookTest.TestDispatcher.DispatchCall first =
                dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        Thread.sleep(50);  // let inFlight release
        assertThat(hook.researchModeRecoveryCount("s1")).isEqualTo(1);

        // Successful end_turn — budget should reset.
        hook.onSessionIdle("s1", "run-2", "end_turn",
                List.of(todo("completed", "step 2")));
        assertThat(hook.researchModeRecoveryCount("s1")).isEqualTo(0);

        // Now a new research_mode hard-stop is
        // allowed again (it's a new task).
        dispatcher.dispatches.clear();
        hook.onSessionIdle("s1", "run-3", "loop_research_mode", todos);
        TodoContinuationHookTest.TestDispatcher.DispatchCall third =
                dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(third).isNotNull();
        assertThat(hook.researchModeRecoveryCount("s1")).isEqualTo(1);
    }

    @Test
    void nonResearchModeLoopStillSkipsAfterBudgetExhausted() throws Exception {
        // The R142 budget is research-mode-specific.
        // other loop kinds (same_fingerprint /
        // no_file_write_progress) keep the existing
        // safe-default skip regardless of the budget.
        // Use up the research_mode budget first.
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_research_mode", todos);
        TodoContinuationHookTest.TestDispatcher.DispatchCall first =
                dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        Thread.sleep(50);  // let inFlight release
        dispatcher.dispatches.clear();
        // Now a same_fingerprint loop — should skip
        // (R141 behavior, not R142 budget).
        hook.onSessionIdle("s1", "run-2", "loop_same_fingerprint", todos);
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void budgetExhaustionLogMessage() throws Exception {
        // When the budget is exhausted, the hook
        // should LOG the exhaustion so the user
        // can see in the daemon log that the
        // fallback is intentional (and the user
        // is about to see the [todo-ask-llm]
        // prompt).
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_research_mode", todos);
        TodoContinuationHookTest.TestDispatcher.DispatchCall first =
                dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        Thread.sleep(50);  // let inFlight release
        dispatcher.dispatches.clear();
        // The second research_mode logs the
        // exhaustion. We don't assert on the log
        // content directly (that's a logging test)
        // — we just verify the dispatch is
        // suppressed and the budget is unchanged.
        hook.onSessionIdle("s1", "run-2", "loop_research_mode", todos);
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(hook.researchModeRecoveryCount("s1")).isEqualTo(1);
    }
}
