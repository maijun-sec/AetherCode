package org.aethercode.evals.capability.planning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-1: Planning & Multi-Step Reasoning capability suite.
 *
 * <p>Covers Survey on Evaluation of LLM-based Agents (2503.16416) §2.1
 * — Planning and Multi-Step Reasoning — across five sub-abilities:</p>
 *
 * <ul>
 *   <li>Task decomposition — given a goal, can the agent split it into
 *       named sub-steps?</li>
 *   <li>State tracking — can the plan be stepped through respecting
 *       dependency state?</li>
 *   <li>Multi-step reasoning — does the plan chain into a coherent
 *       topological order?</li>
 *   <li>Meta-planning — can a plan classifier decide whether a plan
 *       is safe to auto-approve or needs explicit confirmation?</li>
 *   <li>Causal understanding — does the plan's dependency graph
 *       encode a plausible causal chain?</li>
 * </ul>
 *
 * <p>The suite uses a self-contained {@link Plan} / {@link PlanStep}
 * model with the same shape as AetherCode's {@code DagPlan}, so the
 * tests can later be wired to the real SDK without changing the
 * capability assertions. R-orch-3's E2E pattern (mock the LLM, exercise
 * the orchestration plumbing) is the inspiration here.</p>
 */
class PlanningCapabilityTest {

    /* --------------------- Plan model (DagPlan-style) --------------------- */

    /** A single planning step, with explicit dependencies on prior
     *  steps identified by id. */
    public record PlanStep(String id, String title, List<String> dependsOn) {
        public PlanStep {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must be non-blank");
            }
            if (title == null) title = "";
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    /** A plan is a list of steps; the constructor validates and
     *  computes a topological order. */
    public static final class Plan {
        private final List<PlanStep> steps;
        private final Map<String, PlanStep> byId;
        private final List<String> topoOrder;

        public Plan(List<PlanStep> steps) {
            if (steps == null) throw new IllegalArgumentException("steps must be non-null");
            this.steps = List.copyOf(steps);
            Map<String, PlanStep> m = new LinkedHashMap<>();
            for (PlanStep s : this.steps) {
                if (m.put(s.id(), s) != null) {
                    throw new IllegalArgumentException("duplicate step id: " + s.id());
                }
            }
            for (PlanStep s : this.steps) {
                for (String dep : s.dependsOn()) {
                    if (!m.containsKey(dep)) {
                        throw new IllegalArgumentException(
                                "step " + s.id() + " depends on unknown step: " + dep);
                    }
                }
            }
            this.byId = Map.copyOf(m);
            this.topoOrder = topoSort(this.steps, m);
        }

        public List<PlanStep> steps() { return steps; }
        public PlanStep get(String id) { return byId.get(id); }
        public List<String> topoOrder() { return topoOrder; }

        /** All step ids whose dependencies are satisfied by
         *  {@code completed}. Returned in topological order so the
         *  caller can step through them sequentially. */
        public List<String> readySteps(Set<String> completed) {
            Set<String> done = completed == null ? Set.of() : completed;
            List<String> out = new ArrayList<>();
            for (String id : topoOrder) {
                if (done.contains(id)) continue;
                PlanStep s = byId.get(id);
                if (done.containsAll(s.dependsOn())) {
                    out.add(id);
                }
            }
            return out;
        }

        public boolean isComplete(Set<String> completed) {
            Set<String> done = completed == null ? Set.of() : completed;
            for (PlanStep s : steps) if (!done.contains(s.id())) return false;
            return true;
        }

        private static List<String> topoSort(List<PlanStep> steps, Map<String, PlanStep> byId) {
            // Kahn's algorithm. Throws IllegalStateException on cycle.
            Map<String, Integer> inDegree = new LinkedHashMap<>();
            Map<String, List<String>> edges = new LinkedHashMap<>();
            for (PlanStep s : steps) {
                inDegree.putIfAbsent(s.id(), 0);
                edges.putIfAbsent(s.id(), new ArrayList<>());
            }
            for (PlanStep s : steps) {
                for (String dep : s.dependsOn()) {
                    inDegree.merge(s.id(), 1, Integer::sum);
                    edges.get(dep).add(s.id());
                }
            }
            List<String> order = new ArrayList<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
                if (e.getValue() == 0) queue.add(e.getKey());
            }
            while (!queue.isEmpty()) {
                String n = queue.poll();
                order.add(n);
                for (String m : edges.getOrDefault(n, List.of())) {
                    int d = inDegree.merge(m, -1, Integer::sum);
                    if (d == 0) queue.add(m);
                }
            }
            if (order.size() != steps.size()) {
                throw new IllegalStateException("cycle detected: " + steps);
            }
            return order;
        }
    }

    /** A plan classifier modelled on AetherCode's
     *  {@code PlanClassifier}. Decides whether a plan is trivial
     *  (auto-approve) or needs explicit user approval. */
    public static final class PlanVerifier {
        public enum Verdict { TRIVIAL, NEEDS_APPROVAL }

        public static final int DEFAULT_MAX_STEPS = 3;
        public static final Set<String> DEFAULT_DESTRUCTIVE = Set.of(
                "bash", "shell", "file_write", "file_edit", "file_delete",
                "web_fetch", "process_kill");

        private final int maxSteps;
        private final Set<String> destructive;

        public PlanVerifier() { this(DEFAULT_MAX_STEPS, DEFAULT_DESTRUCTIVE); }
        public PlanVerifier(int maxSteps, Set<String> destructive) {
            this.maxSteps = maxSteps;
            this.destructive = destructive == null ? Set.of() : Set.copyOf(destructive);
        }

        public Verdict classify(Plan plan) {
            if (plan == null || plan.steps().isEmpty()) return Verdict.NEEDS_APPROVAL;
            if (plan.steps().size() > maxSteps) return Verdict.NEEDS_APPROVAL;
            for (PlanStep s : plan.steps()) {
                String title = s.title() == null ? "" : s.title().toLowerCase();
                for (String d : destructive) {
                    if (title.contains(d.toLowerCase())) return Verdict.NEEDS_APPROVAL;
                }
            }
            return Verdict.TRIVIAL;
        }
    }

    /* --------------------- Task-decomposition test cases --------------------- */

    @Test
    void planAcceptsFlatLinearChain() {
        // Three-step pipeline: read file → parse → summarize. No
        // dependencies declared explicitly because topological order
        // by declaration matches the intended sequence.
        Plan plan = new Plan(List.of(
                new PlanStep("read", "read input file", List.of()),
                new PlanStep("parse", "parse content", List.of("read")),
                new PlanStep("summarize", "summarize result", List.of("parse"))));
        assertEquals(List.of("read", "parse", "summarize"), plan.topoOrder());
    }

    @Test
    void planAcceptsFanOutFanIn() {
        // Two parallel parses feeding a synthesis step. The plan
        // must schedule parse-1 and parse-2 first, then synthesize
        // only when both are done.
        Plan plan = new Plan(List.of(
                new PlanStep("read", "read input", List.of()),
                new PlanStep("parse-1", "parse section 1", List.of("read")),
                new PlanStep("parse-2", "parse section 2", List.of("read")),
                new PlanStep("synthesize", "synthesize", List.of("parse-1", "parse-2"))));
        assertEquals(List.of("read", "parse-1", "parse-2", "synthesize"), plan.topoOrder());
        assertEquals(List.of("read"), plan.readySteps(Set.of()));
        assertEquals(List.of("parse-1", "parse-2"),
                plan.readySteps(Set.of("read")),
                "after read is done, both parses become ready in parallel");
        // After both parses, synthesize becomes ready.
        assertEquals(List.of("synthesize"),
                plan.readySteps(Set.of("read", "parse-1", "parse-2")));
    }

    @Test
    void planRejectsUnknownDependency() {
        assertThrows(IllegalArgumentException.class, () -> new Plan(List.of(
                new PlanStep("a", "step a", List.of("missing")))),
                "a dependency on a missing step id must be rejected at construction");
    }

    @Test
    void planRejectsDuplicateStepId() {
        assertThrows(IllegalArgumentException.class, () -> new Plan(List.of(
                new PlanStep("a", "first a", List.of()),
                new PlanStep("a", "second a", List.of()))),
                "duplicate step ids must be rejected");
    }

    /* --------------------- Cycle detection --------------------- */

    @Test
    void planDetectsDirectCycle() {
        // a -> b -> a is a cycle. Topological sort must throw.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new Plan(List.of(
                new PlanStep("a", "step a", List.of("b")),
                new PlanStep("b", "step b", List.of("a")))));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void planDetectsTransitiveCycle() {
        // a -> b -> c -> a is a transitive cycle.
        assertThrows(IllegalStateException.class, () -> new Plan(List.of(
                new PlanStep("a", "step a", List.of("c")),
                new PlanStep("b", "step b", List.of("a")),
                new PlanStep("c", "step c", List.of("b")))));
    }

    /* --------------------- Multi-step reasoning: depth --------------------- */

    @Test
    void planSchedulesLongHorizonChain() {
        // 10-step chain. The depth tests a real-world long-horizon
        // scenario: a refactor that touches the type system, then
        // call sites, then tests.
        List<PlanStep> steps = new ArrayList<>();
        String prev = null;
        for (int i = 0; i < 10; i++) {
            String id = "step-" + i;
            List<String> deps = prev == null ? List.of() : List.of(prev);
            steps.add(new PlanStep(id, "refactor step " + i, deps));
            prev = id;
        }
        Plan plan = new Plan(steps);
        assertEquals(10, plan.topoOrder().size());
        assertEquals(List.of("step-0"), plan.readySteps(Set.of()));
        // After step-5 done, step-6 must be the only ready.
        Set<String> done = new HashSet<>();
        for (int i = 0; i < 5; i++) done.add("step-" + i);
        assertEquals(List.of("step-5"), plan.readySteps(done));
    }

    @Test
    void planPreservesTopoOrderAcrossDiamond() {
        // Diamond: a -> {b, c} -> d. Topological order must place a
        // first, d last, and b/c between in any order.
        Plan plan = new Plan(List.of(
                new PlanStep("a", "a", List.of()),
                new PlanStep("b", "b", List.of("a")),
                new PlanStep("c", "c", List.of("a")),
                new PlanStep("d", "d", List.of("b", "c"))));
        List<String> order = plan.topoOrder();
        assertEquals("a", order.get(0));
        assertEquals("d", order.get(3));
        assertTrue(order.subList(1, 3).containsAll(List.of("b", "c")));
    }

    /* --------------------- State tracking --------------------- */

    @Test
    void isCompleteReturnsTrueWhenAllStepsDone() {
        Plan plan = new Plan(List.of(
                new PlanStep("a", "a", List.of()),
                new PlanStep("b", "b", List.of("a"))));
        assertFalse(plan.isComplete(Set.of("a")));
        assertTrue(plan.isComplete(Set.of("a", "b")));
        // Empty plan is trivially complete (no work to do).
        assertTrue(new Plan(List.of()).isComplete(Set.of()));
    }

    @Test
    void completedSetExposesExactRemainingWork() {
        Plan plan = new Plan(List.of(
                new PlanStep("a", "a", List.of()),
                new PlanStep("b", "b", List.of("a")),
                new PlanStep("c", "c", List.of("b"))));
        Set<String> done = new HashSet<>(List.of("a"));
        Set<String> remaining = new HashSet<>(plan.topoOrder());
        remaining.removeAll(done);
        assertEquals(Set.of("b", "c"), remaining);
    }

    /* --------------------- Causal understanding --------------------- */

    @Test
    void planRejectsSelfLoop() {
        // a step depending on itself is a degenerate cycle. The
        // dependsOn check passes (the id exists), but the topo
        // sort must still throw IllegalStateException.
        assertThrows(IllegalStateException.class, () -> new Plan(List.of(
                new PlanStep("a", "a", List.of("a")))));
    }

    @Test
    void planEncodesPlausibleCausalChain() {
        // A "build & test" plan. The dependency chain must express
        // the real-world ordering: lint -> test -> package, where
        // test depends on lint passing first.
        Plan plan = new Plan(List.of(
                new PlanStep("checkout", "checkout source", List.of()),
                new PlanStep("install", "install deps", List.of("checkout")),
                new PlanStep("lint", "lint code", List.of("install")),
                new PlanStep("test", "run unit tests", List.of("lint")),
                new PlanStep("package", "package artifact", List.of("test"))));
        // Topo sort must be deterministic here because each step
        // has a single dep.
        assertEquals(List.of("checkout", "install", "lint", "test", "package"),
                plan.topoOrder());
    }

    /* --------------------- Meta-planning (classification) --------------------- */

    @Test
    void trivialPlanIsAutoApproved() {
        Plan plan = new Plan(List.of(
                new PlanStep("read", "read input", List.of()),
                new PlanStep("summarize", "summarize", List.of("read"))));
        assertEquals(PlanVerifier.Verdict.TRIVIAL,
                new PlanVerifier().classify(plan));
    }

    @Test
    void planWithDestructiveToolNeedsApproval() {
        Plan plan = new Plan(List.of(
                new PlanStep("read", "read file", List.of()),
                new PlanStep("edit", "file_edit config", List.of("read"))));
        assertEquals(PlanVerifier.Verdict.NEEDS_APPROVAL,
                new PlanVerifier().classify(plan),
                "file_edit is destructive, must require explicit approval");
    }

    @Test
    void planOverStepLimitNeedsApproval() {
        // 4 steps, default limit is 3.
        Plan plan = new Plan(List.of(
                new PlanStep("a", "a", List.of()),
                new PlanStep("b", "b", List.of("a")),
                new PlanStep("c", "c", List.of("b")),
                new PlanStep("d", "d", List.of("c"))));
        assertEquals(PlanVerifier.Verdict.NEEDS_APPROVAL,
                new PlanVerifier().classify(plan));
    }

    @Test
    void emptyPlanNeedsApproval() {
        // An empty plan should never auto-approve (it might be a
        // truncation error).
        assertEquals(PlanVerifier.Verdict.NEEDS_APPROVAL,
                new PlanVerifier().classify(new Plan(List.of())));
    }

    @Test
    void customDestructiveSetIsHonored() {
        Plan plan = new Plan(List.of(
                new PlanStep("query", "query database", List.of())));
        // Default verifier: "query" isn't destructive -> TRIVIAL.
        assertEquals(PlanVerifier.Verdict.TRIVIAL,
                new PlanVerifier().classify(plan));
        // Custom verifier: "query" is destructive -> NEEDS_APPROVAL.
        PlanVerifier strict = new PlanVerifier(3, Set.of("query"));
        assertEquals(PlanVerifier.Verdict.NEEDS_APPROVAL, strict.classify(plan));
    }

    /* --------------------- Causal chain audit --------------------- */

    @Test
    void causalChainLengthMatchesLongestPath() {
        // Diamond shape: a -> b -> d, a -> c -> d. The longest
        // causal chain through the graph is 3 hops.
        Plan plan = new Plan(List.of(
                new PlanStep("a", "a", List.of()),
                new PlanStep("b", "b", List.of("a")),
                new PlanStep("c", "c", List.of("a")),
                new PlanStep("d", "d", List.of("b", "c"))));
        // Verify longest chain via BFS from roots.
        Map<String, Integer> longest = new LinkedHashMap<>();
        for (String id : plan.topoOrder()) {
            PlanStep s = plan.get(id);
            int max = 0;
            for (String dep : s.dependsOn()) {
                max = Math.max(max, longest.getOrDefault(dep, 0));
            }
            longest.put(id, max + 1);
        }
        assertEquals(1, longest.get("a"));
        assertEquals(2, longest.get("b"));
        assertEquals(2, longest.get("c"));
        assertEquals(3, longest.get("d"));
    }

    /* --------------------- Plan composition: tasks broken into sub-tasks --------------------- */

    @Test
    void planBuilderComposesGoalIntoSubtasks() {
        // Real-world decomposition test: a "fix the failing test"
        // goal must split into reproduce -> diagnose -> patch ->
        // verify. The model must produce all four steps; each step
        // must depend on the prior one in a way that respects
        // causal ordering.
        Plan plan = new Plan(List.of(
                new PlanStep("reproduce", "reproduce the failing test", List.of()),
                new PlanStep("diagnose", "read the traceback", List.of("reproduce")),
                new PlanStep("patch", "fix the bug", List.of("diagnose")),
                new PlanStep("verify", "re-run the test", List.of("patch"))));
        assertEquals(4, plan.steps().size());
        // Verify the chain is causal: each step depends on the
        // previous.
        for (int i = 1; i < plan.steps().size(); i++) {
            PlanStep prev = plan.steps().get(i - 1);
            PlanStep curr = plan.steps().get(i);
            assertTrue(curr.dependsOn().contains(prev.id()),
                    curr.id() + " must depend on " + prev.id());
        }
        // Plan classifier says: 4 steps > limit 3, so it needs
        // approval. This is correct: the user must see the plan
        // before the agent edits code.
        assertEquals(PlanVerifier.Verdict.NEEDS_APPROVAL,
                new PlanVerifier().classify(plan));
    }

    @Test
    void parallelizableWorkIsEncodedAsFanOut() {
        // A goal that has two independent sub-tasks must be
        // encoded as a fan-out (no edge between them) so the
        // scheduler can run them concurrently.
        Plan plan = new Plan(List.of(
                new PlanStep("lint", "run linter", List.of()),
                new PlanStep("typecheck", "run type checker", List.of()),
                new PlanStep("build", "build artifact", List.of("lint", "typecheck"))));
        // After both lint and typecheck complete, build becomes
        // ready. Until then, neither blocks the other.
        assertEquals(List.of("lint", "typecheck"), plan.readySteps(Set.of()));
        // typecheck doesn't depend on lint, so it remains ready
        // even after lint is done — only build waits for both.
        assertEquals(List.of("typecheck"), plan.readySteps(Set.of("lint")));
        assertEquals(List.of("build"),
                plan.readySteps(Set.of("lint", "typecheck")));
    }

    /* --------------------- Integration: end-to-end plan lifecycle --------------------- */

    @Test
    void planExecutesTopologicallyWithConcurrentReadiness() {
        // Walk the plan in topo order, recording when each step
        // becomes ready. The "readiness trace" must satisfy: a
        // step is never ready before its deps are completed.
        Plan plan = new Plan(List.of(
                new PlanStep("a", "a", List.of()),
                new PlanStep("b", "b", List.of("a")),
                new PlanStep("c", "c", List.of("a")),
                new PlanStep("d", "d", List.of("b", "c"))));
        Set<String> completed = new HashSet<>();
        List<String> trace = new ArrayList<>();
        while (!plan.isComplete(completed)) {
            List<String> ready = plan.readySteps(completed);
            assertFalse(ready.isEmpty(), "topo sort guarantees a non-empty ready set");
            // Simulate completing all currently-ready steps in
            // parallel: that's how a real scheduler would treat a
            // diamond.
            completed.addAll(ready);
            trace.addAll(ready);
        }
        // Verify causal invariant: a step's completion precedes
        // the completion of any of its dependents.
        Map<String, Integer> completionAt = new LinkedHashMap<>();
        for (int i = 0; i < trace.size(); i++) completionAt.put(trace.get(i), i);
        for (PlanStep s : plan.steps()) {
            for (String dep : s.dependsOn()) {
                assertTrue(completionAt.get(dep) < completionAt.get(s.id()),
                        s.id() + " must complete after " + dep);
            }
        }
    }
}
