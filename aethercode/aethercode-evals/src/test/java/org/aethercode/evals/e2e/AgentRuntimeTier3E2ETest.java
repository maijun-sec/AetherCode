package org.aethercode.evals.e2e;

import org.aethercode.orchestration.multiagent.AgentFn;
import org.aethercode.orchestration.multiagent.CritiqueStrategy;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.orchestration.perf.ActionCache;
import org.aethercode.orchestration.perf.CostCeiling;
import org.aethercode.orchestration.perf.TokenCounter;
import org.aethercode.orchestration.planner.AgentArchitectureSelector;
import org.aethercode.orchestration.planner.AgentArchitectureSelector.Architecture;
import org.aethercode.orchestration.planner.AgentArchitectureSelector.Recommendation;
import org.aethercode.orchestration.planner.AgentArchitectureSelector.TaskFeatures;
import org.aethercode.orchestration.planner.CapabilitySaturationDetector;
import org.aethercode.orchestration.planner.CapabilitySaturationDetector.RunResult;
import org.aethercode.orchestration.planner.CentralPlanner;
import org.aethercode.orchestration.planner.CentralPlanner.AgentSpec;
import org.aethercode.orchestration.planner.CentralPlanner.Plan;
import org.aethercode.orchestration.planner.CentralPlanner.PlanResult;
import org.aethercode.orchestration.plan.HierarchicalExecutor;
import org.aethercode.orchestration.plan.GlobalPlan;
import org.aethercode.orchestration.plan.GlobalPlan.Skill;
import org.aethercode.orchestration.runtime.AgentRuntime;
import org.aethercode.orchestration.runtime.AgentRuntime.RuntimeResult;
import org.aethercode.orchestration.security.ByzantineDetector;
import org.aethercode.orchestration.security.ByzantineDetector.AgentAction;
import org.aethercode.orchestration.selfcorrect.RetryStrategy;
import org.aethercode.orchestration.selfcorrect.SelfCorrectionLoop;
import org.aethercode.orchestration.verifier.RedFlagDetector;
import org.aethercode.orchestration.verifier.RuleVerifier;
import org.aethercode.orchestration.verifier.Verifier;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that wire Tier-3 paper-compat implementations into
 * the real {@link AgentRuntime} business flow.
 * <p>
 * The shape is: front-end input → AgentRuntime.run (verifier → self-correct
 * → ensemble) → Tier-3 instances actually do work → final outcome is
 * correct. No mocks for the orchestration primitives; the LLM is
 * stubbed via {@link AgentFn} lambda.
 */
class AgentRuntimeTier3E2ETest {

    @Test
    void redFlagRejectsAndSelfCorrectRescues() {
        // Round 1: a too-short output fails RuleVerifier's min-length check.
        // Round 2: a "good" output passes.
        // This is the canonical "V → fail → self-correct → V → pass" flow.
        Verifier<String> v = new RuleVerifier("min-10",
            List.of(new RuleVerifier.Rule(
                RuleVerifier.Kind.MUST_MATCH, ".{10,}", "min 10 chars")));

        // RedFlagDetector is hooked BEFORE the verifier: outputs that
        // hit HIGH-severity flags should never even reach the verifier.
        RedFlagDetector redFlag = new RedFlagDetector();

        SelfCorrectionLoop<String> sc = new SelfCorrectionLoop<>(
            "sc",
            v,
            RetryStrategy.transform(s -> s + " [expanded]"),
            3
        );

        AgentRuntime<String> runtime = AgentRuntime.<String>builder()
            .name("red-flag-e2e")
            .verifier(v)
            .selfCorrect(sc)
            .build();

        // Action: short output, no red flags
        String action1 = "hi";
        assertFalse(redFlag.isRedFlagged(action1), "short but not a HIGH red flag yet");
        RuntimeResult<String> r1 = runtime.run(action1);
        assertEquals("self-corrected", r1.outcome(),
            "expected self-corrected after verifier fail");
        assertTrue(r1.action().length() >= 10, "expanded action should now pass: " + r1.action());

        // Action: empty output → HIGH red flag → real front-end would
        // short-circuit. Our runtime catches it via verifier failing.
        String action2 = "";
        assertTrue(redFlag.isRedFlagged(action2), "empty is a HIGH red flag");
        RuntimeResult<String> r2 = runtime.run(action2);
        // Self-correct expands "" → " [expanded]" → length 12 → passes
        assertEquals("self-corrected", r2.outcome());
        assertTrue(r2.action().length() >= 10);
    }

    @Test
    void architectureSelectorPicksStrategyAndRunsEnsemble() {
        // Front-end: "I have a tool-heavy parallelizable task at baseline
        // 0.3. Which multi-agent architecture should I run?"
        AgentArchitectureSelector sel = new AgentArchitectureSelector();
        TaskFeatures f = new TaskFeatures(
            /* parallelizable */ true,
            /* sequential */ false,
            /* toolHeavy */ true,
            /* dynamic */ false,
            /* singleAgentBaseline */ 0.3);
        Recommendation rec = sel.recommend(f);
        assertEquals(Architecture.HYBRID, rec.architecture());

        // Build the actual strategy from the recommendation and run it
        // through MultiAgentOrchestrator.
        // For HYBRID we need a verifier; use a trivial one for the E2E.
        Verifier<String> triviallyOk = new RuleVerifier("trivially-ok",
            List.of(new RuleVerifier.Rule(
                RuleVerifier.Kind.MUST_CONTAIN, "", "always pass")));
        org.aethercode.orchestration.planner.AgentArchitectureSelector.VerifierSupplier vs =
            new org.aethercode.orchestration.planner.AgentArchitectureSelector.VerifierSupplier() {
                @SuppressWarnings("unchecked")
                @Override
                public <T> Verifier<T> get() {
                    return (Verifier<T>) triviallyOk;
                }
            };
        MultiAgentOrchestrator<String> orch = new MultiAgentOrchestrator<>(
            "e2e-arch",
            List.of(
                (AgentFn<String>) (prompt, peers) -> "result-A",
                (AgentFn<String>) (prompt, peers) -> "result-B"
            ),
            sel.buildStrategy(rec, vs)
        );
        EnsembleResult<String> er = orch.run("test prompt");
        assertNotNull(er.winner());
    }

    @Test
    void capabilitySaturationGateBeforeMultiAgent() {
        // Front-end: "I have N single-agent runs. Should I escalate to
        // multi-agent?" Detector says yes if mean >= 0.45, no otherwise.
        CapabilitySaturationDetector det = new CapabilitySaturationDetector();
        // Scenario 1: high baseline → saturated → skip multi-agent
        var highReport = det.assess(List.of(
            new RunResult(0.50), new RunResult(0.55), new RunResult(0.60)));
        assertTrue(highReport.isSaturated());
        // Scenario 2: low baseline → not saturated → worth escalating
        var lowReport = det.assess(List.of(
            new RunResult(0.10), new RunResult(0.15), new RunResult(0.20)));
        assertFalse(lowReport.isSaturated());
    }

    @Test
    void centralPlannerDispatchesToAgentsAndReports() {
        // Front-end: "I have a goal with a default agent and a researcher
        // agent. Plan + execute end-to-end and confirm the dispatch
        // happened."
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        CentralPlanner planner = CentralPlanner.builder()
            .registerAgent(new AgentSpec("researcher", "research", input -> {
                calls.incrementAndGet();
                return "researched";
            }))
            .registerAgent(new AgentSpec("default-agent", "default", input -> {
                calls.incrementAndGet();
                return "ok";
            }))
            .build();
        try {
            PlanResult r = planner.run("do something", Map.of());
            assertTrue(r.success(), "expected successful plan, got: " + r.results());
            assertTrue(calls.get() >= 1, "at least one agent should have been called");
        } finally {
            planner.shutdown();
        }
    }

    @Test
    void globalPlanSkillDispatchReturnsAllResults() {
        // Front-end: "Run a 3-skill plan (search → code → finish)."
        GlobalPlan plan = GlobalPlan.ofSequence(Skill.SEARCHING, Skill.CODING, Skill.FINISH);
        java.util.List<String> executed = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger finishCount = new java.util.concurrent.atomic.AtomicInteger();
        HierarchicalExecutor exec = HierarchicalExecutor.builder()
            .searching(step -> { executed.add("search:" + step.description()); return "r"; })
            .coding(step -> { executed.add("code:" + step.description()); return "r"; })
            .finish(step -> { finishCount.incrementAndGet(); return "DONE"; })
            .build();
        String out = exec.executeAll(plan);
        assertEquals(3, plan.size(), "plan has 3 steps");
        assertTrue(out.contains("SEARCHING"));
        assertTrue(out.contains("CODING"));
        assertTrue(out.contains("FINISH"));
        assertEquals(2, executed.size(), "searching + coding handlers fired");
        assertEquals(1, finishCount.get(), "finish handler fired once");
    }

    @Test
    void byzantineDetectorFlagsEchoAttack() {
        // Three identical outputs from the same agent = echo attack
        ByzantineDetector det = new ByzantineDetector();
        String same = "this is a normal agent output line";
        det.observe(new AgentAction("a1", same, true));
        det.observe(new AgentAction("a1", same, true));
        var third = det.observe(new AgentAction("a1", same, true));
        assertTrue(third.stream().anyMatch(v -> v.rule().equals("ECHO_ATTACK")));
        assertTrue(det.isFlagged("a1"));
    }

    @Test
    void fullBusinessFlowVotingEnsembleWithByzantineGuard() {
        // Full business flow: front-end submits 3 LLM candidates via
        // an ensemble. The Byzantine detector watches the per-agent
        // outputs. The vote picks the winner; Byzantine flags any
        // echoed agent. End-to-end.
        ByzantineDetector byz = new ByzantineDetector();
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();

        MultiAgentOrchestrator<String> orch = new MultiAgentOrchestrator<>(
            "e2e-vote",
            List.of(
                (AgentFn<String>) (prompt, peers) -> {
                    String out = "candidate-A";
                    byz.observe(new AgentAction("agent-A", out, true));
                    return out;
                },
                (AgentFn<String>) (prompt, peers) -> {
                    String out = "candidate-B";
                    byz.observe(new AgentAction("agent-B", out, true));
                    return out;
                },
                (AgentFn<String>) (prompt, peers) -> {
                    String out = "candidate-A";
                    byz.observe(new AgentAction("agent-C", out, true));
                    return out;
                }
            ),
            new org.aethercode.orchestration.multiagent.VoteStrategy<>(
                org.aethercode.orchestration.multiagent.VoteStrategy.VoteMode.PLURALITY)
        );

        EnsembleResult<String> r = orch.run("pick the best");
        assertEquals("candidate-A", r.winner());
        // Two of three agents emitted the same string, but they are
        // different agent IDs → no echo-attack rule (which checks
        // identical agent IDs). Byzantine flags nothing yet, which
        // is correct: voting worked.
        assertFalse(byz.isFlagged("agent-A"));
    }
}
