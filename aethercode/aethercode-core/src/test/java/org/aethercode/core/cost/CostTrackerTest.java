package org.aethercode.core.cost;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostTrackerTest {

    @Test
    void emptyTrackerHasZero() {
        CostTracker t = new CostTracker();
        CostTracker.Summary s = t.summary();
        assertThat(s.totalInput()).isZero();
        assertThat(s.totalOutput()).isZero();
        assertThat(s.totalCostUsd()).isZero();
    }

    @Test
    void recordAccumulatesTokens() {
        CostTracker t = new CostTracker();
        t.record("claude-sonnet-4-5", new CostTracker.Usage(1_000, 500));
        t.record("claude-sonnet-4-5", new CostTracker.Usage(2_000, 1_000));
        CostTracker.Summary s = t.summary();
        assertThat(s.totalInput()).isEqualTo(3_000);
        assertThat(s.totalOutput()).isEqualTo(1_500);
    }

    @Test
    void defaultPriceTableChargesCorrectly() {
        CostTracker t = new CostTracker();
        // sonnet-4-5: $0.003 / 1k in, $0.015 / 1k out
        double cost = t.costFor("claude-sonnet-4-5", 1_000, 1_000);
        assertThat(cost).isCloseTo(0.018, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void perModelBreakdown() {
        CostTracker t = new CostTracker();
        t.record("claude-sonnet-4-5", new CostTracker.Usage(1_000, 500));
        t.record("claude-opus-4", new CostTracker.Usage(1_000, 500));
        CostTracker.Summary s = t.summary();
        assertThat(s.byModel()).containsKeys("claude-sonnet-4-5", "claude-opus-4");
        assertThat(s.byModel().get("claude-sonnet-4-5").inputTokens()).isEqualTo(1_000);
        assertThat(s.byModel().get("claude-opus-4").inputTokens()).isEqualTo(1_000);
    }

    @Test
    void customPriceOverrides() {
        CostTracker t = new CostTracker();
        t.setPrice("custom-model", 0.001, 0.002);
        t.record("custom-model", new CostTracker.Usage(10_000, 5_000));
        assertThat(t.costFor("custom-model", 10_000, 5_000)).isCloseTo(0.020, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void recordUnknownModel() {
        CostTracker t = new CostTracker();
        t.record("never-priced", new CostTracker.Usage(100, 100));
        CostTracker.Summary s = t.summary();
        assertThat(s.totalInput()).isEqualTo(100);
        // cost is 0 because no price entry
        assertThat(s.byModel().get("never-priced").costUsd()).isZero();
    }

    @Test
    void resetClears() {
        CostTracker t = new CostTracker();
        t.record("claude-sonnet-4-5", new CostTracker.Usage(1_000, 500));
        t.reset();
        CostTracker.Summary s = t.summary();
        assertThat(s.totalInput()).isZero();
        assertThat(s.totalOutput()).isZero();
    }

    @Test
    void usageTotalIsInputPlusOutput() {
        CostTracker.Usage u = new CostTracker.Usage(100, 200);
        assertThat(u.total()).isEqualTo(300);
    }

    @Test
    void negativeUsageClampedToZero() {
        CostTracker.Usage u = new CostTracker.Usage(-5, -10);
        assertThat(u.inputTokens()).isZero();
        assertThat(u.outputTokens()).isZero();
    }
}
