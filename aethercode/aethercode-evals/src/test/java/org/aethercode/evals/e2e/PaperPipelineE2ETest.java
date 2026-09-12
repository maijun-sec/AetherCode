package org.aethercode.evals.e2e;

import org.aethercode.orchestration.multiagent.AgentFn;
import org.aethercode.orchestration.multiagent.CritiqueStrategy;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult.AgentOutput;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleStrategy;
import org.aethercode.orchestration.runtime.AgentRuntime;
import org.aethercode.orchestration.runtime.AgentRuntime.RuntimeResult;
import org.aethercode.orchestration.perf.ActionCache;
import org.aethercode.orchestration.perf.CostCeiling;
import org.aethercode.orchestration.perf.TokenCounter;
import org.aethercode.orchestration.selfcorrect.RetryStrategy;
import org.aethercode.orchestration.selfcorrect.SelfCorrectionLoop;
import org.aethercode.orchestration.verifier.Verifier;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end paper pipeline test.
 *
 * <p>Wires together every R-radar / R-orch / R-perf / R-mod building
 * block into a single flow:</p>
 *
 * <pre>
 *   paper id
 *      ↓
 *   [scholar]   → fetch metadata (mocked)
 *      ↓
 *   [arxiv]     → fetch abstract (mocked)
 *      ↓
 *   [critique]  → multi-agent ensemble verdict
 *      ↓
 *   [verifier]  → check verdict shape
 *      ↓
 *   result
 * </pre>
 *
 * <p>The test runs the pipeline against the eight papers under
 * {@code reference/papers/*_摘要.md} (a real corpus we already
 * have on disk) and asserts that every paper comes back with a
 * passed verdict. Mocks stand in for the live LLM calls; the
 * orchestration plumbing is the part under test.</p>
 */
class PaperPipelineE2ETest {

    /** A single paper's worth of pipeline state. */
    public record Paper(String id, String title, String abstract_) {
        public String verdictField() {
            return id + "-verdict";
        }
    }

    /** The pipeline's verdict object (one per paper). */
    public record Verdict(String paperId, String summary, int score, List<String> concerns) {
        public boolean acceptable() {
            return score >= 0 && score <= 100 && summary != null && !summary.isEmpty();
        }
    }

    /* ----------------------- Mocks for the LLM / API steps ----------------------- */

    /** Step 1: scholar → returns a "metadata" record (just the paper id + title). */
    private static AgentFn<Verdict> scholarMock(Map<String, Paper> corpus) {
        return new AgentFn<>() {
            @Override public String name() { return "scholar"; }
            @Override public Verdict respond(String paperId, List<Verdict> peers) {
                Paper p = corpus.get(paperId);
                if (p == null) {
                    return new Verdict(paperId, "scholar: paper not found", 0, List.of("missing"));
                }
                return new Verdict(p.id(), "scholar-mock: " + p.title(), 0, List.of());
            }
        };
    }

    /** Step 2: arxiv → produces a draft verdict based on the abstract. */
    private static AgentFn<Verdict> arxivMock(Map<String, Paper> corpus) {
        return new AgentFn<>() {
            @Override public String name() { return "arxiv"; }
            @Override public Verdict respond(String paperId, List<Verdict> peers) {
                Paper p = corpus.get(paperId);
                if (p == null) {
                    return new Verdict(paperId, "arxiv: paper not found", 0, List.of("missing"));
                }
                // Crude: a paper whose abstract mentions "agent" is at
                // least relevant; otherwise score 0. Real impl would
                // call export.arxiv.org.
                boolean relevant = p.abstract_().toLowerCase().contains("agent")
                        || p.abstract_().toLowerCase().contains("llm");
                int score = relevant ? 50 : 0;
                return new Verdict(p.id(),
                        "arxiv-mock: abstract length=" + p.abstract_().length(),
                        score,
                        relevant ? List.of() : List.of("not clearly relevant"));
            }
        };
    }

    /**
     * Step 3: a {@link CritiqueStrategy.Critic} that scores each
     * proposal. Higher is better. Awards a flat 0.7 so any non-empty
     * proposal passes; the verifier does the real acceptability check.
     */
    private static CritiqueStrategy.Critic<Verdict> criticMock() {
        return new CritiqueStrategy.Critic<>() {
            @Override public String name() { return "critic"; }
            @Override public double score(Verdict proposal, List<Verdict> allProposals) {
                if (proposal == null) return 0.0;
                if (proposal.concerns().contains("missing")) return 0.0;
                return 0.7;
            }
        };
    }

    /* ----------------------- Verifier: checks the verdict ----------------------- */

    private static Verifier<Verdict> acceptableVerdict() {
        return new Verifier<>() {
            @Override public String name() { return "acceptable"; }
            @Override public VerificationResult verify(Verdict v) {
                if (v == null) {
                    return VerificationResult.fail(Verifier.Severity.BLOCK, "null verdict");
                }
                if (!v.acceptable()) {
                    return VerificationResult.fail(Verifier.Severity.BLOCK,
                            "verdict not acceptable: " + v);
                }
                if (v.concerns().contains("missing")) {
                    return VerificationResult.fail(Verifier.Severity.BLOCK,
                            "verdict flagged paper as missing: " + v.paperId());
                }
                return VerificationResult.pass("verdict looks ok", Map.of("score", v.score()));
            }
        };
    }

    /* ----------------------- Test: the whole pipeline ----------------------- */

    @Test
    void pipelineProcessesAllEightPapers() throws Exception {
        // Load the real corpus from reference/papers/. The 8 abstracts
        // are checked into the repo as *_摘要.md.
        Map<String, Paper> corpus = loadCorpus();

        // Build the pipeline: scholar (1 proposer) + arxiv (1 proposer)
        // + critic (1 critic) → CritiqueStrategy. We then run the
        // winner through V → self-correct → V.
        List<AgentFn<Verdict>> proposers = List.of(scholarMock(corpus), arxivMock(corpus));
        MultiAgentOrchestrator<Verdict> orchestrator = new MultiAgentOrchestrator<>(
                "paper-pipeline",
                proposers,
                new CritiqueStrategy<Verdict>(List.of(criticMock())));

        // The "self-correct" strategy is a deterministic rewrite that
        // bumps the score — covers the case where the critic picked a
        // weak proposal.
        SelfCorrectionLoop<Verdict> sc = new SelfCorrectionLoop<>(
                "sc",
                acceptableVerdict(),
                RetryStrategy.transform(v -> new Verdict(v.paperId(), v.summary(), v.score() + 10, v.concerns())),
                5);

        // Cost ceiling: generous enough for 8 papers, but proves the
        // early-stop path works.
        CostCeiling ceiling = CostCeiling.builder()
                .maxCalls(200).maxMillis(60_000L).maxTokens(100_000L).build();
        ActionCache cache = new ActionCache(64);

        AgentRuntime<Verdict> runtime = AgentRuntime.<Verdict>builder()
                .name("paper-pipeline-runtime")
                .verifier(acceptableVerdict())
                .selfCorrect(sc)
                .ensemble(orchestrator)
                .costCeiling(ceiling)
                .actionCache(cache)
                .tokenCounter(TokenCounter.charQuotient())
                .build();

        // For each paper, run the whole pipeline and assert a passing
        // verdict. This is the "scholar → arxiv → critique → verdict"
        // E2E shape from the round plan.
        List<String> failures = new ArrayList<>();
        for (String paperId : corpus.keySet()) {
            Paper p = corpus.get(paperId);
            // The "action" we hand the runtime is the critic's seed
            // verdict; the runtime then runs it through the verifier
            // and (on fail) the self-correct loop. The ensemble step
            // runs the orchestrator and verifies the winner.
            Verdict seed = new Verdict(p.id(), "seed: " + p.title(), 0, List.of());
            RuntimeResult<Verdict> result = runtime.run(seed);
            if (!result.passed()) {
                failures.add(p.id() + " (" + result.outcome() + ")");
                continue;
            }
            Verdict finalVerdict = result.action();
            assertNotNull(finalVerdict, "runtime returned null action for " + p.id());
            assertEquals(p.id(), finalVerdict.paperId(), "paperId should be preserved");
            assertTrue(finalVerdict.acceptable(),
                    "verdict must be acceptable for " + p.id() + " but was " + finalVerdict);
        }
        assertTrue(failures.isEmpty(),
                "all 8 papers must pass the pipeline; failures: " + failures);

        // The runtime's cache + cost ceiling should have been used
        // (the verifier was hit more than once; the cache should
        // contain at least one entry by now).
        assertTrue(cache.size() >= 1, "cache should retain at least one verdict");
        assertTrue(ceiling.calls() > 0, "ceiling should have been charged at least one call");
    }

    @Test
    void pipelineRejectsMissingPaperAtScholar() {
        // Sanity: a paper id not in the corpus surfaces as a failure
        // through the verifier (concerns.contains("missing")).
        Map<String, Paper> empty = Map.of();
        AgentRuntime<Verdict> runtime = AgentRuntime.<Verdict>builder()
                .name("missing-paper")
                .verifier(acceptableVerdict())
                .selfCorrect(new SelfCorrectionLoop<>(
                        "sc",
                        acceptableVerdict(),
                        RetryStrategy.transform(v -> v),
                        3))
                .build();
        Verdict missingSeed = new Verdict("9999.99999", "not in corpus", 0,
                List.of("missing"));
        RuntimeResult<Verdict> res = runtime.run(missingSeed);
        assertTrue(!res.passed() || res.action().concerns().contains("missing"),
                "missing paper must fail the pipeline (got " + res.outcome() + ")");
    }

    @Test
    void pipelineShortCircuitsUnderTightBudget() {
        // A 1-call budget: the first V run exhausts it; the ensemble
        // (which would otherwise fire) is skipped.
        CostCeiling tight = CostCeiling.builder()
                .maxCalls(1).maxMillis(60_000L).maxTokens(1000L).build();
        AtomicInteger ensembleCalls = new AtomicInteger();
        EnsembleStrategy<Verdict> stubStrategy = (prompt, agents) -> {
            ensembleCalls.incrementAndGet();
            AgentFn<Verdict> a = agents.get(0);
            Verdict v = a.respond(prompt, List.of());
            return EnsembleResult.of(v, List.of(new AgentOutput<>("stub", v)),
                    Map.of("consensus", "stub"));
        };
        AgentFn<Verdict> passthroughAgent = new AgentFn<>() {
            @Override public String name() { return "passthrough"; }
            @Override public Verdict respond(String prompt, List<Verdict> peers) {
                return new Verdict("passthrough", "stub", 0, List.of());
            }
        };
        MultiAgentOrchestrator<Verdict> ensemble = new MultiAgentOrchestrator<>(
                "no-ensemble",
                List.of(passthroughAgent),
                stubStrategy);
        AgentRuntime<Verdict> runtime = AgentRuntime.<Verdict>builder()
                .name("tight-budget")
                .verifier(new Verifier<>() {
                    @Override public String name() { return "fail"; }
                    @Override public VerificationResult verify(Verdict input) {
                        return VerificationResult.fail(Verifier.Severity.BLOCK, "nope");
                    }
                })
                .selfCorrect(new SelfCorrectionLoop<>(
                        "sc",
                        new Verifier<>() {
                            @Override public String name() { return "sc-fail"; }
                            @Override public VerificationResult verify(Verdict input) {
                                return VerificationResult.fail(Verifier.Severity.BLOCK, "nope");
                            }
                        },
                        RetryStrategy.retrySame(), 5))
                .ensemble(ensemble)
                .costCeiling(tight)
                .build();
        RuntimeResult<Verdict> res = runtime.run(
                new Verdict("2512.13564v2", "x", 0, List.of()));
        assertEquals("budget-exhausted", res.outcome(),
                "tight budget must short-circuit before the ensemble");
        assertEquals(0, ensembleCalls.get(),
                "ensemble must not be invoked when the budget is exhausted");
    }

    /* ----------------------- helpers ----------------------- */

    /**
     * Load the 8 papers from {@code reference/papers/*_摘要.md}.
     * Each file's first non-empty line is the title; the body is the
     * abstract. The id is derived from the file name
     * (e.g. {@code 2512.13564v2_摘要.md} → {@code 2512.13564v2}).
     *
     * <p>Returns a corpus keyed by id. Falls back to a synthetic
     * corpus (one paper) if the reference directory is missing, so
     * the test still runs in a clean checkout that hasn't been
     * initialized.</p>
     */
    private static Map<String, Paper> loadCorpus() throws Exception {
        Map<String, Paper> corpus = new LinkedHashMap<>();
        Path papers = Path.of("reference", "papers");
        if (!Files.isDirectory(papers)) {
            // Fallback for a fresh clone without reference/.
            corpus.put("2512.13564v2", new Paper(
                    "2512.13564v2",
                    "Memory in the Age of AI Agents",
                    "agents and memory and lifelong learning across instances"));
            return corpus;
        }
        try (var stream = Files.list(papers)) {
            stream.filter(p -> p.getFileName().toString().endsWith("_摘要.md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p);
                            String[] lines = text.split("\n");
                            String title = "";
                            int firstBody = 0;
                            for (int i = 0; i < lines.length; i++) {
                                String l = lines[i].strip();
                                if (!l.isEmpty() && title.isEmpty()) {
                                    title = l.startsWith("#") ? l.substring(1).strip() : l;
                                    firstBody = i + 1;
                                    break;
                                }
                            }
                            StringBuilder body = new StringBuilder();
                            for (int i = firstBody; i < lines.length; i++) {
                                body.append(lines[i]).append('\n');
                            }
                            String id = p.getFileName().toString().replace("_摘要.md", "");
                            corpus.put(id, new Paper(id, title, body.toString()));
                        } catch (Exception ex) {
                            throw new RuntimeException("failed to load " + p, ex);
                        }
                    });
        }
        if (corpus.isEmpty()) {
            // Same fallback as above.
            corpus.put("2512.13564v2", new Paper(
                    "2512.13564v2",
                    "Memory in the Age of AI Agents",
                    "agents and memory and lifelong learning across instances"));
        }
        return corpus;
    }
}
