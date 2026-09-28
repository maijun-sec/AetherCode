package org.aethercode.tools.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for R372 — per-agent cost controls (token budget,
 * circuit breaker) and the dashboard metric surface. The
 * subagent job lifecycle is exercised end-to-end: register
 * → addTokens → markCompleted / markFailed, then assert
 * on the registry's metrics snapshot.
 *
 * <p>The tests share the default registry instance via the
 * {@link #freshRegistry()} helper so each case starts with
 * a clean breaker + concurrency state. The watchdog is
 * configured to a very long timeout (24 h) so the
 * background timer can't misfire during a fast unit test.
 */
class SubagentRegistryR372Test {

    private static SubagentRegistry freshRegistry() {
        // 24h timeout so the per-job watchdog timer
        // never trips inside a fast unit test.
        SubagentRegistry r = new SubagentRegistry();
        r.setWatchdogTimeoutMs(24L * 60 * 60 * 1000);
        // R374.2: bump the default-quota roles this
        // test class uses so back-to-back register()
        // calls don't trip the quota=1 default. The
        // breaker logic + the dashboard metrics test
        // depend on registering several jobs of the
        // same role without intervening terminal
        // transitions. Limiter is fresh on each
        // freshRegistry() call so this doesn't leak
        // between tests.
        r.setQuota("pm", 100);
        r.setQuota("coder", 100);
        r.setQuota("general-purpose", 100);
        return r;
    }

    /** helper: spawn → mark → next terminal state. Skips
     *  the streaming partial path because the unit test
     *  doesn't need to exercise it. */
    private static String spawnAndFail(SubagentRegistry r, String agentName,
                                       String prompt, String error) {
        String id = r.register("task-1", prompt, agentName);
        r.markFailed(id, error);
        return id;
    }

    // ---- R372.1 token budget --------------------------------------

    @Test
    void tokenBudgetExceeding_maxTokensMarksJobFailedAndFiresWatchdog() {
        SubagentRegistry r = freshRegistry();
        String id = r.register("task-budget", "do work", "pm", 100L);
        // 80 tokens is fine
        r.addTokens(id, 80);
        assertEquals(80, r.listRunning().get(0).tokensUsed);
        assertEquals(1, r.listRunning().size());
        // 30 more → 110, exceeds the 100 budget.
        r.addTokens(id, 30);
        // markFailed was called by the budget guard.
        assertEquals(0, r.listRunning().size());
        assertNotNull(r.listFinished().get(0).error);
        assertTrue(r.listFinished().get(0).error.contains("token budget exceeded"));
    }

    @Test
    void tokenBudgetZeroIsUnlimited() {
        SubagentRegistry r = freshRegistry();
        String id = r.register("task", "do work", "pm", 0L);
        r.addTokens(id, 1_000_000L);
        // still running — no budget was set
        assertEquals(1, r.listRunning().size());
        assertEquals(1_000_000L, r.listRunning().get(0).tokensUsed);
    }

    @Test
    void tokenBudget_deltaOfZeroIsNoOp() {
        SubagentRegistry r = freshRegistry();
        String id = r.register("task", "do work", "pm", 100L);
        r.addTokens(id, 0);
        assertEquals(0, r.listRunning().get(0).tokensUsed);
        // still running
        assertEquals(1, r.listRunning().size());
    }

    // ---- R372.2 circuit breaker ------------------------------------

    @Test
    void circuitBreaker_tripsAfterThresholdConsecutiveFailures() {
        SubagentRegistry r = freshRegistry();
        // Default threshold = 3. Three failures trip it.
        spawnAndFail(r, "pm", "first", "boom-1");
        spawnAndFail(r, "pm", "second", "boom-2");
        spawnAndFail(r, "pm", "third", "boom-3");
        // Now the breaker should be open.
        assertTrue(r.circuitBreaker().isOpen("pm"),
                "breaker should be open after 3 consecutive failures");
    }

    @Test
    void circuitBreaker_successResetsStreak() {
        SubagentRegistry r = freshRegistry();
        // 2 failures (one short of the trip threshold)
        spawnAndFail(r, "pm", "first", "boom");
        spawnAndFail(r, "pm", "second", "boom");
        assertFalse(r.circuitBreaker().isOpen("pm"));
        // success
        String id = r.register("task", "third", "pm");
        r.markCompleted(id, "ok");
        assertFalse(r.circuitBreaker().isOpen("pm"));
        // next failure starts a fresh streak — does NOT trip
        spawnAndFail(r, "pm", "fourth", "boom");
        assertFalse(r.circuitBreaker().isOpen("pm"));
    }

    @Test
    void circuitBreaker_isolatesByAgentName() {
        SubagentRegistry r = freshRegistry();
        // Trip "pm" with 3 consecutive failures.
        spawnAndFail(r, "pm", "x", "boom");
        spawnAndFail(r, "pm", "x", "boom");
        spawnAndFail(r, "pm", "x", "boom");
        assertTrue(r.circuitBreaker().isOpen("pm"));
        // "coder" is a different name; its slot is fresh.
        assertFalse(r.circuitBreaker().isOpen("coder"));
    }

    @Test
    void circuitBreakerCancellationIsNotAFailure() {
        SubagentRegistry r = freshRegistry();
        // cancellations don't trip the breaker
        for (int i = 0; i < 10; i++) {
            String id = r.register("task", "x", "pm");
            r.markCancelled(id, "user-cancelled");
        }
        assertFalse(r.circuitBreaker().isOpen("pm"));
    }

    @Test
    void circuitBreaker_openDurationExpiresAllowingProbe() {
        // custom breaker with a 50ms open duration so the
        // test runs in ~100ms instead of waiting 60s.
        SubagentCircuitBreaker br = new SubagentCircuitBreaker(2, Duration.ofMillis(50));
        br.recordFailure("pm");
        br.recordFailure("pm");
        assertTrue(br.isOpen("pm"));
        // wait past the cooldown
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        // probe call should now succeed (breaker is half-open)
        assertFalse(br.isOpen("pm"));
    }

    // ---- R372.3 concurrency limiter (unit) ------------------------

    @Test
    void concurrencyLimiter_acquireAndReleaseRoundTrip() {
        SubagentConcurrencyLimiter l = new SubagentConcurrencyLimiter();
        l.setQuota("pm", 2);
        var a1 = l.tryAcquire("pm", "job-1");
        var a2 = l.tryAcquire("pm", "job-2");
        var a3 = l.tryAcquire("pm", "job-3");  // should be denied
        assertTrue(a1.acquired());
        assertTrue(a2.acquired());
        assertFalse(a3.acquired());
        assertEquals(2, a3.quota());
        // release one
        l.release("pm", a1.tokenId(), "job-1");
        var a4 = l.tryAcquire("pm", "job-4");
        assertTrue(a4.acquired());
    }

    @Test
    void concurrencyLimiter_releaseUnknownTokenIsNoOp() {
        SubagentConcurrencyLimiter l = new SubagentConcurrencyLimiter();
        l.setQuota("pm", 1);
        var a = l.tryAcquire("pm", "job");
        assertTrue(a.acquired());
        // release a fake token — must not crash and must
        // not change the in-flight count.
        l.release("pm", "conc-deadbeef", "ghost");
        assertEquals(1, l.snapshot("pm").inFlight());
        // the real release works
        l.release("pm", a.tokenId(), "job");
        assertEquals(0, l.snapshot("pm").inFlight());
    }

    // ---- R372.4 dashboard metrics ---------------------------------

    @Test
    void dashboardMetrics_aggregatesPerAgent() {
        SubagentRegistry r = freshRegistry();
        // pm: 1 running, 1 completed, 1 failed
        String pmRunning = r.register("task", "a", "pm");
        r.addTokens(pmRunning, 50);
        String pmDone = r.register("task", "b", "pm");
        r.addTokens(pmDone, 75);
        r.markCompleted(pmDone, "ok");
        spawnAndFail(r, "pm", "c", "boom");
        // coder: 1 completed
        String cDone = r.register("task", "d", "coder");
        r.addTokens(cDone, 30);
        r.markCompleted(cDone, "ok");

        Map<String, SubagentRegistry.AgentMetric> metrics = r.dashboardMetrics();
        assertTrue(metrics.containsKey("pm"));
        assertTrue(metrics.containsKey("coder"));

        SubagentRegistry.AgentMetric pm = metrics.get("pm");
        assertEquals(1, pm.running);
        assertEquals(1, pm.completed);
        assertEquals(1, pm.failed);
        // tokensTotal includes the running job's 50, the
        // completed job's 75, and the failed job's 0.
        assertEquals(125L, pm.tokensTotal);

        SubagentRegistry.AgentMetric coder = metrics.get("coder");
        assertEquals(0, coder.running);
        assertEquals(1, coder.completed);
        assertEquals(0, coder.failed);
        assertEquals(30L, coder.tokensTotal);

        // breaker state for "pm": 1 consecutive failure
        // (the 2 successes earlier were cleared by recordSuccess)
        assertEquals("CLOSED", pm.circuitState);
        assertEquals(1, pm.consecutiveFailures);
    }

    @Test
    void dashboardMetrics_unknownAgentReturnsDefaultSnapshot() {
        SubagentRegistry r = freshRegistry();
        Map<String, SubagentRegistry.AgentMetric> metrics = r.dashboardMetrics();
        // No spawns yet — metrics map is empty.
        assertTrue(metrics.isEmpty());
    }
}