package org.aethercode.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-2-16 acceptance tests for {@link Pricing#costUsd}. The
 * three tests cover the three behaviours the {@code ac session
 * tokens} CLI command (T-2-18) + the {@code ContextMeter} cost
 * column (spec.md §7.1) rely on:
 *
 * <ol>
 *   <li>basic input/output cost (no cache);</li>
 *   <li>cache-aware cost (cached tokens priced at the cache rate);</li>
 *   <li>defensive handling of negative inputs and over-large
 *       {@code cachedIn} values.</li>
 * </ol>
 */
class PricingTest {

    @Test
    void inputAndOutputArePricedPerMillionTokens() {
        // Anthropic Sonnet 4 rates: $3/M input, $15/M output.
        Pricing p = new Pricing(3.0, 15.0, 0.3);

        // 1M in + 1M out: $3 + $15 = $18.00
        assertThat(p.costUsd(1_000_000L, 1_000_000L)).isEqualTo(18.0);

        // Half-million in, quarter-million out: 0.5*3 + 0.25*15 = 5.25
        assertThat(p.costUsd(500_000L, 250_000L)).isEqualTo(5.25);

        // Zero tokens => zero cost. The renderer must not produce
        // a NaN / "-$0.00" artifact.
        assertThat(p.costUsd(0L, 0L)).isEqualTo(0.0);

        // Free fallback: a local ollama model with all-zero rates.
        Pricing free = Pricing.free();
        assertThat(free.costUsd(1_000_000L, 1_000_000L)).isEqualTo(0.0);
        assertThat(free.costUsd(0L, 0L)).isEqualTo(0.0);
    }

    @Test
    void cachedInputUsesCacheRateAndRemainderUsesInputRate() {
        // Same Sonnet 4 rates: $3/M regular, $0.30/M cached.
        Pricing p = new Pricing(3.0, 15.0, 0.3);

        // 1M in, 200k cached, 200k out.
        //   non-cached = 800k  -> 0.8 * 3.0 = 2.40
        //   cached     = 200k -> 0.2 * 0.3 = 0.06
        //   output     = 200k -> 0.2 * 15  = 3.00
        //   total: 5.46
        assertThat(p.costUsd(1_000_000L, 200_000L, 200_000L))
                .isEqualTo(5.46);

        // All-cached: every input token is at the cache rate.
        // 1M in, 1M cached, 0 out: 1.0 * 0.3 = 0.30
        assertThat(p.costUsd(1_000_000L, 0L, 1_000_000L)).isEqualTo(0.30);

        // Tiny request (single token) — must not lose precision
        // for a free model. (3 * 1e-6) is the rate for a 1-token
        // input at $3/M; the assertion uses a tight tolerance
        // because floating point.
        double oneToken = p.costUsd(1L, 0L, 0L);
        assertThat(oneToken).isCloseTo(3.0e-6, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void defensiveClampingForNegativeAndOversizedCached() {
        Pricing p = new Pricing(2.0, 8.0, 0.4);

        // Negative inputs are a hard error: the caller has a bug.
        assertThatThrownBy(() -> p.costUsd(-1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.costUsd(0L, -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.costUsd(0L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class);

        // cachedIn > tokensIn is logically impossible; we clamp to
        // tokensIn so a buggy counter doesn't produce a negative
        // cost. 1M in, 2M cached, 0 out -> all 1M treated as cached.
        //   cost = 1.0 * 0.4 = 0.40
        assertThat(p.costUsd(1_000_000L, 0L, 2_000_000L)).isEqualTo(0.40);

        // Two-arg overload: no cache hit.
        assertThat(p.costUsd(500_000L, 250_000L))
                .isEqualTo(0.5 * 2.0 + 0.25 * 8.0);

        // Negative pricing values are rejected at construction time.
        assertThatThrownBy(() -> new Pricing(-1.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pricing(0.0, -1.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pricing(0.0, 0.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);

        // null component collapses to 0.0; this is the merge layer's
        // contract for a user that only overrode one column of pricing.
        Pricing partial = new Pricing(null, 5.0, null);
        assertThat(partial.costUsd(1_000_000L, 1_000_000L, 0L))
                .isEqualTo(5.0);
    }
}
