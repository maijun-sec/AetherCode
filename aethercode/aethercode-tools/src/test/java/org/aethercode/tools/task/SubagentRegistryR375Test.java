package org.aethercode.tools.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R375.1: tests for {@code SubagentRegistry.resetAllCircuits()} —
 * the fleet-wide recovery path the desktop dashboard wires
 * into its header.
 *
 * <p>Scope:
 * <ul>
 *   <li>empty fleet: resetAll returns 0 (no slots to
 *       clear); subsequent register() still works.</li>
 *   <li>multiple tripped breakers: resetAll returns the
 *       count of cleared slots and isOpen() returns
 *       false for every role afterwards.</li>
 *   <li>mix of tripped + CLOSED + half_open: the count is
 *       the total slot count (the breaker holds a slot
 *       for every role that ever failed, regardless of
 *       current state).</li>
 *   <li>after reset: new register() calls succeed without
 *       "circuit open" rejection.</li>
 * </ul>
 */
class SubagentRegistryR375Test {

    private SubagentRegistry reg;

    @BeforeEach
    void fresh() {
        // The registry is a process singleton so each
        // test starts from a clean slate. The quota
        // limiter needs reset too because R374 wired
        // it into register().
        SubagentRegistry.instance().resetConcurrencyLimiter();
        SubagentRegistry.instance().circuitBreaker().reset();
        // Wipe any RUNNING jobs left over by a previous
        // test. Without this, the tokensBudget metric
        // (R375.4) can pick up a leftover job's budget
        // and report the wrong value. markCompleted on
        // every running job moves them to `finished`
        // so `running` ends empty AND we don't trip
        // the breaker for the next test. The breaker
        // reset above already wiped slots from any
        // PRIOR test's failures.
        for (String id : SubagentRegistry.instance().listRunning()
                .stream().map(j -> j.jobId).toList()) {
            SubagentRegistry.instance().markCompleted(id, "test-cleanup");
        }
        reg = SubagentRegistry.instance();
        // Bump test quotas so the back-to-back register()
        // calls below don't hit the default cap.
        reg.setQuota("pm", 100);
        reg.setQuota("coder", 100);
        reg.setQuota("tester", 100);
    }

    @Test
    void resetAllCircuits_emptyReturnsZero() {
        // Fresh state — no roles have tripped the
        // breaker yet. resetAll should report 0 cleared.
        assertEquals(0, reg.resetAllCircuits(),
                "empty fleet must report 0 cleared");
    }

    @Test
    void resetAllCircuits_threeRolesTripped_returnsThree() {
        // Trip the breaker for pm, coder, tester (3
        // failures each). The breaker holds a slot per
        // role. After resetAll, all slots should be
        // cleared.
        tripThree("pm");
        tripThree("coder");
        tripThree("tester");

        assertTrue(reg.circuitBreaker().isOpen("pm"));
        assertTrue(reg.circuitBreaker().isOpen("coder"));
        assertTrue(reg.circuitBreaker().isOpen("tester"));

        int cleared = reg.resetAllCircuits();
        assertEquals(3, cleared,
                "fleet with 3 tripped breakers must report cleared=3");

        assertFalse(reg.circuitBreaker().isOpen("pm"),
                "pm should be CLOSED after resetAll");
        assertFalse(reg.circuitBreaker().isOpen("coder"),
                "coder should be CLOSED after resetAll");
        assertFalse(reg.circuitBreaker().isOpen("tester"),
                "tester should be CLOSED after resetAll");
    }

    @Test
    void resetAllCircuits_oneTrippedAndOneHealthy_returnsOne() {
        // pm gets tripped; coder never fails. resetAll
        // should report 1 (only pm has a slot to clear).
        // The slot count = total roles that ever tripped,
        // not the number currently OPEN.
        tripThree("pm");

        int cleared = reg.resetAllCircuits();
        assertEquals(1, cleared,
                "fleet with 1 tripped breaker (coder never tripped) must report cleared=1");
    }

    @Test
    void resetAllCircuits_afterReset_newRegisterSucceeds() {
        // Trip pm, resetAll, then register a new pm job —
        // the breaker must NOT reject the new job. This
        // is the end-to-end "fleet recovery" scenario:
        // user clicked the button, system is healthy
        // again, new work can land.
        tripThree("pm");
        reg.resetAllCircuits();

        // The breaker should not be open anymore. Try
        // a fresh register — no IllegalStateException.
        String jobId = reg.register("task-r375-after-reset", "p", "pm");
        assertEquals("pm", reg.get(jobId).role,
                "fresh register must succeed and report role pm");
    }

    // ----- R375.4: token-budget context on AgentMetric -----

    @Test
    void tokensBudget_zeroWhenNoRunningJobHasBudget() {
        // Register a job WITHOUT a budget (maxTokens=0) and
        // a finished job WITH a budget. The bar should
        // hide because no RUNNING job has a budget.
        String j = reg.register("task-r375-budget-none", "p", "pm");
        reg.markCompleted(j, "ok");
        String jBudgeted = reg.register("task-r375-budget", "p", "pm", "", 1000L);
        reg.markCompleted(jBudgeted, "ok");

        java.util.Map<String, org.aethercode.tools.task.SubagentRegistry.AgentMetric> m
                = reg.dashboardMetrics();
        org.aethercode.tools.task.SubagentRegistry.AgentMetric pm = m.get("pm");
        assertNotNull(pm);
        assertEquals(0L, pm.tokensBudget,
                "no running job with a budget → tokensBudget = 0");
        assertEquals(0L, pm.tokensBudgetUsed);
    }

    @Test
    void tokensBudget_reportsRunningJobBudgetAndUsed() {
        // Register a running job with a budget, then
        // bump its tokensUsed. The metric must report
        // both fields.
        reg.setQuota("pm", 5);
        String j = reg.register("task-r375-budget", "p", "pm", "", 1000L);
        reg.addTokens(j, 250L);  // bump mid-run

        org.aethercode.tools.task.SubagentRegistry.AgentMetric pm =
                reg.dashboardMetrics().get("pm");
        assertNotNull(pm);
        assertEquals(1000L, pm.tokensBudget,
                "running job with budget → tokensBudget = maxTokens");
        assertEquals(250L, pm.tokensBudgetUsed,
                "tokensBudgetUsed mirrors the running job's tokensUsed");
    }

    @Test
    void tokensBudget_picksMostAdvancedRunningJob() {
        // Two running jobs with different budgets. The
        // metric should surface the one closest to its
        // cap (highest ratio).
        reg.setQuota("pm", 5);
        String jSmall = reg.register("task-r375-small", "p", "pm", "", 100L);
        reg.addTokens(jSmall, 90L);  // 90/100 = 90%
        String jLarge = reg.register("task-r375-large", "p", "pm", "", 10000L);
        reg.addTokens(jLarge, 1000L);  // 1000/10000 = 10%

        org.aethercode.tools.task.SubagentRegistry.AgentMetric pm =
                reg.dashboardMetrics().get("pm");
        assertNotNull(pm);
        assertEquals(100L, pm.tokensBudget,
                "the highest-ratio job wins (90% > 10%)");
        assertEquals(90L, pm.tokensBudgetUsed,
                "tokensBudgetUsed follows the chosen job");
    }

    @Test
    void tokensBudget_tieBreakerPicksSmallestBudget() {
        // Two running jobs with the same ratio. The
        // smaller budget wins — it's the one the user
        // is most likely to be watching.
        reg.setQuota("pm", 5);
        String jA = reg.register("task-r375-a", "p", "pm", "", 1000L);
        reg.addTokens(jA, 500L);  // 50%
        String jB = reg.register("task-r375-b", "p", "pm", "", 200L);
        reg.addTokens(jB, 100L);  // 50%

        org.aethercode.tools.task.SubagentRegistry.AgentMetric pm =
                reg.dashboardMetrics().get("pm");
        assertNotNull(pm);
        assertEquals(200L, pm.tokensBudget,
                "tie-breaker: smallest budget wins");
        assertEquals(100L, pm.tokensBudgetUsed);
    }

    // ----- helpers -----

    /** Trip the breaker for one role by failing 3 jobs in a row. */
    private void tripThree(String role) {
        for (int i = 0; i < 3; i++) {
            String id = reg.register("task-r375-trip", "p", role);
            reg.markFailed(id, "boom");
        }
    }
}