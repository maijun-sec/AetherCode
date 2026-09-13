package org.aethercode.orchestration.verifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * ReDeReF-style training-free probabilistic reasoner (arXiv:2603.13256).
 *
 * <p>ReDeReF replaces LLM-based multi-agent voting with a
 * training-free probabilistic model. The key idea: treat each
 * agent's answer as a draw from a categorical distribution, and
 * use Bayesian updates to maintain a posterior over answers as
 * evidence accumulates. No fine-tuning, no LLM judge; the only
 * assumption is that each agent is independently noisy.
 *
 * <p>This class is the AetherCode Tier-3 implementation. Given
 * a list of (agent, answer) pairs, it computes:
 * <ol>
 *   <li>The posterior over each candidate answer.</li>
 *   <li>The expected-utility pick (answer with the highest
 *       posterior).</li>
 *   <li>The credible interval on the top answer's probability
 *       (for "I am X% confident in this answer" diagnostics).</li>
 * </ol>
 *
 * <h2>Why useful</h2>
 * For tasks where the same agent can produce different answers
 * across runs (stochastic, API temperature &gt; 0), this gives
 * a principled way to merge them without paying for an LLM
 * judge. It also gives a confidence score, which a {@code Verifier}
 * can use as a red flag if confidence is below a threshold.
 */
public final class ProbabilisticReasoner {

    /** A single (agentName, answer) observation. */
    public record Observation(String agent, String answer) {
        public Observation { Objects.requireNonNull(agent, "agent"); Objects.requireNonNull(answer, "answer"); }
    }
    /** Posterior over all observed answers. */
    public record Posterior(Map<String, Double> probs, String bestAnswer, double bestProb, double credibleLow, double credibleHigh) {}
    /** Sampling method for the credible interval. */
    public record SamplingResult(Map<String, Double> mean, Map<String, Double> low, Map<String, Double> high) {}

    private final double priorAlpha;     // Dirichlet prior pseudo-count per answer
    private final int credibleSamples;    // bootstrap sample count for credible interval
    private final Random random;

    public ProbabilisticReasoner() {
        this(1.0, 1000, new Random(42L));
    }

    public ProbabilisticReasoner(double priorAlpha, int credibleSamples, Random random) {
        if (priorAlpha <= 0) throw new IllegalArgumentException("priorAlpha must be > 0");
        if (credibleSamples < 100) throw new IllegalArgumentException("credibleSamples must be >= 100");
        this.priorAlpha = priorAlpha;
        this.credibleSamples = credibleSamples;
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Compute the Bayesian posterior over answers using a Dirichlet
     * prior with {@code priorAlpha} pseudo-counts. The returned
     * probabilities sum to 1.0.
     */
    public Posterior posterior(List<Observation> observations) {
        Objects.requireNonNull(observations, "observations");
        Map<String, Double> counts = new HashMap<>();
        for (Observation o : observations) {
            counts.merge(o.answer(), 1.0, Double::sum);
        }
        if (counts.isEmpty()) {
            return new Posterior(Map.of(), null, 0.0, 0.0, 0.0);
        }
        double denom = counts.values().stream().mapToDouble(Double::doubleValue).sum()
                     + priorAlpha * counts.size();
        Map<String, Double> probs = new HashMap<>();
        for (var e : counts.entrySet()) {
            probs.put(e.getKey(), (e.getValue() + priorAlpha) / denom);
        }
        // best answer
        var best = probs.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .orElseThrow();
        // credible interval via bootstrap (resample observations, recompute posterior)
        double[] sampledProbs = new double[credibleSamples];
        List<Observation> obsList = new ArrayList<>(observations);
        for (int s = 0; s < credibleSamples; s++) {
            // bootstrap: sample with replacement
            Map<String, Double> bs = new HashMap<>();
            for (int i = 0; i < obsList.size(); i++) {
                Observation sampled = obsList.get(random.nextInt(obsList.size()));
                bs.merge(sampled.answer(), 1.0, Double::sum);
            }
            double bsDenom = bs.values().stream().mapToDouble(Double::doubleValue).sum()
                          + priorAlpha * bs.size();
            sampledProbs[s] = (bs.getOrDefault(best.getKey(), 0.0) + priorAlpha) / bsDenom;
        }
        java.util.Arrays.sort(sampledProbs);
        double low  = sampledProbs[(int) (0.025 * credibleSamples)];
        double high = sampledProbs[(int) (0.975 * credibleSamples)];
        return new Posterior(probs, best.getKey(), best.getValue(), low, high);
    }

    /**
     * Sample N times from the posterior Dirichlet. Useful for
     * "what if I observed 100 more draws, which answer would I
     * most likely pick" simulations.
     */
    public Map<String, Double> sampleFromPosterior(List<Observation> observations, int n) {
        Posterior p = posterior(observations);
        if (p.probs().isEmpty()) return Map.of();
        java.util.Random rng = new Random(random.nextLong());
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i < n; i++) {
            double r = rng.nextDouble();
            double cum = 0;
            for (var e : p.probs().entrySet()) {
                cum += e.getValue();
                if (r <= cum) {
                    out.merge(e.getKey(), 1.0, Double::sum);
                    break;
                }
            }
        }
        double total = out.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total == 0) return Map.of();
        for (var e : out.entrySet()) out.put(e.getKey(), e.getValue() / total);
        return out;
    }
}
