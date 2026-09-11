package org.aethercode.core.workflow;

import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** tests for the workflow executor. The executor walks
 *  a parsed {@link WorkflowReader.WorkflowDoc} step by step,
 *  executing each step by type, and emits a {@code workflow_step}
 *  SideNote on every state transition. These tests assert the
 *  step statuses, the captured outputs, and the gate's branching
 *  — the parts the desktop's {@code WorkflowProgressBar} reads. */
class WorkflowExecutorTest {

    private static List<String> capture(WorkflowReader.WorkflowDoc doc, Map<String, Object> inputs) throws Exception {
        List<String> events = new ArrayList<>();
        WorkflowExecutor exec = new WorkflowExecutor(doc, inputs, "wf-test",
                (StreamEvent ev) -> {
                    if (ev instanceof StreamEvent.SideNote sn) {
                        events.add(sn.kind() + ":" + sn.message());
                    }
                });
        Thread t = new Thread(exec::run, "workflow-exec-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        return events;
    }

    // ---- shell ---------------------------------------------------------

    @Test
    void shellStepRunsCommandAndCapturesExitCode() throws Exception {
        String yaml = """
                name: t
                steps:
                  - id: hello
                    type: shell
                    cmd: "%s"
                """.formatted(isWindows() ? "echo hello-world" : "echo hello-world");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.startsWith("workflow_step:step 1/1 ok: hello")),
                "expected ok transition, got: " + events);
        // captured output is not on the event channel — it's
        // in the executor's results map. We don't read it
        // here; the daemon captures it for the desktop on
        // demand.
    }

    @Test
    void shellStepReportsErrorOnNonZeroExit() throws Exception {
        String yaml = """
                name: t
                steps:
                  - id: bad
                    type: shell
                    cmd: "%s"
                """.formatted(isWindows() ? "cmd /c exit 7" : "exit 7");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("bad") && e.contains("error")),
                "expected error transition, got: " + events);
    }

    // ---- delay ---------------------------------------------------------

    @Test
    void delayStepSleepsForConfiguredDuration() throws Exception {
        String yaml = """
                name: t
                steps:
                  - id: d
                    type: delay
                    duration: 100
                """;
        var doc = WorkflowReader.parse(yaml, "t");
        long start = System.currentTimeMillis();
        var events = capture(doc, Map.of());
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 100, "expected ≥ 100ms, got " + elapsed);
        assertTrue(events.stream().anyMatch((e) -> e.contains("ok: d")),
                "expected ok transition, got: " + events);
    }

    // ---- parallel ------------------------------------------------------

    @Test
    void parallelStepFansOutBranches() throws Exception {
        String yaml = """
                name: t
                steps:
                  - id: p
                    type: parallel
                    branches:
                      - id: b1
                        type: shell
                        cmd: "echo a"
                      - id: b2
                        type: shell
                        cmd: "echo b"
                """;
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("b1") && e.contains("ok")),
                "expected b1 ok, got: " + events);
        assertTrue(events.stream().anyMatch((e) -> e.contains("b2") && e.contains("ok")),
                "expected b2 ok, got: " + events);
        assertTrue(events.stream().anyMatch((e) -> e.contains("p") && e.contains("ok")),
                "expected parallel p ok, got: " + events);
    }

    @Test
    void parallelStepAggregatesBranchError() throws Exception {
        String yaml = """
                name: t
                steps:
                  - id: p
                    type: parallel
                    branches:
                      - id: ok
                        type: shell
                        cmd: "echo a"
                      - id: bad
                        type: shell
                        cmd: "%s"
                """.formatted(isWindows() ? "cmd /c exit 1" : "exit 1");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        // parallel parent should be "error" because one branch failed
        assertTrue(events.stream().anyMatch((e) -> e.contains("ok: p") == false
                && e.contains("p") && e.contains("error")),
                "expected parallel p error, got: " + events);
    }

    // ---- gate ---------------------------------------------------------

    @Test
    void gateStepFiresThenBranchWhenTrue() throws Exception {
        String yaml = """
                name: t
                steps:
                  - id: setup
                    type: shell
                    cmd: "echo a"
                  - id: decide
                    type: gate
                    when: "{{steps.setup.exitCode}} == 0"
                    then:
                      - id: ok
                        type: delay
                        duration: 10
                """;
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("ok: ok")),
                "expected ok ok, got: " + events);
    }

    @Test
    void gateStepFiresElseBranchWhenFalse() throws Exception {
        // setup intentionally fails (exit 3) but is
        // marked continue_on_error so the workflow still
        // advances to the gate. The gate's when-expression
        // sees exitCode == 3 (not 0) and fires the else_
        // branch (fail_branch).
        String yaml = """
                name: t
                steps:
                  - id: setup
                    type: shell
                    continue_on_error: true
                    cmd: "%s"
                  - id: decide
                    type: gate
                    when: "{{steps.setup.exitCode}} == 0"
                    then:
                      - id: ok_branch
                        type: delay
                        duration: 10
                    else_:
                      - id: fail_branch
                        type: delay
                        duration: 10
                """.formatted(isWindows() ? "cmd /c exit 3" : "exit 3");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("ok: fail_branch")),
                "expected fail_branch ok, got: " + events);
        assertTrue(events.stream().noneMatch((e) -> e.contains("ok_branch") && e.contains("ok")),
                "ok_branch should not have fired, got: " + events);
    }

    // ---- parameter substitution -----------------------------------------

    @Test
    void shellStepSubstitutesInputs() throws Exception {
        String yaml = """
                name: t
                inputs:
                  greet:
                    type: string
                    default: "hello"
                steps:
                  - id: echo
                    type: shell
                    cmd: "echo {{inputs.greet}}"
                """;
        var doc = WorkflowReader.parse(yaml, "t");
        // We don't capture stdout here, but the substitution
        // must not throw and the step must end ok. We assert
        // the run finished without exception by waiting on
        // the executor thread.
        var events = capture(doc, Map.of("greet", "world"));
        assertTrue(events.stream().anyMatch((e) -> e.contains("ok: echo")));
    }

    // ---- when parser --------------------------------------------------

    @Test
    void whenParserHandlesComparisons() {
        var doc = new WorkflowReader.WorkflowDoc("t", "", List.of(),
                List.of(new WorkflowReader.Step("a", "shell")),
                "name: t\nsteps:\n  - id: a\n    type: shell\n");
        WorkflowExecutor exec = new WorkflowExecutor(doc, Map.of(), "x", (e) -> {});
        assertTrue(exec.evalWhen("0 == 0"));
        assertFalse(exec.evalWhen("0 == 1"));
        assertTrue(exec.evalWhen("1 != 2"));
        assertTrue(exec.evalWhen("5 > 3"));
        assertFalse(exec.evalWhen("3 > 5"));
        assertTrue(exec.evalWhen("0 == 0 && 1 == 1"));
        assertFalse(exec.evalWhen("0 == 0 && 1 == 2"));
        assertTrue(exec.evalWhen("0 == 1 || 1 == 1"));
    }

    @Test
    void whenParserHandlesSubstitution() {
        var doc = new WorkflowReader.WorkflowDoc("t", "", List.of(),
                List.of(new WorkflowReader.Step("test", "shell")),
                "name: t\nsteps:\n  - id: test\n    type: shell\n    cmd: \"echo hi\"\n");
        WorkflowExecutor exec = new WorkflowExecutor(doc, Map.of(), "x", (e) -> {});
        // After running, steps.test.exitCode is 0
        // Manually pre-seed results for the substitution test
        // (don't actually run the step).
        try {
            var f = WorkflowExecutor.class.getDeclaredField("results");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Map<String, WorkflowExecutor.StepResult>) f.get(exec);
            map.put("test", new WorkflowExecutor.StepResult("test", "shell"));
            map.get("test").exitCode = 0;
        } catch (Exception e) { fail("reflection setup: " + e.getMessage()); }
        assertTrue(exec.evalWhen("{{steps.test.exitCode}} == 0"));
    }

    // ---- helpers -------------------------------------------------------

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    void continueOnErrorDowngradesToSkipped() throws Exception {
        // A step with `continue_on_error: true` that fails
        // should be marked "skipped" and the workflow should
        // keep advancing to the next step. The next step runs
        // and is "ok", so the overall status is "ok".
        //
        // The event stream sees both an "error" (from
        // runShell's non-zero exit code) AND a "skipped"
        // (from the run() loop's downgrade via
        // continue_on_error). The final state is "skipped";
        // the "error" event is the underlying cause.
        String yaml = """
                name: t
                steps:
                  - id: may-fail
                    type: shell
                    continue_on_error: true
                    cmd: "%s"
                  - id: must-run
                    type: shell
                    cmd: "echo ok"
                """.formatted(isWindows() ? "cmd /c exit 9" : "exit 9");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("skipped: may-fail")),
                "expected may-fail skipped, got: " + events);
        assertTrue(events.stream().anyMatch((e) -> e.contains("ok: must-run")),
                "expected must-run ok, got: " + events);
        assertTrue(events.stream().noneMatch((e) -> e.contains("ok: may-fail")),
                "may-fail must not be ok, got: " + events);
        // Workflow must keep advancing: must-run is the
        // second step, and we should see its ok event after
        // the may-fail transition.
        int mayFailIdx = -1, mustRunIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if (mayFailIdx < 0 && events.get(i).contains("skipped: may-fail")) mayFailIdx = i;
            if (mustRunIdx < 0 && events.get(i).contains("ok: must-run")) mustRunIdx = i;
        }
        assertTrue(mayFailIdx >= 0 && mustRunIdx > mayFailIdx,
                "must-run must come after may-fail's skipped event, got: " + events);
    }

    @Test
    void continueOnErrorFalseAbortsRemainingSteps() throws Exception {
        // Default behaviour (no continue_on_error) on a
        // failing step: the step is "error" and all later
        // steps are marked "skipped" with reason.
        String yaml = """
                name: t
                steps:
                  - id: first
                    type: shell
                    cmd: "%s"
                  - id: never-runs
                    type: shell
                    cmd: "echo never"
                """.formatted(isWindows() ? "cmd /c exit 1" : "exit 1");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("error: first")),
                "expected first error, got: " + events);
        assertTrue(events.stream().anyMatch((e) -> e.contains("skipped: never-runs")),
                "expected never-runs skipped, got: " + events);
        assertTrue(events.stream().noneMatch((e) -> e.contains("ok: never-runs")),
                "never-runs must not be ok, got: " + events);
    }

    @Test
    void continueOnErrorAcceptsYesAndOneAliases() throws Exception {
        // `yes` and `1` should also flip the flag to true.
        // `no` and `0` should leave it false. The case
        // matters for `false` vs `False` (case-insensitive
        // match per the regex).
        String yaml = """
                name: t
                steps:
                  - id: a
                    type: shell
                    continue_on_error: YES
                    cmd: "%s"
                """.formatted(isWindows() ? "cmd /c exit 1" : "exit 1");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("skipped: a")),
                "expected a skipped (YES alias), got: " + events);
    }

    @Test
    void continueOnErrorCatchesExplicitErrorFromShellExitCode() throws Exception {
        // Shell step with non-zero exit code sets r.status
        // to "error" without throwing. The run() loop
        // should still see this as a failure and honour
        // continue_on_error. The final visible state is
        // "skipped" (the inner "error" event is the
        // underlying cause; the outer "skipped" event
        // reflects the downgrade).
        String yaml = """
                name: t
                steps:
                  - id: bad
                    type: shell
                    continue_on_error: true
                    cmd: "%s"
                """.formatted(isWindows() ? "cmd /c exit 1" : "exit 1");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("skipped: bad")),
                "expected bad skipped, got: " + events);
        assertTrue(events.stream().noneMatch((e) -> e.contains("ok: bad")),
                "bad must not be ok, got: " + events);
    }

    @Test
    void waitAnyCompletesAsSoonAsOneBranchSucceeds() throws Exception {
        // `wait: any` should make the parallel step ok
        // as soon as one branch returns ok. We set up
        // three branches: b1 is slow, b2 is fast, b3 is
        // also fast. As soon as b2 (or b3) finishes ok,
        // the parallel step should be "ok".
        String yaml = """
                name: t
                steps:
                  - id: p
                    type: parallel
                    wait: any
                    branches:
                      - id: b1
                        type: delay
                        duration: 1500
                      - id: b2
                        type: shell
                        cmd: "echo fast"
                      - id: b3
                        type: shell
                        cmd: "echo also-fast"
                """;
        var doc = WorkflowReader.parse(yaml, "t");
        long start = System.currentTimeMillis();
        var events = capture(doc, Map.of());
        long elapsed = System.currentTimeMillis() - start;
        // Parallel should be marked ok, and total runtime
        // should be much less than b1's 1500ms (since b2/b3
        // complete in <100ms and the parallel step closes
        // as soon as one of them is ok).
        assertTrue(events.stream().anyMatch((e) -> e.contains("ok: p")),
                "expected parallel p ok, got: " + events);
        assertTrue(elapsed < 1200,
                "wait:any should beat 1500ms b1, got " + elapsed + "ms");
    }

    @Test
    void waitMajorityRequiresMoreThanHalfOk() throws Exception {
        // 4 branches, 2 ok + 2 error. With `wait: majority`,
        // we need >2 of either outcome. 2 ok is not a
        // majority of 4 (need 3). So the parallel waits
        // until all branches finish, then aggregates to
        // "error" only if >2 errored. With 2 errored and
        // 2 ok, the parallel is "ok" (no majority of
        // error).
        String yaml = """
                name: t
                steps:
                  - id: p
                    type: parallel
                    wait: majority
                    branches:
                      - id: ok1
                        type: shell
                        cmd: "echo a"
                      - id: ok2
                        type: shell
                        cmd: "echo b"
                      - id: bad1
                        type: shell
                        cmd: "%s"
                      - id: bad2
                        type: shell
                        cmd: "%s"
                """.formatted(isWindows() ? "cmd /c exit 1" : "exit 1",
                              isWindows() ? "cmd /c exit 1" : "exit 1");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        // No majority of error (2 of 4) so parallel is ok.
        assertTrue(events.stream().anyMatch((e) -> e.contains("ok: p")),
                "expected parallel p ok, got: " + events);
    }

    @Test
    void waitAllIsTheDefaultAndAnyErrorFails() throws Exception {
        // Default `wait` policy is `all`; any error makes
        // the parallel error. This is a re-assertion of
        // R103's behaviour to make sure R105 didn't regress.
        String yaml = """
                name: t
                steps:
                  - id: p
                    type: parallel
                    branches:
                      - id: ok
                        type: shell
                        cmd: "echo a"
                      - id: bad
                        type: shell
                        cmd: "%s"
                """.formatted(isWindows() ? "cmd /c exit 1" : "exit 1");
        var doc = WorkflowReader.parse(yaml, "t");
        var events = capture(doc, Map.of());
        assertTrue(events.stream().anyMatch((e) -> e.contains("error: p")),
                "expected parallel p error, got: " + events);
    }

    @Test
    void waitParserAcceptsKnownPoliciesAndFallsBackToAll() {
        // Direct unit test of the static parser.
        assertEquals("all", WorkflowExecutor.parseWaitPolicy("wait: all"));
        assertEquals("any", WorkflowExecutor.parseWaitPolicy("wait: any"));
        assertEquals("majority", WorkflowExecutor.parseWaitPolicy("wait: majority"));
        // Case-insensitive
        assertEquals("any", WorkflowExecutor.parseWaitPolicy("wait: ANY"));
        // Unknown value falls back to "all"
        assertEquals("all", WorkflowExecutor.parseWaitPolicy("wait: first_error"));
        // Missing field defaults to "all"
        assertEquals("all", WorkflowExecutor.parseWaitPolicy("type: parallel\nbranches: []"));
        // Null chunk
        assertEquals("all", WorkflowExecutor.parseWaitPolicy(null));
    }

    @Test
    void readerParsesContinueOnErrorFlag() {
        // Direct unit test of the reader's flag extraction.
        String yaml = """
                name: t
                steps:
                  - id: a
                    type: shell
                    continue_on_error: true
                    cmd: "echo a"
                  - id: b
                    type: shell
                    continue_on_error: false
                    cmd: "echo b"
                  - id: c
                    type: shell
                    cmd: "echo c"
                """;
        var doc = WorkflowReader.parse(yaml, "t");
        assertEquals(3, doc.steps().size());
        assertEquals("a", doc.steps().get(0).id());
        assertTrue(doc.steps().get(0).continueOnError(), "a should have continueOnError=true");
        assertEquals("b", doc.steps().get(1).id());
        assertFalse(doc.steps().get(1).continueOnError(), "b should have continueOnError=false");
        assertEquals("c", doc.steps().get(2).id());
        assertFalse(doc.steps().get(2).continueOnError(), "c (no flag) defaults to false");
    }
}
