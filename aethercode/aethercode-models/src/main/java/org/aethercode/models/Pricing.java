package org.aethercode.models;

import java.util.Objects;

/**
 * Phase 2.2 / T-2-16 (design.md §3.7, spec.md §7.2, §10.1):
 * per-million-token pricing for one model. All three values are
 * in <em>USD per 1 000 000 tokens</em> (the unit the spec calls
 * "$/M"), not per single token. {@code cachedPerMTokensUsd} may
 * be zero / unset for providers that don't expose prompt-cache
 * pricing (the {@link #costUsd} formula treats null and zero
 * identically).
 *
 * <p>The cost calculation is intentionally simple: each component
 * is priced independently, no bundle discounts, no minimum
 * charge. The shape matches what
 * {@code aethercode-tasks}'s token counter feeds into the CLI's
 * {@code ac session tokens <id>} output.
 */
public record Pricing(
        Double inputPerMTokensUsd,
        Double outputPerMTokensUsd,
        Double cachedPerMTokensUsd
) {
    public Pricing {
        if (inputPerMTokensUsd == null) inputPerMTokensUsd = 0.0;
        if (outputPerMTokensUsd == null) outputPerMTokensUsd = 0.0;
        if (cachedPerMTokensUsd == null) cachedPerMTokensUsd = 0.0;
        if (inputPerMTokensUsd < 0 || outputPerMTokensUsd < 0 || cachedPerMTokensUsd < 0) {
            throw new IllegalArgumentException("pricing values must be >= 0: " + this);
        }
    }

    /** All-zero pricing — useful as a fallback for unknown / free models. */
    public static Pricing free() {
        return new Pricing(0.0, 0.0, 0.0);
    }

    /**
     * Cost in USD for a single request that consumed {@code tokensIn}
     * input tokens and {@code tokensOut} output tokens. {@code cachedIn}
     * is the subset of the input tokens that were served from the
     * provider's prompt cache (Anthropic, OpenAI); these are priced at
     * the (typically much lower) cache rate rather than the standard
     * input rate.
     *
     * <p>Formula (design.md §3.7):
     * <pre>
     *   cost = (tokensIn/1e6) * inputPerMTokensUsd
     *        + (tokensOut/1e6) * outputPerMTokensUsd
     *        + (cachedIn/1e6) * cachedPerMTokensUsd
     * </pre>
     * {@code cachedIn} cannot exceed {@code tokensIn} — that would
     * mean "more cache hits than input", which is a logic bug at the
     * caller; the method clamps to {@code tokensIn} defensively rather
     * than throwing, so a buggy counter doesn't take down the daemon.
     */
    public double costUsd(long tokensIn, long tokensOut, long cachedIn) {
        if (tokensIn < 0 || tokensOut < 0 || cachedIn < 0) {
            throw new IllegalArgumentException(
                    "token counts must be >= 0: in=" + tokensIn + " out=" + tokensOut
                            + " cached=" + cachedIn);
        }
        long cached = Math.min(cachedIn, tokensIn);
        long nonCached = tokensIn - cached;
        double cost = 0.0;
        cost += (nonCached / 1_000_000.0) * inputPerMTokensUsd;
        cost += (tokensOut / 1_000_000.0) * outputPerMTokensUsd;
        cost += (cached / 1_000_000.0) * cachedPerMTokensUsd;
        return cost;
    }

    /** Two-argument overload for callers that don't track cache hits. */
    public double costUsd(long tokensIn, long tokensOut) {
        return costUsd(tokensIn, tokensOut, 0L);
    }

    @Override
    public String toString() {
        return "Pricing(input=$" + inputPerMTokensUsd + "/M, output=$" + outputPerMTokensUsd
                + "/M, cached=$" + cachedPerMTokensUsd + "/M)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pricing p)) return false;
        return Objects.equals(inputPerMTokensUsd, p.inputPerMTokensUsd)
                && Objects.equals(outputPerMTokensUsd, p.outputPerMTokensUsd)
                && Objects.equals(cachedPerMTokensUsd, p.cachedPerMTokensUsd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputPerMTokensUsd, outputPerMTokensUsd, cachedPerMTokensUsd);
    }
}
