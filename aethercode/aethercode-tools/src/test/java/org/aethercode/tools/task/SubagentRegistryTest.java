package org.aethercode.tools.task;

import org.aethercode.tasks.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link SubagentRegistry}. Verifies the
 * job lifecycle: register / markCompleted / markFailed /
 * markCancelled, the LRU eviction cap, and the summary /
 * taskStatusFor helpers.
 */
class SubagentRegistryTest {

    /** Each test needs a fresh registry — the production
     *  instance is a process-singleton, so we re-register
     *  with a unique prompt to make sure no leftover
     *  entries from a previous test interfere. The tests
     *  are written to be robust against prior state. */
    @BeforeEach
    void freshPrompts() {
        // No global reset (the registry is a singleton) —
        // every test uses a unique prompt to keep its
        // jobId distinct from any leftover entries. The
        // lookup helpers (.get / .listRunning /
        // .listFinished) work on the singleton's full
        // state, which is fine for a unit test as long
        // as we only assert on the jobs we just created.
    }

    @Test
    void register_createsRunningJob() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String jobId = reg.register("task-1", "do thing", "explore");
        SubagentRegistry.SubagentJob j = reg.get(jobId);
        assertNotNull(j);
        assertEquals(jobId, j.jobId);
        assertEquals("task-1", j.taskId);
        assertEquals("do thing", j.prompt);
        assertEquals("explore", j.role);
        assertEquals(SubagentRegistry.SubagentJob.Status.RUNNING, j.status);
        assertTrue(j.startedAtMs > 0);
        assertEquals(0, j.finishedAtMs, "running job has no finish time");
    }

    @Test
    void register_nullRoleDefaultsToGeneralPurpose() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String jobId = reg.register("task-2", "summarize", null);
        SubagentRegistry.SubagentJob j = reg.get(jobId);
        assertNotNull(j);
        assertEquals("general-purpose", j.role);
    }

    @Test
    void markCompleted_movesToFinishedWithResult() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String jobId = reg.register("task-3", "x", null);
        reg.markCompleted(jobId, "the answer");
        SubagentRegistry.SubagentJob j = reg.get(jobId);
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED, j.status);
        assertEquals("the answer", j.resultText);
        assertTrue(j.finishedAtMs > 0);
        assertNull(j.error);
        assertFalse(reg.listRunning().stream().anyMatch(r -> r.jobId.equals(jobId)));
        assertTrue(reg.listFinished().stream().anyMatch(r -> r.jobId.equals(jobId)));
    }

    @Test
    void markFailed_movesToFinishedWithError() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String jobId = reg.register("task-4", "x", null);
        reg.markFailed(jobId, "kaboom");
        SubagentRegistry.SubagentJob j = reg.get(jobId);
        assertEquals(SubagentRegistry.SubagentJob.Status.FAILED, j.status);
        assertEquals("kaboom", j.error);
        assertNull(j.resultText);
    }

    @Test
    void markCancelled_movesToFinishedAsCancelled() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String jobId = reg.register("task-5", "x", null);
        reg.markCancelled(jobId);
        SubagentRegistry.SubagentJob j = reg.get(jobId);
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED, j.status);
        assertTrue(j.finishedAtMs > 0);
    }

    @Test
    void get_unknownJobReturnsNull() {
        assertNull(SubagentRegistry.instance().get("sag-doesnotexist"));
    }

    @Test
    void markCompleted_unknownJobIsNoop() {
        // Should not throw — a stale daemon thread finishing
        // after eviction / cancel must not blow up.
        SubagentRegistry.instance().markCompleted("sag-nope", "x");
    }

    @Test
    void listFinished_newestFirst() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String a = reg.register("ta", "prompt a", null);
        // Small sleep to ensure timestamps differ.
        try { Thread.sleep(2); } catch (InterruptedException ignored) {}
        String b = reg.register("tb", "prompt b", null);
        try { Thread.sleep(2); } catch (InterruptedException ignored) {}
        String c = reg.register("tc", "prompt c", null);
        reg.markCompleted(a, "a-done");
        reg.markCompleted(b, "b-done");
        reg.markCompleted(c, "c-done");
        List<SubagentRegistry.SubagentJob> finished = reg.listFinished();
        // The newest should appear first. Scan the
        // finished list for our three ids and confirm
        // the ordering.
        int ai = -1, bi = -1, ci = -1;
        for (int i = 0; i < finished.size(); i++) {
            String id = finished.get(i).jobId;
            if (id.equals(a)) ai = i;
            else if (id.equals(b)) bi = i;
            else if (id.equals(c)) ci = i;
        }
        // All three present.
        assertTrue(ai >= 0 && bi >= 0 && ci >= 0,
                "expected all three jobs in finished list");
        // c finished last → c should appear before b, b before a.
        assertTrue(ci < bi, "c should be before b, got ci=" + ci + " bi=" + bi);
        assertTrue(bi < ai, "b should be before a, got bi=" + bi + " ai=" + ai);
    }

    @Test
    void listRunning_doesNotIncludeFinished() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String finishedId = reg.register("task-fin", "p", null);
        reg.markCompleted(finishedId, "done");
        String runningId = reg.register("task-run", "p", null);
        List<SubagentRegistry.SubagentJob> running = reg.listRunning();
        // The running list contains only the unfinished job.
        assertTrue(running.stream().anyMatch(j -> j.jobId.equals(runningId)));
        assertFalse(running.stream().anyMatch(j -> j.jobId.equals(finishedId)));
    }

    @Test
    void taskStatusFor_mapsJobStatusToTaskStatus() {
        assertEquals(TaskStatus.RUNNING, SubagentRegistry.taskStatusFor(
                newJob(SubagentRegistry.SubagentJob.Status.RUNNING)));
        assertEquals(TaskStatus.COMPLETED, SubagentRegistry.taskStatusFor(
                newJob(SubagentRegistry.SubagentJob.Status.COMPLETED)));
        assertEquals(TaskStatus.FAILED, SubagentRegistry.taskStatusFor(
                newJob(SubagentRegistry.SubagentJob.Status.FAILED)));
        // CANCELLED maps to FAILED (closest existing status).
        assertEquals(TaskStatus.FAILED, SubagentRegistry.taskStatusFor(
                newJob(SubagentRegistry.SubagentJob.Status.CANCELLED)));
        // Null job → FAILED.
        assertEquals(TaskStatus.FAILED, SubagentRegistry.taskStatusFor(null));
    }

    @Test
    void summary_runningIncludesElapsed() {
        SubagentRegistry.SubagentJob j = newJob(
                SubagentRegistry.SubagentJob.Status.RUNNING,
                "what is the answer", "task-x", "explore");
        String s = SubagentRegistry.summary(j);
        assertTrue(s.contains("running"), "summary: " + s);
        assertTrue(s.contains("explore"), "summary: " + s);
        assertTrue(s.contains("task-x"), "summary: " + s);
        assertTrue(s.contains("what is the answer") || s.contains("what is the answe"),
                "summary should include the prompt (possibly truncated): " + s);
    }

    @Test
    void summary_completedIncludesResult() {
        SubagentRegistry.SubagentJob j = newJob(
                SubagentRegistry.SubagentJob.Status.COMPLETED,
                "p", "t", "general-purpose");
        j.resultText = "the answer is 42";
        String s = SubagentRegistry.summary(j);
        assertTrue(s.contains("done"), "summary: " + s);
        assertTrue(s.contains("the answer is 42"), "summary: " + s);
    }

    @Test
    void summary_failedIncludesError() {
        SubagentRegistry.SubagentJob j = newJob(
                SubagentRegistry.SubagentJob.Status.FAILED,
                "p", "t", "general-purpose");
        j.error = "kaboom: out of memory";
        String s = SubagentRegistry.summary(j);
        assertTrue(s.contains("failed"), "summary: " + s);
        assertTrue(s.contains("kaboom"), "summary: " + s);
    }

    @Test
    void summary_cancelledOmitsResult() {
        SubagentRegistry.SubagentJob j = newJob(
                SubagentRegistry.SubagentJob.Status.CANCELLED,
                "p", "t", "general-purpose");
        String s = SubagentRegistry.summary(j);
        assertTrue(s.contains("cancelled"), "summary: " + s);
    }

    @Test
    void summary_nullJobIsGraceful() {
        String s = SubagentRegistry.summary(null);
        assertTrue(s.contains("unknown"), "summary: " + s);
    }

    @Test
    void finished_evictsOldestBeyondCap() {
        // The cap is 64. Adding 65 finished jobs should
        // evict the oldest. We use distinct prompts so
        // we can identify the oldest by order of
        // registration.
        SubagentRegistry reg = SubagentRegistry.instance();
        int sizeBefore = reg.listFinished().size();
        int toAdd = SubagentRegistry.MAX_FINISHED_JOBS + 5;
        String firstAddedId = null;
        for (int i = 0; i < toAdd; i++) {
            String id = reg.register("evict-" + i, "p-" + i, null);
            if (i == 0) firstAddedId = id;
            reg.markCompleted(id, "ok-" + i);
        }
        int sizeAfter = reg.listFinished().size();
        // The cap is 64; the singleton's finished list
        // may already have been near the cap, but the
        // total can never exceed sizeBefore + toAdd, and
        // the registry enforces the cap so the oldest
        // entries are dropped.
        assertTrue(sizeAfter <= SubagentRegistry.MAX_FINISHED_JOBS,
                "finished list should be capped at " + SubagentRegistry.MAX_FINISHED_JOBS
                        + ", got " + sizeAfter);
        // The very first job we added should have been
        // evicted (because we added MAX+5 more after
        // it and the cap is finite).
        assertNull(reg.get(firstAddedId),
                "oldest job should have been evicted by LRU");
    }

    private static SubagentRegistry.SubagentJob newJob(SubagentRegistry.SubagentJob.Status s) {
        return newJob(s, "test prompt", "task-test", "general-purpose");
    }

    private static SubagentRegistry.SubagentJob newJob(SubagentRegistry.SubagentJob.Status s,
                                                       String prompt, String taskId, String role) {
        return newJob(s, prompt, taskId, role, "");
    }

    private static SubagentRegistry.SubagentJob newJob(SubagentRegistry.SubagentJob.Status s,
                                                       String prompt, String taskId, String role,
                                                       String sessionId) {
        SubagentRegistry.SubagentJob j = new SubagentRegistry.SubagentJob(
                "sag-test-" + System.nanoTime(), taskId, prompt, role, sessionId);
        j.status = s;
        j.finishedAtMs = s == SubagentRegistry.SubagentJob.Status.RUNNING ? 0 : System.currentTimeMillis();
        return j;
    }

    @Test
    void onChange_nullListenerRejected() {
        SubagentRegistry reg = SubagentRegistry.instance();
        assertThrows(IllegalArgumentException.class, () -> reg.onChange(null));
    }

    @Test
    void onChange_firesOnRegister() {
        SubagentRegistry reg = SubagentRegistry.instance();
        CopyOnWriteArrayList<SubagentRegistry.SubagentEvent> received =
                new CopyOnWriteArrayList<>();
        reg.onChange(received::add);

        String id = reg.register("r91d-task", "p", "explore");
        // Drain: we may receive other events from concurrent tests, so filter by jobId.
        boolean found = false;
        for (var ev : received) {
            if (ev.jobId().equals(id)) {
                found = true;
                assertEquals(SubagentRegistry.SubagentJob.Status.RUNNING, ev.status());
                assertEquals("explore", ev.role());
                assertNotNull(ev.summary());
                assertTrue(ev.atMs() > 0L);
                assertFalse(ev.isTerminal(), "register event is not terminal");
                break;
            }
        }
        assertTrue(found, "expected a register event for " + id);
    }

    @Test
    void onChange_firesOnCompleted() {
        SubagentRegistry reg = SubagentRegistry.instance();
        CopyOnWriteArrayList<SubagentRegistry.SubagentEvent> received =
                new CopyOnWriteArrayList<>();
        reg.onChange(received::add);

        String id = reg.register("r91d-task-c", "p", null);
        reg.markCompleted(id, "all done");
        SubagentRegistry.SubagentEvent terminal = null;
        for (var ev : received) {
            if (ev.jobId().equals(id)
                    && ev.status() == SubagentRegistry.SubagentJob.Status.COMPLETED) {
                terminal = ev;
                break;
            }
        }
        assertNotNull(terminal, "expected a completed event for " + id);
        assertTrue(terminal.isTerminal());
        assertTrue(terminal.elapsedMs() >= 0L);
    }

    @Test
    void onChange_firesOnFailed() {
        SubagentRegistry reg = SubagentRegistry.instance();
        AtomicInteger seen = new AtomicInteger();
        CopyOnWriteArrayList<SubagentRegistry.SubagentEvent> received =
                new CopyOnWriteArrayList<>();
        reg.onChange(ev -> {
            if (ev.status() == SubagentRegistry.SubagentJob.Status.FAILED) {
                seen.incrementAndGet();
            }
            received.add(ev);
        });

        String id = reg.register("r91d-task-f", "p", null);
        reg.markFailed(id, "boom");
        assertTrue(seen.get() >= 1, "expected at least one FAILED event for " + id);
    }

    @Test
    void onChange_firesOnCancelled() {
        SubagentRegistry reg = SubagentRegistry.instance();
        CopyOnWriteArrayList<SubagentRegistry.SubagentEvent> received =
                new CopyOnWriteArrayList<>();
        reg.onChange(received::add);

        String id = reg.register("r91d-task-x", "p", null);
        reg.markCancelled(id);
        boolean found = false;
        for (var ev : received) {
            if (ev.jobId().equals(id)
                    && ev.status() == SubagentRegistry.SubagentJob.Status.CANCELLED) {
                found = true;
                assertTrue(ev.isTerminal());
                break;
            }
        }
        assertTrue(found, "expected a cancelled event for " + id);
    }

    @Test
    void onChange_misbehavingListenerDoesNotBreakRegistry() {
        // A listener that throws should be skipped silently
        // (logged warn) so the registry stays usable for the
        // next listener and the engine.
        SubagentRegistry reg = SubagentRegistry.instance();
        reg.onChange(ev -> { throw new RuntimeException("listener boom"); });
        CopyOnWriteArrayList<SubagentRegistry.SubagentEvent> received =
                new CopyOnWriteArrayList<>();
        reg.onChange(received::add);

        String id = reg.register("r91d-task-bad", "p", null);
        reg.markCompleted(id, "ok");
        // The good listener still got the events.
        boolean gotRegister = false;
        boolean gotComplete = false;
        for (var ev : received) {
            if (!ev.jobId().equals(id)) continue;
            if (ev.status() == SubagentRegistry.SubagentJob.Status.RUNNING) gotRegister = true;
            if (ev.status() == SubagentRegistry.SubagentJob.Status.COMPLETED) gotComplete = true;
        }
        assertTrue(gotRegister);
        assertTrue(gotComplete);
    }

    @Test
    void onChange_multipleListenersAllInvoked() {
        SubagentRegistry reg = SubagentRegistry.instance();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        reg.onChange(ev -> a.incrementAndGet());
        reg.onChange(ev -> b.incrementAndGet());

        reg.markCancelled(reg.register("r91d-task-multi", "p", null));
        assertTrue(a.get() >= 1);
        assertTrue(b.get() >= 1);
    }

    @Test
    void subagentEvent_isTerminal() {
        // SubagentEvent constructor now takes a
        // trailing reason string. We pass "" for the
        // non-cancellation cases; this test does not
        // exercise reason semantics, only isTerminal().
        // SubagentEvent now also has a trailing
        // partialResult field; we pass "" everywhere
        // (this test does not exercise partial text).
        var running = new SubagentRegistry.SubagentEvent(
                "sag-x", "r",
                SubagentRegistry.SubagentJob.Status.RUNNING, 0L, "s", 0L, "", "", "", "");
        var completed = new SubagentRegistry.SubagentEvent(
                "sag-x", "r",
                SubagentRegistry.SubagentJob.Status.COMPLETED, 100L, "s", 0L, "", "ok", "", "");
        var failed = new SubagentRegistry.SubagentEvent(
                "sag-x", "r",
                SubagentRegistry.SubagentJob.Status.FAILED, 100L, "s", 0L, "", "boom", "", "");
        var cancelled = new SubagentRegistry.SubagentEvent(
                "sag-x", "r",
                SubagentRegistry.SubagentJob.Status.CANCELLED, 100L, "s", 0L, "", "", "", "");
        assertFalse(running.isTerminal());
        assertTrue(completed.isTerminal());
        assertTrue(failed.isTerminal());
        assertTrue(cancelled.isTerminal());
    }

    @Test
    void cancel_unknownJobReturnsAlreadyFinished() {
        // A bogus id (never registered) should be reported
        // as already-finished — the renderer's UI can
        // distinguish "I cancelled a live job" from
        // "nothing to cancel" by reading the second boolean.
        SubagentRegistry reg = SubagentRegistry.instance();
        SubagentRegistry.CancelResult r = reg.cancel("sag-doesnotexist-r92c");
        assertFalse(r.cancelled());
        assertTrue(r.alreadyFinished());
    }

    @Test
    void cancel_nullJobIdReturnsAlreadyFinished() {
        // Defensive: a malformed RPC payload should not NPE.
        SubagentRegistry reg = SubagentRegistry.instance();
        SubagentRegistry.CancelResult r = reg.cancel(null);
        assertFalse(r.cancelled());
        assertTrue(r.alreadyFinished());
    }

    @Test
    void cancel_runningJobInterruptsThreadAndFlipsStatus() throws Exception {
        // The classic case: a background subagent is running
        // in its own daemon thread. cancel() must:
        //   - flip the status to CANCELLED (no longer RUNNING)
        //   - interrupt the worker thread (so a chat-client
        //     stream that respects Thread.interrupt() can
        //     bail out)
        //   - fire a CANCELLED event so the renderer sees it
        SubagentRegistry reg = SubagentRegistry.instance();
        var seen = new CopyOnWriteArrayList<SubagentRegistry.SubagentEvent>();
        reg.onChange(seen::add);

        String id = reg.register("task-r92c-cancel", "do something long", "explore");
        // Stand in for the real daemon thread with a thread
        // that loops on a sleep and exits on interrupt.
        Thread worker = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException ie) {
                // expected — the cancel() interrupted us
                Thread.currentThread().interrupt();
            }
        }, "subagent-test-worker");
        worker.setDaemon(true);
        reg.attachThread(id, worker);
        worker.start();

        // Give the worker a tick to start.
        Thread.sleep(20);

        SubagentRegistry.CancelResult r = reg.cancel(id);
        assertTrue(r.cancelled(), "cancelled flag should be true for a live job");
        assertFalse(r.alreadyFinished(), "alreadyFinished should be false for a live job");

        // The status should now be CANCELLED.
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED, reg.get(id).status);
        // The job is no longer in the running set.
        assertNull(reg.listRunning().stream().filter(j -> id.equals(j.jobId)).findFirst().orElse(null));

        // The worker thread should be interrupted.
        worker.join(1000);
        assertFalse(worker.isAlive(), "cancel() should have interrupted the worker thread");

        // The CANCELLED event was fired.
        var last = seen.stream().filter(e -> id.equals(e.jobId())
                && e.status() == SubagentRegistry.SubagentJob.Status.CANCELLED)
                .findFirst().orElse(null);
        assertNotNull(last, "CANCELLED event should have been fired");
    }

    @Test
    void cancel_finishedJobIsNoop() {
        // After markCompleted, cancel must report
        // alreadyFinished=true and not touch the job.
        SubagentRegistry reg = SubagentRegistry.instance();
        String id = reg.register("task-r92c-cancel-finished", "fast", "explore");
        reg.markCompleted(id, "ok");

        SubagentRegistry.CancelResult r = reg.cancel(id);
        assertFalse(r.cancelled());
        assertTrue(r.alreadyFinished());
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED, reg.get(id).status);
    }

    @Test
    void attachThread_nullArgsAreSafe() {
        // Defensive: a bad call must not throw.
        SubagentRegistry reg = SubagentRegistry.instance();
        reg.attachThread(null, null);
        reg.attachThread("sag-x", null);
        reg.attachThread(null, Thread.currentThread());
    }

    @Test
    void attachThread_unknownJobIsNoop() {
        // Attaching a thread to a job that doesn't exist
        // (e.g. a stale handle) must not throw and must
        // not pollute the registry.
        SubagentRegistry reg = SubagentRegistry.instance();
        reg.attachThread("sag-doesnotexist-r92c-attach", Thread.currentThread());
        // No assertion beyond "didn't throw" — the registry
        // state is unchanged.
    }

    @Test
    void cancel_runningJobWithoutAttachedThreadStillFlipsStatus() {
        // If a job is registered without an attached
        // thread (e.g. a job from previously-C shipped, or
        // a foreground dispatch that re-used the register
        // path), cancel() still updates the status. The
        // worker (if any) just won't see an interrupt.
        SubagentRegistry reg = SubagentRegistry.instance();
        String id = reg.register("task-r92c-no-thread", "no thread", "explore");
        SubagentRegistry.CancelResult r = reg.cancel(id);
        assertTrue(r.cancelled());
        assertFalse(r.alreadyFinished());
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED, reg.get(id).status);
    }

    @Test
    void cancel_firesSubagentEventInOrder() {
        // cancel() must fire the CANCELLED event
        // AFTER the natural RUNNING/COMPLETED events for
        // the same jobId. Renderers rely on this for the
        // "the job I just cancelled was running" toast.
        SubagentRegistry reg = SubagentRegistry.instance();
        var seen = new CopyOnWriteArrayList<SubagentRegistry.SubagentEvent>();
        reg.onChange(seen::add);

        String id = reg.register("task-r92c-event-order", "order test", "explore");
        reg.cancel(id);

        // The list contains the RUNNING event followed by
        // the CANCELLED event for this jobId.
        var myEvents = seen.stream()
                .filter(e -> id.equals(e.jobId()))
                .toList();
        assertEquals(2, myEvents.size(), "expected RUNNING + CANCELLED events");
        assertEquals(SubagentRegistry.SubagentJob.Status.RUNNING, myEvents.get(0).status());
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED, myEvents.get(1).status());
    }

    @Test
    void register_withSessionIdCarriesOnEvent() {
        // The sessionId passed at register() must appear
        // on every subsequent event for that job (RUNNING,
        // COMPLETED, FAILED, CANCELLED). The TUI/desktop
        // read it to filter events to the current session.
        SubagentRegistry reg = SubagentRegistry.instance();
        var seen = new CopyOnWriteArrayList<SubagentRegistry.SubagentEvent>();
        reg.onChange(seen::add);

        String id = reg.register("task-r92d-session", "session test", "explore", "sess-42");
        // The first event (RUNNING) must carry the sessionId.
        var running = seen.stream()
                .filter(e -> id.equals(e.jobId()))
                .filter(e -> e.status() == SubagentRegistry.SubagentJob.Status.RUNNING)
                .findFirst().orElse(null);
        assertNotNull(running);
        assertEquals("sess-42", running.sessionId());

        reg.markCompleted(id, "ok");
        var completed = seen.stream()
                .filter(e -> id.equals(e.jobId()))
                .filter(e -> e.status() == SubagentRegistry.SubagentJob.Status.COMPLETED)
                .findFirst().orElse(null);
        assertNotNull(completed);
        assertEquals("sess-42", completed.sessionId());
    }

    @Test
    void register_sessionIdDefaultsToEmptyForBackwardCompat() {
        // The 3-arg register() overload (no sessionId)
        // must continue to work — it defaults to "" so
        // legacy-D callers and single-session daemons
        // see every event.
        SubagentRegistry reg = SubagentRegistry.instance();
        var seen = new CopyOnWriteArrayList<SubagentRegistry.SubagentEvent>();
        reg.onChange(seen::add);

        String id = reg.register("task-r92d-default-session", "default", "explore");
        var ev = seen.stream()
                .filter(e -> id.equals(e.jobId()))
                .findFirst().orElse(null);
        assertNotNull(ev);
        assertEquals("", ev.sessionId(), "3-arg register should default to empty sessionId");
    }

    @Test
    void register_nullSessionIdBecomesEmpty() {
        // Defensive: a null sessionId should be stored as
        // "" so the wire payload is always a string.
        SubagentRegistry reg = SubagentRegistry.instance();
        String id = reg.register("task-r92d-null-session", "null session", "explore", null);
        assertEquals("", reg.get(id).sessionId);
    }

    @Test
    void subagentJob_constructorAcceptsSessionId() {
        // Direct constructor (package-private) is reachable
        // from the test in the same package. Verify the
        // new sessionId field is preserved.
        var j = new SubagentRegistry.SubagentJob(
                "sag-direct", "task-direct", "p", "r", "sess-99");
        assertEquals("sess-99", j.sessionId);
    }

    @Test
    void subagentJob_constructorNullSessionIdDefaultsToEmpty() {
        var j = new SubagentRegistry.SubagentJob(
                "sag-direct-null", "task-direct", "p", "r", null);
        assertEquals("", j.sessionId);
    }

    @Test
    void markCancelled_preservesSessionIdOnEvent() {
        // cancel() goes through markCancelled() which
        // must carry the sessionId from the job onto the
        // event payload.
        SubagentRegistry reg = SubagentRegistry.instance();
        var seen = new CopyOnWriteArrayList<SubagentRegistry.SubagentEvent>();
        reg.onChange(seen::add);

        String id = reg.register("task-r92d-cancel-session", "x", "explore", "sess-7");
        reg.cancel(id);

        var ev = seen.stream()
                .filter(e -> id.equals(e.jobId()))
                .filter(e -> e.status() == SubagentRegistry.SubagentJob.Status.CANCELLED)
                .findFirst().orElse(null);
        assertNotNull(ev);
        assertEquals("sess-7", ev.sessionId());
    }

    @Test
    void listFinished_canBeFilteredBySessionId() {
        // callers (e.g. the TUI panel) want to
        // list a session's subagents without re-implementing
        // the filter. The registry itself doesn't keep a
        // session-indexed view (it's process-singleton and
        // would add overhead) so the caller filters the
        // snapshot. This test pins the contract: a session
        // filter on the snapshot returns only that session's
        // jobs, and jobs from other sessions are skipped.
        SubagentRegistry reg = SubagentRegistry.instance();
        String idA = reg.register("task-r92d-list-A", "A", "explore", "sess-A");
        reg.markCompleted(idA, "ok-A");
        String idB = reg.register("task-r92d-list-B", "B", "explore", "sess-B");
        reg.markCompleted(idB, "ok-B");

        var all = reg.listFinished();
        var sessionAOnly = all.stream()
                .filter(j -> "sess-A".equals(j.sessionId))
                .toList();
        assertTrue(sessionAOnly.stream().anyMatch(j -> idA.equals(j.jobId)),
                "session-A filter should include job from session A");
        assertTrue(sessionAOnly.stream().noneMatch(j -> idB.equals(j.jobId)),
                "session-A filter should exclude jobs from session B");
    }

    // =================================================================================
    // cancel reason + audit log
    // =================================================================================

    @Test
    void cancel_withReason_publishesReasonOnEvent() {
        // Capture the terminal event so we can assert the
        // reason field made it onto the wire shape.
        java.util.concurrent.atomic.AtomicReference<SubagentRegistry.SubagentEvent> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        SubagentRegistry.instance().onChange(captured::set);

        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        var result = SubagentRegistry.instance().cancel(id, "user pressed Ctrl+C");
        assertTrue(result.cancelled());
        assertFalse(result.alreadyFinished());
        SubagentRegistry.SubagentEvent ev = captured.get();
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED, ev.status());
        assertEquals("user pressed Ctrl+C", ev.reason());
    }

    @Test
    void cancel_emptyReason_fallsBackToEmptyString() {
        // A cancel with null / empty reason should still
        // work, and the event should carry an empty
        // reason (not a null) so the wire shape is stable.
        java.util.concurrent.atomic.AtomicReference<SubagentRegistry.SubagentEvent> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        SubagentRegistry.instance().onChange(captured::set);

        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().cancel(id);
        assertEquals("", captured.get().reason());
    }

    @Test
    void cancel_nullReason_isTreatedAsEmpty() {
        // Defensive: explicit null must not throw.
        java.util.concurrent.atomic.AtomicReference<SubagentRegistry.SubagentEvent> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        SubagentRegistry.instance().onChange(captured::set);

        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().cancel(id, null);
        assertEquals("", captured.get().reason());
        // The job is still cancelled, just without a
        // reason attached.
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED,
                SubagentRegistry.instance().get(id).status);
    }

    @Test
    void cancel_alreadyFinished_doesNotPublishReason() {
        // Idempotency: a cancel on a finished job returns
        // alreadyFinished=true and does NOT fire another
        // event (no reason to publish, no transition).
        SubagentRegistry.SubagentEvent[] captured = new SubagentRegistry.SubagentEvent[1];
        SubagentRegistry.instance().onChange(ev -> captured[0] = ev);

        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markCompleted(id, "ok");
        var result = SubagentRegistry.instance().cancel(id, "ignored");
        assertFalse(result.cancelled());
        assertTrue(result.alreadyFinished());
        // The most recent event is the COMPLETED one,
        // not a duplicate CANCELLED.
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED, captured[0].status());
    }

    @Test
    void cancel_reasonStoredOnJob() {
        // The reason should also be on the SubagentJob
        // itself so consumers that look the job up later
        // (not just the live event stream) can see it.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().cancel(id, "model timeout");
        assertEquals("model timeout",
                SubagentRegistry.instance().get(id).cancelReason);
    }

    @Test
    void auditLog_recordsEachLifecycleTransition() {
        // The audit log should contain one entry per
        // transition: REGISTER, ATTACH_THREAD, CANCEL.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        Thread t = new Thread(() -> {}, "r93d-test-thread");
        SubagentRegistry.instance().attachThread(id, t);
        SubagentRegistry.instance().cancel(id, "audit-reason");
        var entries = SubagentRegistry.instance().auditLog(id);
        assertEquals(3, entries.size());
        assertEquals("REGISTER",     entries.get(0).action());
        assertEquals("ATTACH_THREAD", entries.get(1).action());
        assertEquals("CANCEL",       entries.get(2).action());
        // The CANCEL entry's details should include the reason.
        assertTrue(entries.get(2).details().contains("audit-reason"),
                "CANCEL details should include the reason; was: " + entries.get(2).details());
        // And the ATTACH_THREAD entry's details should
        // include the thread name.
        assertTrue(entries.get(1).details().contains("r93d-test-thread"),
                "ATTACH_THREAD details should include the thread name; was: " + entries.get(1).details());
    }

    @Test
    void auditLog_completeJob_hasFourEntries() {
        // REGISTER + ATTACH_THREAD + COMPLETE = 3 entries
        // (no CANCEL on the happy path).
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        Thread t = new Thread(() -> {}, "r93d-thread-complete");
        SubagentRegistry.instance().attachThread(id, t);
        SubagentRegistry.instance().markCompleted(id, "all good");
        var entries = SubagentRegistry.instance().auditLog(id);
        assertEquals(3, entries.size());
        assertEquals("REGISTER",      entries.get(0).action());
        assertEquals("ATTACH_THREAD", entries.get(1).action());
        assertEquals("COMPLETE",      entries.get(2).action());
        // COMPLETE entry should reference the result.
        assertTrue(entries.get(2).details().contains("all good"));
    }

    @Test
    void auditLog_failedJob_recordsError() {
        // FAIL entry should record the error message in
        // the details so an offline log dump shows what
        // blew up.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markFailed(id, "synthetic boom");
        var entries = SubagentRegistry.instance().auditLog(id);
        assertEquals(2, entries.size());
        assertEquals("REGISTER", entries.get(0).action());
        assertEquals("FAIL",     entries.get(1).action());
        assertEquals("synthetic boom", entries.get(1).details());
    }

    @Test
    void auditLog_unknownJob_returnsEmptyList() {
        // Defensive: asking for a non-existent job's
        // audit log returns an empty list (not null,
        // not an exception).
        var entries = SubagentRegistry.instance().auditLog("sag-bogus");
        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void auditLog_cappedAtMaxEntries() {
        // The audit log is bounded to MAX_AUDIT_ENTRIES
        // so a long-running job cannot accumulate
        // unbounded entries. We exercise the cap by
        // re-attaching a thread many times (each call
        // appends one entry) and verifying the
        // eventually-trimmed size.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        for (int i = 0; i < SubagentRegistry.MAX_AUDIT_ENTRIES + 10; i++) {
            Thread t = new Thread(() -> {}, "r93d-loop-" + i);
            SubagentRegistry.instance().attachThread(id, t);
        }
        var entries = SubagentRegistry.instance().auditLog(id);
        assertEquals(SubagentRegistry.MAX_AUDIT_ENTRIES, entries.size());
        // The oldest entries should be the ones evicted;
        // the newest should be present.
        assertEquals("ATTACH_THREAD", entries.get(entries.size() - 1).action());
        assertTrue(entries.get(entries.size() - 1).details().contains(
                "r93d-loop-" + (SubagentRegistry.MAX_AUDIT_ENTRIES + 9)));
    }

    @Test
    void summary_cancelledJobIncludesReason() {
        // The summary() helper should include the reason
        // when one is given. Without prior round this would be
        // a bare "[cancelled ...] role=... task=..."
        // string; with prior round it should append
        // " reason=<text>".
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().cancel(id, "manual");
        String s = SubagentRegistry.summary(SubagentRegistry.instance().get(id));
        assertTrue(s.contains("cancelled"), "summary should mention cancelled: " + s);
        assertTrue(s.contains("reason=manual"),
                "summary should include reason=manual: " + s);
    }

    // =================================================================================
    // streaming partial results
    // =================================================================================

    @Test
    void updatePartial_firesRunningEventWithPartialText() {
        // Capture every event the registry fires so we
        // can assert the partial text made it onto the
        // wire shape.
        java.util.List<SubagentRegistry.SubagentEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        SubagentRegistry.instance().onChange(events::add);

        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().updatePartial(id, "first chunk of output");
        SubagentRegistry.instance().updatePartial(id, "first chunk of output and more");

        // The most recent event carries the latest
        // partial text. The listener fires on every
        // update so we get at least two RUNNING events
        // with the partial text.
        SubagentRegistry.SubagentEvent latest = events.get(events.size() - 1);
        assertEquals(SubagentRegistry.SubagentJob.Status.RUNNING, latest.status());
        assertEquals("first chunk of output and more", latest.partialResult());
    }

    @Test
    void updatePartial_storesOnJob() {
        // The partial text is also stored on the
        // SubagentJob so consumers that look the job
        // up later can see the latest preview.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().updatePartial(id, "preview text");
        assertEquals("preview text",
                SubagentRegistry.instance().get(id).partialResult);
    }

    @Test
    void updatePartial_finishedJobIsNoOp() {
        // A late partial event that arrives after the
        // job has been marked COMPLETED should be
        // dropped, not resurrect the job's RUNNING
        // status.
        java.util.List<SubagentRegistry.SubagentEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        SubagentRegistry.instance().onChange(events::add);
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markCompleted(id, "final result");
        int eventsBefore = events.size();
        SubagentRegistry.instance().updatePartial(id, "ignored chunk");
        // No new event fired.
        assertEquals(eventsBefore, events.size());
    }

    @Test
    void updatePartial_nullJobIdIsNoOp() {
        // Defensive: a null jobId should not crash.
        SubagentRegistry.instance().updatePartial(null, "ignored");
    }

    @Test
    void updatePartial_truncatesAtMaxPartialChars() {
        // Long partial text is truncated on the wire to
        // keep the notification payload bounded.
        String big = "x".repeat(SubagentRegistry.MAX_PARTIAL_CHARS + 1000);
        java.util.concurrent.atomic.AtomicReference<SubagentRegistry.SubagentEvent> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        SubagentRegistry.instance().onChange(captured::set);
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().updatePartial(id, big);
        // The wire payload (partialResult) is at most
        // MAX_PARTIAL_CHARS long (plus the trailing
        // ellipsis marker).
        String wire = captured.get().partialResult();
        assertTrue(wire.length() <= SubagentRegistry.MAX_PARTIAL_CHARS + 1,
                "wire partial should be truncated to ≤ MAX+1 chars; got " + wire.length());
        // But the full text is still on the job.
        assertEquals(big, SubagentRegistry.instance().get(id).partialResult);
    }

    @Test
    void updatePartial_auditedInJobLog() {
        // The PARTIAL entry should be appended to the
        // job's audit log so a debug dump shows the
        // streaming history.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().updatePartial(id, "chunk 1");
        SubagentRegistry.instance().updatePartial(id, "chunk 2");
        var entries = SubagentRegistry.instance().auditLog(id);
        // 1 REGISTER + 2 PARTIAL = 3 entries.
        assertEquals(3, entries.size());
        assertEquals("REGISTER", entries.get(0).action());
        assertEquals("PARTIAL",  entries.get(1).action());
        assertEquals("PARTIAL",  entries.get(2).action());
        // Each PARTIAL entry's details should record
        // the size of the chunk.
        assertTrue(entries.get(1).details().contains("chars=7"));
        assertTrue(entries.get(2).details().contains("chars=7"));
    }

    @Test
    void markCompleted_clearsPartialResult() {
        // The partial slot should be cleared on
        // terminal transition so the job's record
        // reflects the final state.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().updatePartial(id, "in progress");
        SubagentRegistry.instance().markCompleted(id, "final");
        assertEquals("", SubagentRegistry.instance().get(id).partialResult);
    }

    @Test
    void markFailed_clearsPartialResult() {
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().updatePartial(id, "in progress");
        SubagentRegistry.instance().markFailed(id, "boom");
        assertEquals("", SubagentRegistry.instance().get(id).partialResult);
    }

    @Test
    void markCancelled_clearsPartialResult() {
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().updatePartial(id, "in progress");
        SubagentRegistry.instance().cancel(id, "user gave up");
        assertEquals("", SubagentRegistry.instance().get(id).partialResult);
    }
}
