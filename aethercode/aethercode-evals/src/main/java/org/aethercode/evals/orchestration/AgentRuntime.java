package org.aethercode.evals.orchestration;

import org.aethercode.evals.multiagent.MultiAgentOrchestrator;
import org.aethercode.evals.perf.ActionCache;
import org.aethercode.evals.perf.CostCeiling;
import org.aethercode.evals.perf.TokenCounter;
import org.aethercode.evals.selfcorrect.CorrectionStrategy;
import org.aethercode.evals.selfcorrect.SelfCorrectionLoop;
import org.aethercode.evals.selfcorrect.SelfCorrectionLoop.LoopResult;
import org.aethercode.evals.verifier.Verifier;
import org.aethercode.evals.verifier.Verifier.VerificationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Single-action runtime: V (verify) → if pass, return. If fail, run
 * self-correction loop (V → strategy → ...). Optionally, on a final
 * self-correction failure, escalate to a multi-agent ensemble to
 * collect fresh perspectives.
 *
 * <p>This is the integration point of R-radar-6 (V), R-radar-7
 * (self-correction), and R-radar-8 (multi-agent ensemble) for the
 * single-action case. For N parallel candidates, see
 * {@link #runEnsemble}.</p>
 *
 * <p>The runtime is policy-light by design: it threads three
 * pre-built objects together and decides only when to use which
 * one. Callers can swap any of the three for their own (e.g. a
 * domain-specific composite verifier, a custom correction strategy,
 * a custom ensemble strategy).</p>
 */
public class AgentRuntime<T> {

    private final String name;
    private final Verifier<T> verifier;
    private final SelfCorrectionLoop<T> selfCorrect;
    private final MultiAgentOrchestrator<T> ensemble;
    private final Function<T, T> onEnsembleAdopted;
    private final CostCeiling costCeiling;
    private final ActionCache actionCache;
    private final TokenCounter<T> tokenCounter;

    private AgentRuntime(Builder<T> b) {
        this.name = b.name;
        this.verifier = Objects.requireNonNull(b.verifier, "verifier");
        this.selfCorrect = b.selfCorrect;
        this.ensemble = b.ensemble;
        this.onEnsembleAdopted = b.onEnsembleAdopted == null ? t -> t : b.onEnsembleAdopted;
        this.costCeiling = b.costCeiling;
        this.actionCache = b.actionCache;
        this.tokenCounter = b.tokenCounter == null
                ? TokenCounter.zero()
                : b.tokenCounter;
        if (selfCorrect == null && ensemble == null) {
            // A runtime with no self-correct and no ensemble is just a
            // bare verifier. Allow it (useful for tests) but warn
            // via the constructor check.
            // No-op.
        }
    }

    /**
     * Run a single action through V → self-correct (on fail) →
     * ensemble (on self-correct exhaustion). Returns the verified
     * action plus a full audit trail.
     */
    public RuntimeResult<T> run(T action) {
        Objects.requireNonNull(action, "action");
        RuntimeTrace trace = new RuntimeTrace();
        VerificationResult v = verifyWithCache(action);
        trace.add(new RuntimeTrace.Entry("verify", action, v));

        VerificationResult last = v;
        if (v.passed()) {
            return new RuntimeResult<>(action, "passed", trace.entries(), null, null);
        }

        if (selfCorrect != null) {
            if (budgetExceeded()) {
                return new RuntimeResult<>(action, "budget-exhausted",
                        trace.entries(), null, null);
            }
            LoopResult<T> lr = selfCorrect.run(action);
            trace.absorb(lr);
            last = lr.lastAttempt() == null ? last : lr.lastAttempt().result();
            if (lr.passed()) {
                return new RuntimeResult<>(lr.lastAttempt().action(), "self-corrected",
                        trace.entries(), lr, null);
            }
            if (ensemble == null) {
                return new RuntimeResult<>(lr.lastAttempt().action(), "self-correct-exhausted",
                        trace.entries(), lr, null);
            }
        }

        // Self-correct exhausted (or not configured) and ensemble is
        // configured: run ensemble on the last attempted action.
        T seed = selfCorrect == null
                ? action
                : (selfCorrect.run(action).lastAttempt() == null
                        ? action
                        : selfCorrect.run(action).lastAttempt().action());
        if (ensemble == null) {
            // No ensemble to escalate to; return the last attempted action.
            return new RuntimeResult<>(seed, "unverified",
                    trace.entries(),
                    selfCorrect == null ? null : selfCorrect.run(action),
                    null);
        }
        if (budgetExceeded()) {
            return new RuntimeResult<>(seed, "budget-exhausted",
                    trace.entries(), null, null);
        }
        return runEnsembleInternal(seed, trace, last);
    }

    private RuntimeResult<T> runEnsembleInternal(T seed, RuntimeTrace trace,
                                                 VerificationResult lastFailure) {
        MultiAgentOrchestrator.EnsembleResult<T> er = ensemble.run("agent-runtime:" + name);
        T winner = onEnsembleAdopted.apply(er.winner());
        VerificationResult post = verifyWithCache(winner);
        trace.add(new RuntimeTrace.Entry("ensemble-verify", winner, post));
        if (post.passed()) {
            return new RuntimeResult<>(winner, "ensemble-passed",
                    trace.entries(), null, er);
        }
        return new RuntimeResult<>(winner, "ensemble-failed",
                trace.entries(), null, er);
    }

    /**
     * Multi-agent entry point: collect N candidate actions, run each
     * through the V+self-correct pipeline, then ensemble the survivors
     * by majority / consensus (the existing
     * {@link MultiAgentOrchestrator} takes care of the consensus rule).
     */
    public RuntimeResult<T> runEnsemble(List<T> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (ensemble == null) {
            throw new IllegalStateException("runEnsemble requires an ensemble strategy");
        }
        RuntimeTrace trace = new RuntimeTrace();
        List<T> survivors = new ArrayList<>();
        List<T> allCandidates = new ArrayList<>();
        for (T candidate : candidates) {
            allCandidates.add(candidate);
            if (budgetExceeded()) {
                break;
            }
            VerificationResult v = verifyWithCache(candidate);
            trace.add(new RuntimeTrace.Entry("candidate-verify", candidate, v));
            if (v.passed()) {
                survivors.add(candidate);
                continue;
            }
            if (selfCorrect != null) {
                if (budgetExceeded()) {
                    break;
                }
                LoopResult<T> lr = selfCorrect.run(candidate);
                trace.absorb(lr);
                if (lr.passed() && lr.lastAttempt() != null) {
                    survivors.add(lr.lastAttempt().action());
                }
            }
        }
        if (survivors.isEmpty()) {
            // No candidate survived — fall through to the single
            // ensemble run with whatever the user gave.
            survivors = allCandidates;
        }
        // Hand the survivors to the existing multi-agent orchestrator
        // by wrapping them in stub agents.
        List<org.aethercode.evals.multiagent.AgentFn<T>> stubAgents = new ArrayList<>();
        for (int i = 0; i < survivors.size(); i++) {
            final T out = survivors.get(i);
            final String name = "candidate-" + i;
            stubAgents.add(new org.aethercode.evals.multiagent.AgentFn<>() {
                @Override public String name() { return name; }
                @Override public T respond(String prompt, List<T> peers) { return out; }
            });
        }
        MultiAgentOrchestrator<T> localOrch = new MultiAgentOrchestrator<>(
                "agent-runtime-candidates", stubAgents, ensemble.strategy());
        MultiAgentOrchestrator.EnsembleResult<T> er = localOrch.run("agent-runtime:" + name);
        T winner = onEnsembleAdopted.apply(er.winner());
        VerificationResult post = verifyWithCache(winner);
        trace.add(new RuntimeTrace.Entry("ensemble-verify", winner, post));
        if (post.passed()) {
            return new RuntimeResult<>(winner, "ensemble-passed",
                    trace.entries(), null, er);
        }
        return new RuntimeResult<>(winner, "ensemble-failed",
                trace.entries(), null, er);
    }

    /**
     * Verify an action, consulting the cache first. On miss, the
     * verifier runs, the result is cached, and the cost ceiling is
     * charged one call (and a token estimate).
     */
    private VerificationResult verifyWithCache(T action) {
        if (actionCache != null) {
            VerificationResult cached = actionCache.get(action);
            if (cached != null) {
                return cached;
            }
        }
        long tokens = tokenCounter.count(action);
        if (costCeiling != null) {
            costCeiling.recordCall(tokens);
        }
        VerificationResult fresh = verifier.verify(action);
        if (actionCache != null) {
            actionCache.put(action, fresh);
        }
        return fresh;
    }

    private boolean budgetExceeded() {
        return costCeiling != null && costCeiling.exceeded();
    }

    public String name() { return name; }
    public Verifier<T> verifier() { return verifier; }
    public SelfCorrectionLoop<T> selfCorrect() { return selfCorrect; }
    public MultiAgentOrchestrator<T> ensemble() { return ensemble; }
    public CostCeiling costCeiling() { return costCeiling; }
    public ActionCache actionCache() { return actionCache; }
    public TokenCounter<T> tokenCounter() { return tokenCounter; }

    /* ----------------------- result ----------------------- */

    /**
     * Final outcome of one runtime invocation.
     */
    public record RuntimeResult<T>(
            T action,
            String outcome,
            List<RuntimeTrace.Entry> trace,
            LoopResult<T> selfCorrectResult,
            MultiAgentOrchestrator.EnsembleResult<T> ensembleResult) {

        public boolean passed() {
            return "passed".equals(outcome)
                    || "self-corrected".equals(outcome)
                    || "ensemble-passed".equals(outcome);
        }

        public boolean budgetExhausted() {
            return "budget-exhausted".equals(outcome);
        }

        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", outcome);
            m.put("passed", passed());
            m.put("budget_exhausted", budgetExhausted());
            m.put("attempts", trace.size());
            m.put("self_correct_attempts",
                    selfCorrectResult == null ? 0 : selfCorrectResult.attemptsUsed());
            m.put("ensemble_consensus",
                    ensembleResult == null ? null
                            : ensembleResult.metadata().get("consensus"));
            return m;
        }
    }

    /* ----------------------- builder ----------------------- */

    public static <T> Builder<T> builder() { return new Builder<>(); }

    public static final class Builder<T> {
        private String name = "agent-runtime";
        private Verifier<T> verifier;
        private SelfCorrectionLoop<T> selfCorrect;
        private MultiAgentOrchestrator<T> ensemble;
        private Function<T, T> onEnsembleAdopted;
        private CostCeiling costCeiling;
        private ActionCache actionCache;
        private TokenCounter<T> tokenCounter;

        public Builder<T> name(String name) { this.name = name; return this; }
        public Builder<T> verifier(Verifier<T> v) { this.verifier = v; return this; }
        public Builder<T> selfCorrect(SelfCorrectionLoop<T> s) { this.selfCorrect = s; return this; }
        public Builder<T> ensemble(MultiAgentOrchestrator<T> e) { this.ensemble = e; return this; }
        public Builder<T> onEnsembleAdopted(Function<T, T> hook) { this.onEnsembleAdopted = hook; return this; }
        public Builder<T> costCeiling(CostCeiling c) { this.costCeiling = c; return this; }
        public Builder<T> actionCache(ActionCache c) { this.actionCache = c; return this; }
        public Builder<T> tokenCounter(TokenCounter<T> tc) { this.tokenCounter = tc; return this; }

        public AgentRuntime<T> build() { return new AgentRuntime<>(this); }
    }
}
