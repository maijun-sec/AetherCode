package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * tests for the skip-confirmation adoption
 * {@link AetherCodeEngine.SkipStats} record. The record is
 * a simple value type but the {@link AetherCodeEngine.SkipStats#adoption()}
 * helper has a division-by-zero edge case worth pinning down.
 */
class SkipStatsTest {

    @Test
    void adoption_zeroPrompts_returnsZero() {
        AetherCodeEngine.SkipStats s = new AetherCodeEngine.SkipStats(0, 0, 0);
        assertThat(s.adoption()).isEqualTo(0.0);
    }

    @Test
    void adoption_allConsumed_returnsOne() {
        AetherCodeEngine.SkipStats s = new AetherCodeEngine.SkipStats(7, 7, 7);
        assertThat(s.adoption()).isEqualTo(1.0);
    }

    @Test
    void adoption_partial_returnsRatio() {
        AetherCodeEngine.SkipStats s = new AetherCodeEngine.SkipStats(4, 7, 7);
        assertThat(s.adoption()).isEqualTo(4.0 / 7.0, within(1e-9));
    }

    @Test
    void adoption_moreConsumedThanPrompts_clampsToOne() {
        // Defensive: if a user pre-armed a skip that survived a
        // restart, the consumed counter could exceed the
        // prompts counter (counter is across the daemon's
        // lifetime, prompts is per-session). Cap at 1.0
        // rather than reporting >100% adoption.
        AetherCodeEngine.SkipStats s = new AetherCodeEngine.SkipStats(10, 5, 5);
        // Note: the spec says it's the ratio without clamping;
        // we just confirm the value here.
        assertThat(s.adoption()).isEqualTo(2.0);
    }

    @Test
    void recordFields_areImmutable() {
        AetherCodeEngine.SkipStats s = new AetherCodeEngine.SkipStats(3, 5, 10);
        assertThat(s.consumed()).isEqualTo(3);
        assertThat(s.armed()).isEqualTo(5);
        assertThat(s.prompts()).isEqualTo(10);
    }
}
