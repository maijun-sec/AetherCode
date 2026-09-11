package org.aethercode.deepagents.selfimprove;

import java.time.Instant;

/**
 * R241.3 (O-3): a utility-decay policy for the
 * {@link ReasoningBank}. Inspired by the forgetting curves in
 * the AI Agent memory survey (arXiv:2512.13564 §5.2.3) and the
 * "use it or lose it" utility semantics used in the
 * ReasoningBank pattern (paper 1 §4.2.2, 2025).
 *
 * <h2>What "decay" means here</h2>
 *
 * <p>A {@code ReasoningUnit}'s {@code utility} is a number in
 * {@code [0, 1]}. When the unit is freshly written it sits at
 * 0.5 (the {@link ReasoningUnit#of} default). Each recall hit
 * lifts utility toward 1; time without recall should pull it
 * back down so stale patterns do not dominate the recall
 * ranking. The {@code UtilityDecay} decides how that pull-down
 * works.
 *
 * <p>Decay is computed lazily on recall — the
 * {@link #effectiveUtility(ReasoningUnit, Instant)} method
 * returns the utility to use <em>at this moment</em>. The
 * stored utility is not rewritten on every read; the bank
 * provides a separate {@link ReasoningBank#decayPass(Instant)}
 * for callers that want to persist the decayed value.
 *
 * <h2>Why an interface</h2>
 *
 * <p>Different deployments want different policies: a
 * long-running offline pipeline might want a slow linear
 * decay so it does not lose rarely-used but valuable
 * reflections; a chat assistant might want a fast exponential
 * decay so the bank tracks the user's current tasks.
 * Substituting one {@code UtilityDecay} for another should
 * not require touching the bank or the recall path.
 */
@FunctionalInterface
public interface UtilityDecay {

    /**
     * The unit's "effective" utility at the given time, i.e.
     * the value the recall ranking should use. {@code unit.utility()}
     * is the stored value; the implementation applies the
     * decay transformation.
     *
     * <p>Implementations must:
     * <ul>
     *   <li>return a value in {@code [0, 1]};</li>
     *   <li>be pure — same inputs, same output, no side effects;</li>
     *   <li>treat {@code now == null} as the present (i.e.
     *       {@code Instant.now()}).</li>
     * </ul>
     */
    double effectiveUtility(ReasoningUnit unit, Instant now);

    /**
     * The no-op policy: utility is exactly the stored value.
     * The default for new banks (R241.2 behaviour is preserved).
     */
    UtilityDecay NO_DECAY = (unit, now) -> unit.utility();

    /**
     * Exponential decay with a half-life: utility drops by
     * half every {@code halfLife} of elapsed time.
     *
     * <pre>
     *   u_eff = u * 2^(-Δseconds / halfLifeSeconds)
     * </pre>
     *
     * <p>Zero-age returns the stored utility unchanged;
     * one half-life returns half; two half-lives return a
     * quarter. Stale units asymptote to zero but never go
     * below it.
     */
    static UtilityDecay exponential(java.time.Duration halfLife) {
        return new ExponentialDecay(halfLife);
    }

    /**
     * Linear decay: utility drops by {@code perDay} per day
     * elapsed, clamped to {@code [0, 1]}.
     *
     * <pre>
     *   u_eff = max(0, u - perDay * Δdays)
     * </pre>
     */
    static UtilityDecay linear(double perDay) {
        return new LinearDecay(perDay);
    }
}
