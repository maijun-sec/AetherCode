package org.aethercode.core.workflow;

import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R375.3: tests for the {@code parallelism:} field on
 * {@code kind: agent} / {@code kind: skill} workflow steps.
 *
 * <p>Scope:
 * <ul>
 *   <li>parallelism default = 1 (back-compat with R370 / R371).</li>
 *   <li>parallelism=1 still runs the single-invoke path
 *       (no parallel fork-join).</li>
 *   <li>parallelism=3 fans out 3 invocations and waits
 *       for all to complete; the captured text from each
 *       replica is concatenated with a divider header.</li>
 *   <li>parallelism=3 with quota=1: the executor emits a
 *       {@code workflow_step_warning} SideNote before
 *       fanning out (the underlying limiter is what
 *       ultimately enforces the cap).</li>
 *   <li>{{index}} / {{total}} in the prompt substitute
 *       per-replica so the user can split work.</li>
 *   <li>unparseable parallelism field falls back to 1
 *       (graceful degradation, not an error).</li>
 * </ul>
 */
class WorkflowExecutorR375Test {

    /** Tiny helper that wraps an invoker + captures every
     *  event the step forwards to the sink. Lets tests
     *  assert on warning SideNotes and replica tags. */
    private static final class Harness {
        final List<String> captured = new ArrayList<>();
        final ConcurrentLinkedQueue<String> warnings = new ConcurrentLinkedQueue<>();
        final AtomicInteger invokeCount = new AtomicInteger();
        WorkflowExecutor.SkillInvoker invoker;
        Runnable onInvoke;

        Harness(WorkflowExecutor.SkillInvoker invoker) {
            this.invoker = invoker;
        }

        Consumer<StreamEvent> sink() {
            return ev -> {
                captured.add(ev.getClass().getSimpleName() + ":" + ev.toString());
                if (ev instanceof StreamEvent.SideNote sn) {
                    if ("workflow_step_warning".equals(sn.kind())) {
                        warnings.add(sn.message());
                    }
                }
            };
        }
    }

    @Test
    void parallelism_defaultIsOne() {
        // No parallelism: field. The executor still runs
        // the step (single-invoke path).
        AtomicInteger calls = new AtomicInteger();
        WorkflowExecutor.SkillInvoker inv = (kind, name, prompt, model, ev) -> {
            calls.incrementAndGet();
            return "single-shot result";
        };
        WorkflowReader.WorkflowDoc doc = parse(
                "- id: step1\n" +
                "  type: agent\n" +
                "  name: pm\n" +
                "  prompt: hi\n");
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "run-default-1", ev -> {}, inv);
        exec.run();
        assertEquals("ok", exec.results().get("step1").status);
        assertEquals(1, calls.get(),
                "no parallelism: field → exactly 1 invocation");
    }

    @Test
    void parallelism_three_fansOut_andAggregates() {
        AtomicInteger calls = new AtomicInteger();
        List<String> seenPrompts = new ArrayList<>();
        WorkflowExecutor.SkillInvoker inv = (kind, name, prompt, model, ev) -> {
            calls.incrementAndGet();
            synchronized (seenPrompts) { seenPrompts.add(prompt); }
            return "out-" + calls.get();
        };
        WorkflowReader.WorkflowDoc doc = parse(
                "- id: step1\n" +
                "  type: agent\n" +
                "  name: pm\n" +
                "  prompt: hello {{index}}/{{total}}\n" +
                "  parallelism: 3\n");
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "run-parallel-3", ev -> {}, inv);
        exec.run();
        assertEquals("ok", exec.results().get("step1").status,
                "3 replicas all ok → step ok");
        assertEquals(3, calls.get(),
                "parallelism=3 should invoke 3 times");
        assertEquals(3, seenPrompts.size());
        // Each replica must have its {{index}}/{{total}}
        // substituted to a unique 1-based value.
        assertTrue(seenPrompts.contains("hello 1/3"), "replica 1 prompt");
        assertTrue(seenPrompts.contains("hello 2/3"), "replica 2 prompt");
        assertTrue(seenPrompts.contains("hello 3/3"), "replica 3 prompt");
        // stdout should carry all three replicas' outputs,
        // each prefixed with a "--- replica i/N ---" header.
        String stdout = exec.results().get("step1").stdout;
        assertTrue(stdout.contains("--- replica 1/3 ---"));
        assertTrue(stdout.contains("--- replica 2/3 ---"));
        assertTrue(stdout.contains("--- replica 3/3 ---"));
        assertTrue(stdout.contains("out-1"));
        assertTrue(stdout.contains("out-2"));
        assertTrue(stdout.contains("out-3"));
    }

    @Test
    void parallelism_three_replicaFailureMakesStepError_butOthersStillRun() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowExecutor.SkillInvoker inv = (kind, name, prompt, model, ev) -> {
            int n = calls.incrementAndGet();
            if (n == 2) throw new RuntimeException("replica-2 boom");
            return "ok-" + n;
        };
        WorkflowReader.WorkflowDoc doc = parse(
                "- id: step1\n" +
                "  type: agent\n" +
                "  name: pm\n" +
                "  prompt: hi\n" +
                "  parallelism: 3\n");
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "run-partial-fail", ev -> {}, inv);
        exec.run();
        WorkflowExecutor.StepResult sr = exec.results().get("step1");
        assertEquals("error", sr.status,
                "any replica failing → step error");
        // stdout still carries the partial results so the
        // user can see which replica failed.
        assertTrue(sr.stdout.contains("ok-1"));
        assertTrue(sr.stdout.contains("ok-3"));
        assertTrue(sr.stdout.contains("replica 2/3 failed"),
                "failing replica's error text appears in stdout: " + sr.stdout);
    }

    @Test
    void parallelism_exceedsQuota_emitsWarning() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowExecutor.SkillInvoker inv = (kind, name, prompt, model, ev) -> {
            calls.incrementAndGet();
            return "x";
        };
        WorkflowReader.WorkflowDoc doc = parse(
                "- id: step1\n" +
                "  type: agent\n" +
                "  name: pm\n" +
                "  prompt: hi\n" +
                "  parallelism: 5\n");
        // quota=1 for "pm"; parallelism=5 > 1 → warning.
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "run-quota-warn", ev -> {},
                inv,
                null /* agentModelLookup */,
                role -> {
                    if ("pm".equals(role)) return 1;
                    return null;
                });
        // Also wire a sink that captures warnings so we
        // can assert on the SideNote text.
        List<StreamEvent> events = new ArrayList<>();
        WorkflowExecutor execWithSink = new WorkflowExecutor(
                doc, Map.of(), "run-quota-warn-events", events::add, inv,
                null, role -> "pm".equals(role) ? 1 : null);
        execWithSink.run();

        boolean sawWarning = events.stream()
                .filter(e -> e instanceof StreamEvent.SideNote)
                .map(e -> (StreamEvent.SideNote) e)
                .anyMatch(sn -> "workflow_step_warning".equals(sn.kind())
                        && sn.message().contains("parallelism 5")
                        && sn.message().contains("quota 1")
                        && sn.message().contains("pm"));
        assertTrue(sawWarning,
                "parallelism > quota must emit a workflow_step_warning SideNote — events: " + events);

        // The fan-out still happens — the warning is
        // advisory, not blocking.
        assertEquals(5, calls.get(),
                "fan-out proceeds even when parallelism > quota (limiter is the actual gate)");
    }

    @Test
    void parallelism_unparseableFallsBackToOne() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowExecutor.SkillInvoker inv = (kind, name, prompt, model, ev) -> {
            calls.incrementAndGet();
            return "x";
        };
        // Garbage value: not a number. The executor must
        // not crash — it falls back to parallelism=1.
        WorkflowReader.WorkflowDoc doc = parse(
                "- id: step1\n" +
                "  type: agent\n" +
                "  name: pm\n" +
                "  prompt: hi\n" +
                "  parallelism: not-a-number\n");
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "run-bad-parallelism", ev -> {}, inv);
        exec.run();
        assertEquals("ok", exec.results().get("step1").status);
        assertEquals(1, calls.get(),
                "unparseable parallelism → single invocation (graceful)");
    }

    @Test
    void parallelism_oneStillUsesSingleInvokePath() {
        // Sanity: parallelism=1 is the explicit form of
        // the default — it must NOT fan out.
        AtomicInteger calls = new AtomicInteger();
        WorkflowExecutor.SkillInvoker inv = (kind, name, prompt, model, ev) -> {
            calls.incrementAndGet();
            return "x";
        };
        WorkflowReader.WorkflowDoc doc = parse(
                "- id: step1\n" +
                "  type: agent\n" +
                "  name: pm\n" +
                "  prompt: hi\n" +
                "  parallelism: 1\n");
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "run-explicit-one", ev -> {}, inv);
        exec.run();
        assertEquals(1, calls.get());
        // No replica headers in stdout (single path).
        String stdout = exec.results().get("step1").stdout;
        assertEquals("x", stdout,
                "single-invoke path captures the invoker's return value as stdout");
        assertFalse(stdout.contains("--- replica"),
                "single-invoke path must NOT produce replica divider headers");
    }

    @Test
    void parseParallelism_helperHandlesEdgeCases() {
        assertEquals(1, WorkflowExecutor.parseParallelism(null));
        assertEquals(1, WorkflowExecutor.parseParallelism(""));
        assertEquals(1, WorkflowExecutor.parseParallelism("name: pm\nprompt: hi\n"));
        assertEquals(3, WorkflowExecutor.parseParallelism(
                "name: pm\nprompt: hi\nparallelism: 3\n"));
        assertEquals(8, WorkflowExecutor.parseParallelism(
                "parallelism: 8\nname: pm\nprompt: hi\n"));
        // Comment lines count (the regex is multiline +
        // comment-tolerant via the existing # ... trimming
        // done elsewhere; we only enforce that the field is
        // numeric).
        assertEquals(1, WorkflowExecutor.parseParallelism(
                "parallelism: -3\nname: pm\n"), 1,
                "negative parallelism falls back to 1");
        assertEquals(1, WorkflowExecutor.parseParallelism(
                "parallelism: 0\nname: pm\n"), 1,
                "zero parallelism falls back to 1");
    }

    // ----- helpers -----

    /** Parse a single-step workflow doc. The reader's
     *  parser tolerates a top-level list with `- id: ...`
     *  entries; the body is everything between the
     *  `id:` line and the next `- id:` line. */
    private static WorkflowReader.WorkflowDoc parse(String body) {
        // The reader expects top-level steps to be indented
        // by exactly 2 spaces (it's a YAML convention the
        // executor relies on for nested vs top-level
        // disambiguation). Each `- id:` line in body must
        // start with two spaces; we just prepend `  ` to
        // each line so callers don't have to remember.
        String indented = body.replaceAll("(?m)^", "  ");
        String raw = "steps:\n" + indented;
        return WorkflowReader.parse(raw, "test-workflow");
    }
}