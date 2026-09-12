package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.multiagent.CritiqueStrategy;
import org.aethercode.orchestration.multiagent.HybridStrategy;
import org.aethercode.orchestration.multiagent.IndependentStrategy;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator;
import org.aethercode.orchestration.multiagent.VoteStrategy;
import org.aethercode.orchestration.verifier.Verifier;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Paper 2512.08296-style agent architecture selector.
 * <p>
 * Recommends the best architecture for a task based on:
 * <ul>
 *   <li>parallelizable / sequential / dynamic / tool-heavy / single-agent baseline</li>
 *   <li>capability saturation (0.45 拐点)</li>
 *   <li>error amplification (Independent 17.2×, Centralized 4.4×, Hybrid &lt; 4.4×)</li>
 * </ul>
 * <p>
 * The default decision tree follows the paper's measured data:
 * <ul>
 *   <li>parallelizable + low single baseline → Centralized (Vote or Critique)</li>
 *   <li>dynamic web nav → Independent</li>
 *   <li>tool-heavy + verifiable → Hybrid</li>
 *   <li>sequential reasoning → Single</li>
 *   <li>high single baseline (>= 0.45) → Single (capability saturated)</li>
 * </ul>
 */
public final class AgentArchitectureSelector {

    /** The 5 architecture classes from paper 2512.08296. */
    public enum Architecture { SINGLE, INDEPENDENT, CENTRALIZED, DECENTRALIZED, HYBRID }

    /** Task features used for selection. */
    public record TaskFeatures(
        boolean parallelizable,
        boolean sequential,
        boolean toolHeavy,
        boolean dynamic,
        double singleAgentBaseline
    ) {
        public TaskFeatures {
            if (singleAgentBaseline < 0.0 || singleAgentBaseline > 1.0) {
                throw new IllegalArgumentException("singleAgentBaseline must be 0..1");
            }
        }
    }

    /** A recommendation with reasoning. */
    public record Recommendation(
        Architecture architecture,
        String rationale,
        double expectedGainPct
    ) {}

    /** Supplier for verifier (only used if HYBRID is recommended). */
    public interface VerifierSupplier {
        <T> Verifier<T> get();
    }

    private final double saturationThreshold;

    public AgentArchitectureSelector() {
        this(0.45); // paper 2512.08296 measured saturation point
    }

    public AgentArchitectureSelector(double saturationThreshold) {
        this.saturationThreshold = saturationThreshold;
    }

    public Recommendation recommend(TaskFeatures features) {
        Objects.requireNonNull(features, "features");

        // Rule 1: high single baseline → SINGLE (capability saturated)
        if (features.singleAgentBaseline() >= saturationThreshold) {
            return new Recommendation(Architecture.SINGLE,
                "single-agent baseline " + features.singleAgentBaseline() + " >= saturation " + saturationThreshold,
                0.0);
        }
        // Rule 2: sequential reasoning → SINGLE
        if (features.sequential() && !features.parallelizable()) {
            return new Recommendation(Architecture.SINGLE,
                "sequential reasoning; multi-agent degrades 39-70%",
                0.0);
        }
        // Rule 3: dynamic web nav → INDEPENDENT
        if (features.dynamic() && !features.toolHeavy()) {
            return new Recommendation(Architecture.INDEPENDENT,
                "dynamic web navigation; Independent +9.2% vs Centralized +0.2%",
                9.2);
        }
        // Rule 4: tool-heavy + verifiable → HYBRID
        if (features.toolHeavy() && features.parallelizable()) {
            return new Recommendation(Architecture.HYBRID,
                "tool-heavy parallelizable; Hybrid balances 17.2x (Ind) and 4.4x (Cen) errors",
                0.0);
        }
        // Rule 5: parallelizable + low single baseline → CENTRALIZED
        if (features.parallelizable() && features.singleAgentBaseline() < saturationThreshold) {
            return new Recommendation(Architecture.CENTRALIZED,
                "parallelizable + low single baseline (< " + saturationThreshold + "); Centralized +80.8% on financial reasoning",
                80.8);
        }
        // Default: SINGLE (safe fallback)
        return new Recommendation(Architecture.SINGLE,
            "default fallback; task features do not match any specialized pattern",
            0.0);
    }

    /** Returns a 0..1 confidence based on how many rules matched. */
    public double confidence(Recommendation rec, TaskFeatures features) {
        // Higher confidence when features are well-defined (clear single baseline, clear flags)
        double conf = 0.5;
        if (features.singleAgentBaseline() >= saturationThreshold) conf += 0.3;
        if (features.sequential() && !features.parallelizable()) conf += 0.2;
        if (features.dynamic() && !features.toolHeavy()) conf += 0.15;
        if (features.toolHeavy() && features.parallelizable()) conf += 0.2;
        if (features.parallelizable() && features.singleAgentBaseline() < saturationThreshold) conf += 0.25;
        return Math.min(1.0, conf);
    }

    /** Build a {@link MultiAgentOrchestrator.EnsembleStrategy} for the recommendation. */
    public <T> MultiAgentOrchestrator.EnsembleStrategy<T> buildStrategy(
        Recommendation rec,
        VerifierSupplier verifierSupplier
    ) {
        Objects.requireNonNull(rec, "rec");
        return switch (rec.architecture()) {
            case SINGLE -> throw new IllegalStateException("SINGLE does not need a strategy; use AgentRuntime");
            case INDEPENDENT -> new IndependentStrategy<>();
            case CENTRALIZED -> new VoteStrategy<>(VoteStrategy.VoteMode.PLURALITY);
            case HYBRID -> {
                if (verifierSupplier == null) {
                    throw new IllegalArgumentException("HYBRID requires a VerifierSupplier");
                }
                yield new HybridStrategy<>(verifierSupplier.get());
            }
            case DECENTRALIZED -> throw new UnsupportedOperationException(
                "DECENTRALIZED not yet implemented; see R-orch-3.5 backlog");
        };
    }
}
