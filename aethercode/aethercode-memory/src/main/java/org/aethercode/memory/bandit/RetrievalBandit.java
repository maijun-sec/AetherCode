package org.aethercode.memory.bandit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paper 2604.21725 (AEL) — Thompson Sampling bandit for retrieval policy selection.
 * <p>
 * A multi-armed bandit that picks which "retrieval policy" to use
 * for the next episode. Each policy is treated as an arm; reward
 * is 1 on success, 0 on failure. Thompson Sampling gives O(√T)
 * regret under standard assumptions.
 * <p>
 * The bandit is intentionally generic — the caller wires its own
 * {@link RewardSignal} to map an episode outcome to a 0..1 reward.
 * In production the reward is usually a Sharpe-like score or
 * a "passed verifier" boolean; for the paper-compat sketch a
 * simple "did the agent complete the task" signal is enough.
 * <p>
 * The point of having a bandit (instead of a fixed policy):
 * <ul>
 *   <li>Different policies are optimal at different stages of
 *       an agent's lifetime (paper finding: "the early stages of
 *       the tasks benefit from minimal retrieval, recurring
 *       goal types benefit from plan reuse").</li>
 *   <li>Adapting online without re-training the agent saves
 *       the cold-start cost of switching policies.</li>
 * </ul>
 *
 * @see <a href="https://arxiv.org/abs/2604.21725">AEL paper</a>
 */
public final class RetrievalBandit {

    /** A single retrieval policy (arm). */
    public record Arm(String name) {
        public Arm {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("arm name must be non-blank");
            }
        }
    }

    /** Reward signal in [0.0, 1.0] (the bandit accepts any value, clamped). */
    public interface RewardSignal {
        double reward(int episodeIndex, String armChosen, boolean success);
    }

    /** Default reward: 1.0 on success, 0.0 on failure. */
    public static final RewardSignal BINARY = (i, arm, ok) -> ok ? 1.0 : 0.0;

    private final List<Arm> arms;
    private final RewardSignal rewardSignal;
    private final Random rng;
    private final double[] alpha;  // success counts + 1
    private final double[] beta;   // failure counts + 1
    private final AtomicInteger episodeCounter = new AtomicInteger();
    private final List<EpisodeRecord> history = new ArrayList<>();

    public RetrievalBandit(List<Arm> arms) {
        this(arms, BINARY, new Random());
    }

    public RetrievalBandit(List<Arm> arms, RewardSignal rewardSignal, Random rng) {
        Objects.requireNonNull(arms, "arms");
        if (arms.size() < 2) {
            throw new IllegalArgumentException("bandit needs at least 2 arms");
        }
        this.arms = List.copyOf(arms);
        this.rewardSignal = Objects.requireNonNull(rewardSignal, "rewardSignal");
        this.rng = Objects.requireNonNull(rng, "rng");
        this.alpha = new double[arms.size()];
        this.beta = new double[arms.size()];
        // Beta(1, 1) prior: uniform
        for (int i = 0; i < arms.size(); i++) {
            alpha[i] = 1.0;
            beta[i] = 1.0;
        }
    }

    /** Pick an arm for the next episode via Thompson Sampling. */
    public Arm selectArm() {
        int bestIdx = 0;
        double bestSample = -1.0;
        for (int i = 0; i < arms.size(); i++) {
            double sample = sampleBeta(alpha[i], beta[i]);
            if (sample > bestSample) {
                bestSample = sample;
                bestIdx = i;
            }
        }
        return arms.get(bestIdx);
    }

    /** Report the outcome of an episode and update the bandit's beliefs. */
    public void update(Arm arm, boolean success) {
        int idx = armIndex(arm);
        if (success) alpha[idx] += 1.0;
        else beta[idx] += 1.0;
        int ep = episodeCounter.incrementAndGet();
        double reward = rewardSignal.reward(ep, arm.name(), success);
        history.add(new EpisodeRecord(ep, arm.name(), success, reward));
    }

    /** Estimated probability of success for an arm (posterior mean). */
    public double estimatedSuccessRate(Arm arm) {
        int idx = armIndex(arm);
        return alpha[idx] / (alpha[idx] + beta[idx]);
    }

    /** Total number of recorded episodes. */
    public int episodesRecorded() {
        return episodeCounter.get();
    }

    /** Read-only view of the episode history (most recent last). */
    public List<EpisodeRecord> history() {
        return List.copyOf(history);
    }

    /** Number of arms. */
    public int armCount() {
        return arms.size();
    }

    /** All arms. */
    public List<Arm> arms() {
        return arms;
    }

    /* ---- helpers ---- */

    private int armIndex(Arm arm) {
        for (int i = 0; i < arms.size(); i++) {
            if (arms.get(i).name().equals(arm.name())) return i;
        }
        throw new IllegalArgumentException("unknown arm: " + arm);
    }

    /**
     * Sample from Beta(alpha, beta) via two Gamma samples.
     * (Marsaglia & Tsang method for Gamma — simple and good enough
     * for a paper-compat sketch.)
     */
    private double sampleBeta(double a, double b) {
        double x = sampleGamma(a);
        double y = sampleGamma(b);
        return x / (x + y);
    }

    private double sampleGamma(double shape) {
        if (shape < 1.0) {
            // boost: G(a) = G(a+1) * U^(1/a)
            double u = rng.nextDouble();
            return sampleGamma(shape + 1.0) * Math.pow(u, 1.0 / shape);
        }
        // Marsaglia & Tsang
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double v, x;
            do {
                x = rng.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0.0);
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    /** One episode record, kept for audit and evals. */
    public record EpisodeRecord(
        int episode,
        String armChosen,
        boolean success,
        double reward
    ) {}
}
