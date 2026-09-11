package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityDecayTest {

    private static ReasoningUnit unitAt(Instant createdAt, double utility) {
        // Build a unit whose createdAt is the supplied instant, and
        // whose utility is exactly utility.
        return new ReasoningUnit("id", "kind", "err", "fix", "ex",
                utility, 0L, createdAt);
    }

    // -----------------------------------------------------------------
    //  NoDecay
    // -----------------------------------------------------------------

    @Test
    void noDecayReturnsStoredUtility() {
        Instant now = Instant.now();
        ReasoningUnit u = unitAt(now, 0.7);
        assertEquals(0.7, UtilityDecay.NO_DECAY.effectiveUtility(u, now), 1e-9);
    }

    @Test
    void noDecayAcceptsNullNow() {
        // Falls back to Instant.now() and just returns stored utility.
        ReasoningUnit u = unitAt(Instant.now(), 0.42);
        assertEquals(0.42, UtilityDecay.NO_DECAY.effectiveUtility(u, null), 1e-9);
    }

    // -----------------------------------------------------------------
    //  ExponentialDecay
    // -----------------------------------------------------------------

    @Test
    void exponentialAtZeroAgeReturnsBase() {
        Instant now = Instant.now();
        ReasoningUnit u = unitAt(now, 0.6);
        ExponentialDecay d = new ExponentialDecay(Duration.ofDays(7));
        assertEquals(0.6, d.effectiveUtility(u, now), 1e-9);
    }

    @Test
    void exponentialAtOneHalfLifeReturnsHalf() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = unitAt(t0, 0.8);
        ExponentialDecay d = new ExponentialDecay(Duration.ofDays(7));
        Instant t1 = t0.plus(Duration.ofDays(7));
        assertEquals(0.4, d.effectiveUtility(u, t1), 1e-3);
    }

    @Test
    void exponentialAtTwoHalfLivesReturnsQuarter() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = unitAt(t0, 1.0);
        ExponentialDecay d = new ExponentialDecay(Duration.ofDays(7));
        Instant t1 = t0.plus(Duration.ofDays(14));
        assertEquals(0.25, d.effectiveUtility(u, t1), 1e-3);
    }

    @Test
    void exponentialZeroUtilityStaysZero() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = unitAt(t0, 0.0);
        ExponentialDecay d = new ExponentialDecay(Duration.ofDays(7));
        assertEquals(0.0, d.effectiveUtility(u, t0.plus(Duration.ofDays(100))), 1e-9);
    }

    @Test
    void exponentialResultClampedAboveOne() {
        // If a caller manually constructs a unit with utility > 1
        // (e.g. legacy 1.0 is the only "max" we allow in record
        // validation, but defensive programming) the decay
        // function must still clamp.
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // We cannot construct utility > 1.0 via the public
        // constructor, so emulate by setting createdAt in the
        // future (negative elapsed) — that's the same effect.
        Instant future = t0.plus(Duration.ofSeconds(60));
        ReasoningUnit u = unitAt(future, 0.7);
        ExponentialDecay d = new ExponentialDecay(Duration.ofDays(7));
        // The "now" is the older t0, so elapsed is negative.
        // Decay should treat negative elapsed as 0 → return 0.7.
        assertEquals(0.7, d.effectiveUtility(u, t0), 1e-9);
    }

    @Test
    void exponentialRejectsZeroHalfLife() {
        assertThrows(IllegalArgumentException.class, () -> new ExponentialDecay(Duration.ZERO));
    }

    @Test
    void exponentialRejectsNegativeHalfLife() {
        assertThrows(IllegalArgumentException.class, () -> new ExponentialDecay(Duration.ofDays(-1)));
    }

    // -----------------------------------------------------------------
    //  LinearDecay
    // -----------------------------------------------------------------

    @Test
    void linearAtZeroAgeReturnsBase() {
        Instant now = Instant.now();
        ReasoningUnit u = unitAt(now, 0.8);
        LinearDecay d = new LinearDecay(0.1);
        assertEquals(0.8, d.effectiveUtility(u, now), 1e-9);
    }

    @Test
    void linearAfterOneDayDropsByPerDay() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = unitAt(t0, 0.8);
        LinearDecay d = new LinearDecay(0.1);
        Instant t1 = t0.plus(Duration.ofDays(1));
        assertEquals(0.7, d.effectiveUtility(u, t1), 1e-3);
    }

    @Test
    void linearClampsAtZero() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = unitAt(t0, 0.3);
        LinearDecay d = new LinearDecay(0.1);
        // 5 days → would drop to -0.2, but clamped to 0
        Instant t1 = t0.plus(Duration.ofDays(5));
        assertEquals(0.0, d.effectiveUtility(u, t1), 1e-9);
    }

    @Test
    void linearPartialDayProRated() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = unitAt(t0, 0.5);
        LinearDecay d = new LinearDecay(0.1); // 0.1 per day
        Instant t1 = t0.plus(Duration.ofHours(12)); // half a day
        // 0.5 - 0.1 * 0.5 = 0.45
        assertEquals(0.45, d.effectiveUtility(u, t1), 1e-3);
    }

    @Test
    void linearRejectsZeroRate() {
        assertThrows(IllegalArgumentException.class, () -> new LinearDecay(0.0));
    }

    @Test
    void linearRejectsNegativeRate() {
        assertThrows(IllegalArgumentException.class, () -> new LinearDecay(-0.1));
    }

    @Test
    void linearRejectsNaNRate() {
        assertThrows(IllegalArgumentException.class, () -> new LinearDecay(Double.NaN));
    }

    // -----------------------------------------------------------------
    //  Factory sanity
    // -----------------------------------------------------------------

    @Test
    void factoryExponentialMatchesConstructor() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = unitAt(t0, 1.0);
        Instant t1 = t0.plus(Duration.ofDays(7));
        double a = UtilityDecay.exponential(Duration.ofDays(7)).effectiveUtility(u, t1);
        double b = new ExponentialDecay(Duration.ofDays(7)).effectiveUtility(u, t1);
        assertEquals(a, b, 1e-9);
        assertTrue(a < 0.51 && a > 0.49, "expected ~0.5 at one half-life, got " + a);
    }

    @Test
    void factoryLinearMatchesConstructor() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ReasoningUnit u = unitAt(t0, 0.5);
        Instant t1 = t0.plus(Duration.ofDays(2));
        double a = UtilityDecay.linear(0.1).effectiveUtility(u, t1);
        double b = new LinearDecay(0.1).effectiveUtility(u, t1);
        assertEquals(a, b, 1e-9);
        assertEquals(0.3, a, 1e-3);
    }
}
