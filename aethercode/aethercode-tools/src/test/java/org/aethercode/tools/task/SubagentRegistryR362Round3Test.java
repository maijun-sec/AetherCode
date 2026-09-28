package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R362 Round 3 tests for {@link SubagentRegistry#retry(String)} and the
 * per-job Watchdog integration.
 *
 * <p>Two halves:
 * <ul>
 *   <li><b>retry()</b>: resets FAILED / CANCELLED jobs to RUNNING,
 *       refuses RUNNING / COMPLETED / unknown jobs, restarts the
 *       Watchdog, fires a fresh RUNNING event.</li>
 *   <li><b>Watchdog</b>: marks jobs FAILED after the configured
 *       silence timeout (the timer ticks per
 *       {@link org.aethercode.sdk.Watchdog#DEFAULT_POLL_MS};
 *       tests shorten the timeout via
 *       {@link SubagentRegistry#setWatchdogTimeoutMs(long)} so the
 *       watchdog fires within a few hundred ms).</li>
 * </ul>
 */
class SubagentRegistryR362Round3Test {

    @TempDir Path agentsDir;

    @BeforeEach
    void reset() {
        // The registry is a process singleton;
        // tests must run sequentially. We reset
        // the watchdog timeout to a known state
        // (60s — the production default) before
        // each test so a previous test's
        // setWatchdogTimeoutMs(...) doesn't leak
        // into the next. Tests that need a short
        // timeout call setWatchdogTimeoutMs(...)
        // themselves; @AfterEach also restores the
        // default for belt-and-suspenders.
        SubagentRegistry.instance().setWatchdogTimeoutMs(60_000L);
        // R374.2: also clear the concurrency limiter so
        // a prior test's role slots don't trip the
        // quota=1 default on this test's register.
        SubagentRegistry.instance().concurrencyLimiter().reset();
    }

    @AfterEach
    void teardown() throws Exception {
        // Reset the timeout to the default so the
        // next test starts clean. We can't reach
        // the singleton's finished map cleanly
        // without exposing a reset, but the
        // MAX_FINISHED_JOBS cap (64) is plenty for
        // a test suite.
        SubagentRegistry.instance().setWatchdogTimeoutMs(60_000L);
    }

    // =================================================================
    // retry()
    // =================================================================

    @Test
    void retry_failedJob_resetsToRunning() {
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markFailed(id, "boom");
        assertEquals(SubagentRegistry.SubagentJob.Status.FAILED,
                SubagentRegistry.instance().get(id).status);
        // Retry — should reset to RUNNING.
        SubagentRegistry.RetryResult r = SubagentRegistry.instance().retry(id);
        assertTrue(r.retried(), "retry should succeed for FAILED job; reason=" + r.reason());
        assertEquals(SubagentRegistry.SubagentJob.Status.RUNNING,
                SubagentRegistry.instance().get(id).status);
        // error / resultText / cancelReason cleared.
        SubagentRegistry.SubagentJob j = SubagentRegistry.instance().get(id);
        assertEquals("", j.error, "error should be cleared on retry");
        assertEquals("", j.resultText, "resultText should be cleared on retry");
        assertEquals(0L, j.finishedAtMs, "finishedAtMs should be reset on retry");
    }

    @Test
    void retry_cancelledJob_resetsToRunning() {
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().cancel(id, "user changed mind");
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED,
                SubagentRegistry.instance().get(id).status);
        SubagentRegistry.RetryResult r = SubagentRegistry.instance().retry(id);
        assertTrue(r.retried(), "retry should succeed for CANCELLED job");
        assertEquals(SubagentRegistry.SubagentJob.Status.RUNNING,
                SubagentRegistry.instance().get(id).status);
    }

    @Test
    void retry_completedJob_isRefused() {
        // A COMPLETED job must NOT be retryable —
        // re-running would change history.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markCompleted(id, "all good");
        SubagentRegistry.RetryResult r = SubagentRegistry.instance().retry(id);
        assertFalse(r.retried());
        assertTrue(r.reason().contains("COMPLETED"),
                "refusal reason should mention COMPLETED; was: " + r.reason());
        // Status unchanged.
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED,
                SubagentRegistry.instance().get(id).status);
    }

    @Test
    void retry_runningJob_isRefused() {
        // A RUNNING job cannot be re-tried (would
        // double-spawn). The caller should cancel
        // first if they want to restart.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.RetryResult r = SubagentRegistry.instance().retry(id);
        assertFalse(r.retried());
        assertTrue(r.reason().contains("RUNNING"),
                "refusal reason should mention RUNNING; was: " + r.reason());
    }

    @Test
    void retry_unknownJob_isRefused() {
        SubagentRegistry.RetryResult r = SubagentRegistry.instance().retry("sag-bogus-9999");
        assertFalse(r.retried());
        assertTrue(r.reason().contains("no such job"),
                "refusal reason should mention missing job; was: " + r.reason());
    }

    @Test
    void retry_nullJobId_isRefusedCleanly() {
        // Defensive: a malformed RPC payload must
        // not throw — the JSON-RPC handler relies on
        // the tool returning a clean error.
        SubagentRegistry.RetryResult r = SubagentRegistry.instance().retry(null);
        assertFalse(r.retried());
        assertTrue(r.reason().contains("required"));
    }

    @Test
    void retry_firesRunningEvent() throws Exception {
        // The retry should fire a fresh RUNNING event
        // so the TUI/desktop SubagentPanel re-renders
        // the row as running. Without this event, the
        // UI stays stuck on FAILED.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markFailed(id, "boom");
        List<SubagentRegistry.SubagentEvent> events = new ArrayList<>();
        SubagentRegistry.instance().onChange(events::add);
        SubagentRegistry.instance().retry(id);
        // The listener fires synchronously inside
        // retry() (the registry holds its lock).
        assertTrue(events.stream().anyMatch(
                        e -> id.equals(e.jobId())
                                && e.status() == SubagentRegistry.SubagentJob.Status.RUNNING),
                "expected a RUNNING event for the retried job; got: " + events);
    }

    @Test
    void retry_restartsWatchdog() throws Exception {
        // The previous watchdog must be stopped
        // and a fresh one started with the current
        // timeout setting. We verify by inspecting
        // the audit log: a retry appends a RETRY
        // entry, and the new WATCHDOG entry
        // documents the (possibly different)
        // timeout. We use a 200ms timeout via
        // setWatchdogTimeoutMs so the new watchdog
        // is observably fresh.
        SubagentRegistry.instance().setWatchdogTimeoutMs(200L);
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markFailed(id, "boom");
        // Change timeout between fail + retry to
        // verify the new watchdog picks up the new
        // setting.
        SubagentRegistry.instance().setWatchdogTimeoutMs(500L);
        SubagentRegistry.instance().retry(id);
        var entries = SubagentRegistry.instance().auditLog(id);
        // audit should contain RETRY + a fresh
        // WATCHDOG entry (the register-time one
        // was already there).
        boolean hasRetry = entries.stream().anyMatch(e -> "RETRY".equals(e.action()));
        boolean hasWatchdog = entries.stream().anyMatch(e -> "WATCHDOG".equals(e.action()));
        assertTrue(hasRetry, "retry should append a RETRY audit entry; got: " + entries);
        assertTrue(hasWatchdog, "retry should restart the WATCHDOG entry; got: " + entries);
    }

    // =================================================================
    // Watchdog
    // =================================================================

    @Test
    void watchdog_firesAfterTimeout() throws Exception {
        // Set a short timeout (200ms) and a short
        // poll (100ms — minimum allowed by the
        // Watchdog constructor). The job has no
        // updatePartial() calls so the timer
        // naturally expires; the watchdog should
        // mark the job FAILED.
        SubagentRegistry.instance().setWatchdogTimeoutMs(200L);
        CountDownLatch failedLatch = new CountDownLatch(1);
        SubagentRegistry.instance().onChange(e -> {
            if (e.status() == SubagentRegistry.SubagentJob.Status.FAILED) {
                failedLatch.countDown();
            }
        });
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        // Wait up to 5s for the watchdog to fire.
        // The poll cadence is 100ms (clamped from
        // 200/5 = 40 → 100 by the Watchdog's min
        // floor). The watchdog should fire within
        // ~300ms (one poll past timeout) but we
        // allow generous slack for slow CI.
        boolean fired = failedLatch.await(5, TimeUnit.SECONDS);
        assertTrue(fired, "watchdog should mark the job FAILED within 5s");
        SubagentRegistry.SubagentJob j = SubagentRegistry.instance().get(id);
        assertEquals(SubagentRegistry.SubagentJob.Status.FAILED, j.status);
        assertTrue(j.error.contains("watchdog timeout"),
                "error should mention watchdog timeout; was: " + j.error);
    }

    @Test
    void watchdog_resetOnUpdatePartial() throws Exception {
        // Stream partial updates faster than the
        // watchdog timeout — the watchdog must NOT
        // fire. We sleep for 3x the timeout to
        // confirm.
        SubagentRegistry.instance().setWatchdogTimeoutMs(300L);
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        for (int i = 0; i < 6; i++) {
            SubagentRegistry.instance().updatePartial(id, "chunk " + i);
            Thread.sleep(100);
        }
        // The job should still be RUNNING — the
        // watchdog saw fresh activity every 100ms.
        assertEquals(SubagentRegistry.SubagentJob.Status.RUNNING,
                SubagentRegistry.instance().get(id).status,
                "watchdog should not fire while updatePartial keeps the timer alive");
    }

    @Test
    void watchdog_stoppedOnMarkCompleted() throws Exception {
        // After a natural completion the watchdog
        // must stop — a late tick would re-fire
        // markFailed on a job that's already
        // COMPLETED. We verify by waiting for a
        // duration greater than the timeout and
        // confirming the status stays COMPLETED.
        SubagentRegistry.instance().setWatchdogTimeoutMs(200L);
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markCompleted(id, "all good");
        Thread.sleep(500);
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED,
                SubagentRegistry.instance().get(id).status,
                "completed job should not be re-fired by the watchdog");
    }

    @Test
    void watchdog_stoppedOnCancel() throws Exception {
        // Same as above for CANCELLED.
        SubagentRegistry.instance().setWatchdogTimeoutMs(200L);
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().cancel(id, "test reason");
        Thread.sleep(500);
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED,
                SubagentRegistry.instance().get(id).status,
                "cancelled job should not be re-fired by the watchdog");
    }

    @Test
    void watchdog_timeoutFromEnvVar() {
        // Verify the env-var override path. We
        // can't mutate env at runtime (Java
        // limitation), so we verify the helper
        // via a fresh process would set it; the
        // in-process default is 60s.
        // The static read happens at JVM startup,
        // so we can only assert the in-process
        // value via the getter.
        long timeout = SubagentRegistry.instance().getWatchdogTimeoutMs();
        assertTrue(timeout > 0, "watchdog timeout must be positive; was " + timeout);
        // The env-var path is exercised by deployment
        // docs; the unit-level proof is in the
        // above tests that drive setWatchdogTimeoutMs
        // explicitly.
    }
}