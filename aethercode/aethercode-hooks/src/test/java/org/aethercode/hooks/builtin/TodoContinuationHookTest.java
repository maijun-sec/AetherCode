package org.aethercode.hooks.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * unit tests for {@link TodoContinuationHook}.
 *
 * <p>The hook has timing-dependent behavior (countdown,
 * cooldown, exponential backoff on failures), so tests use
 * shortened countdown + cooldown via the test-only setters
 * ({@link TodoContinuationHook#setCountdownSecondsForTest}
 * and {@link TodoContinuationHook#setCooldownMsForTest}).
 * We drive the dispatcher with a stub
 * {@link ContinuationDispatcher} that records every call.
 */
class TodoContinuationHookTest {

    private TestDispatcher dispatcher;
    private TodoContinuationHook hook;

    @BeforeEach
    void setUp() {
        dispatcher = new TestDispatcher();
        hook = new TodoContinuationHook(dispatcher);
        // Short countdown + cooldown so tests don't wait the
        // production 2s / 30s. The countdown is at least 1s
        // granularity for ScheduledExecutorService, so we set
        // 1ms via a separate test-only fast path: the tests
        // use the in-test "in-flight" CAS window as the
        // effective countdown. We override the countdown to
        // a tiny value AND a per-test sleep.
        hook.setCountdownSecondsForTest(0);   // 0s -> immediately
        hook.setCooldownMsForTest(20);         // 20ms cooldown
    }

    @AfterEach
    void tearDown() {
        hook.shutdown();
    }

    @Test
    void emptyTodoListSkipsDispatch() throws Exception {
        hook.onSessionIdle("s1", "run-1", "end_turn", List.of());
        // No countdown scheduled → no dispatch within 0.3s
        assertThat(dispatcher.dispatches.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void allCompletedTodosSkipsDispatch() throws Exception {
        List<Map<String, Object>> todos = List.of(
                todo("completed", "step 1"),
                todo("completed", "step 2")
        );
        hook.onSessionIdle("s1", "run-1", "end_turn", todos);
        assertThat(dispatcher.dispatches.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void incompleteTodosTriggerDispatchAfterCountdown() throws Exception {
        List<Map<String, Object>> todos = List.of(
                todo("completed", "step 1"),
                todo("in_progress", "step 2")
        );
        hook.onSessionIdle("s1", "run-1", "end_turn", todos);
        TestDispatcher.DispatchCall call = dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
        assertThat(call.sessionId).isEqualTo("s1");
        assertThat(call.prompt).contains("todo-continuation");
        assertThat(call.prompt).contains("step 2");
    }

    @Test
    void userStoppedFlagSuppressesDispatch() throws Exception {
        dispatcher.stopped.put("s1", true);
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "end_turn", todos);
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void errorStopReasonSkips() throws Exception {
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "error", todos);
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void loopDetectedStopReasonSkips() throws Exception {
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_detected", todos);
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void loopResearchModeAllowsRecoveryDispatch() throws Exception {
        // the research-mode hard-stop is a
        // recoverable failure pattern — the model was
        // doing 3+ small-output bash (mvn -version,
        // etc.) without writing files. The hook
        // MUST allow a continuation with the recovery
        // prompt instead of forcing the user to ack.
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_research_mode", todos);
        TestDispatcher.DispatchCall call = dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
        assertThat(call.prompt).contains("research-mode-recovery");
        assertThat(call.prompt).contains("step 2");
    }

    @Test
    void loopSameFingerprintSkips() throws Exception {
        // same-fingerprint (model repeats the
        // exact same tool call) is NOT recoverable
        // — the model is genuinely stuck on a
        // specific command, and a continuation
        // would just re-fire the same pattern. Keep
        // the safe-default skip.
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_same_fingerprint", todos);
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void loopNoFileWriteProgressRecovers() throws Exception {
        // no_file_write_progress IS
        // recoverable when the model had been
        // making progress (60+ file_writes)
        // and then stalled. The recovery
        // uses a softer prompt than the
        // research-mode one (acknowledges
        // prior progress). The budget is 1
        // per task (parallel to
        // prior round's research-mode cap).
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        hook.onSessionIdle("s1", "run-1", "loop_no_file_write_progress", todos);
        // Wait for the 2s countdown + dispatch
        var dispatched = dispatcher.dispatches.poll(4, TimeUnit.SECONDS);
        assertThat(dispatched).isNotNull();
        // The recovery prompt is the
        // R151a softer variant.
        assertThat(dispatched.prompt).contains("PROGRESS-STALL RECOVERY");
        assertThat(dispatched.prompt).contains("60+ file_writes");
    }

    @Test
    void loopNoFileWriteProgressBudgetExhausted() throws Exception {
        // after the first
        // progress-stall recovery fires,
        // the second one in the same task
        // falls back to user ack.
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        // 1st stall — allowed
        hook.onSessionIdle("s1", "run-1", "loop_no_file_write_progress", todos);
        assertThat(dispatcher.dispatches.poll(4, TimeUnit.SECONDS)).isNotNull();
        // 2nd stall — budget exhausted, skip
        hook.onSessionIdle("s1", "run-2", "loop_no_file_write_progress", todos);
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void loopNoFileWriteProgressBudgetResetsOnEndTurn() throws Exception {
        // the budget resets on
        // end_turn (parallel to R142's
        // research-mode reset). A fresh
        // task gets a fresh budget.
        // The end_turn with a non-empty
        // todo list (the test scenario:
        // "I finished one task, the rest
        // are still pending") triggers
        // the budget reset.
        List<Map<String, Object>> todos1 = List.of(todo("completed", "done"), todo("pending", "step 2"));
        List<Map<String, Object>> todos2 = List.of(todo("pending", "step 2"));
        // 1st stall — allowed
        hook.onSessionIdle("s1", "run-1", "loop_no_file_write_progress", todos2);
        assertThat(dispatcher.dispatches.poll(4, TimeUnit.SECONDS)).isNotNull();
        // 2nd stall same task — budget exhausted
        hook.onSessionIdle("s1", "run-2", "loop_no_file_write_progress", todos2);
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
        // Reset on end_turn (todoList non-empty
        // so we don't hit the early return).
        hook.onSessionIdle("s1", "run-3", "end_turn", todos1);
        // 3rd stall — budget reset, allowed
        hook.onSessionIdle("s1", "run-4", "loop_no_file_write_progress", todos2);
        assertThat(dispatcher.dispatches.poll(4, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void maxFailuresStopsContinuation() throws Exception {
        // Force 5 consecutive failures by making the dispatcher throw.
        // We need to sleep between calls so the in-flight CAS guard
        // releases (the scheduler releases inFlight in the runnable's
        // finally block, after the dispatch returns).
        dispatcher.shouldThrow = true;
        List<Map<String, Object>> todos = List.of(todo("pending", "step 2"));
        for (int i = 0; i < 5; i++) {
            hook.onSessionIdle("s1", "run-" + i, "end_turn", todos);
            // Generous wait so the scheduler thread can complete
            // the runnable (dispatch throws, catch increments,
            // finally releases inFlight). On a busy CI runner
            // 300ms is the empirical safe margin.
            Thread.sleep(350);
        }
        assertThat(hook.consecutiveFailures("s1")).isGreaterThanOrEqualTo(5);
        // Now the hook should refuse; flip the dispatcher back to
        // success mode and verify no more dispatches happen.
        dispatcher.shouldThrow = false;
        dispatcher.dispatches.clear();
        hook.onSessionIdle("s1", "run-final", "end_turn", todos);
        Thread.sleep(200);
        assertThat(dispatcher.dispatches).isEmpty();
    }

    @Test
    void notifyCountdownFiresBeforeDispatch() throws Exception {
        List<Map<String, Object>> todos = List.of(
                todo("pending", "step 1"),
                todo("pending", "step 2")
        );
        hook.onSessionIdle("s1", "run-1", "end_turn", todos);
        // countdown notification fires synchronously in onSessionIdle
        assertThat(dispatcher.countdowns).hasSize(1);
        TestDispatcher.CountdownCall cd = dispatcher.countdowns.get(0);
        assertThat(cd.incompleteCount).isEqualTo(2);
        assertThat(cd.totalCount).isEqualTo(2);
        // dispatch fires after countdown
        TestDispatcher.DispatchCall call = dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
    }

    @Test
    void multipleIdleEventsCooldown() throws Exception {
        // First idle: schedule + dispatch
        hook.onSessionIdle("s1", "run-1", "end_turn",
                List.of(todo("pending", "step 1")));
        assertThat(dispatcher.dispatches.poll(1, TimeUnit.SECONDS)).isNotNull();

        // Second idle immediately: cooldown blocks (80ms < 30s default)
        hook.onSessionIdle("s1", "run-2", "end_turn",
                List.of(todo("pending", "step 1")));
        assertThat(dispatcher.dispatches.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void cancelledStatusSkipped() throws Exception {
        List<Map<String, Object>> todos = List.of(
                todo("cancelled", "step 1"),
                todo("pending", "step 2")
        );
        hook.onSessionIdle("s1", "run-1", "end_turn", todos);
        TestDispatcher.DispatchCall call = dispatcher.dispatches.poll(1, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
        // Only step 2 should appear in the prompt (cancelled is filtered)
        assertThat(call.prompt).contains("step 2");
        assertThat(call.prompt).doesNotContain("step 1");
    }

    @Test
    void buildContinuationPromptIncludesAllIncomplete() {
        List<Map<String, Object>> incomplete = List.of(
                todo("in_progress", "implement bubble sort"),
                todo("pending",     "write tests")
        );
        String p = TodoContinuationHook.buildContinuationPrompt(incomplete);
        assertThat(p).contains("todo-continuation");
        assertThat(p).contains("bubble sort");
        assertThat(p).contains("write tests");
        assertThat(p).contains("[in_progress]");
        assertThat(p).contains("[pending]");
    }

    private static Map<String, Object> todo(String status, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("status", status);
        return m;
    }

    /** Test dispatcher that records every call.
     *  Package-private so prior round tests in the same
     *  package can reuse it (the inner type was
     *  originally private, but that blocks the
     *  follow-up tests from constructing their own
     *  dispatchers without copy-paste). */
    static final class TestDispatcher implements ContinuationDispatcher {
        final LinkedBlockingQueue<DispatchCall> dispatches = new LinkedBlockingQueue<>();
        final List<CountdownCall> countdowns = new CopyOnWriteArrayList<>();
        final Map<String, Boolean> stopped = new ConcurrentHashMap<>();
        volatile boolean shouldThrow = false;

        public void dispatchContinuation(String sessionId, String runId, String prompt,
                                         List<Map<String, Object>> incompleteTodos) {
            if (shouldThrow) {
                throw new RuntimeException("test-dispatcher-throws");
            }
            dispatches.add(new DispatchCall(sessionId, runId, prompt, incompleteTodos));
        }
        public void notifyCountdown(String sessionId, String runId, int incompleteCount,
                                    int totalCount, long remainingMs) {
            countdowns.add(new CountdownCall(sessionId, runId, incompleteCount, totalCount, remainingMs));
        }
        public boolean isContinuationStopped(String sessionId) {
            return stopped.getOrDefault(sessionId, false);
        }
        public void setContinuationStopped(String sessionId, boolean stopped) {
            this.stopped.put(sessionId, stopped);
        }
        record DispatchCall(String sessionId, String runId, String prompt,
                            List<Map<String, Object>> incompleteTodos) {}
        record CountdownCall(String sessionId, String runId, int incompleteCount,
                             int totalCount, long remainingMs) {}
    }
}
