package org.aethercode.deepagents.selfimprove;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * R241.3 (O-3): linear utility decay, dropping by a fixed
 * amount per day elapsed.
 *
 * <pre>
 *   u_eff = clamp(u - perDay * Δdays, 0, 1)
 * </pre>
 *
 * <p>Linear decay is the simplest "I forget a constant amount
 * per day" model. It is the right choice when a unit's value
 * should taper predictably over time without the
 * "long-tail-but-barely-there" shape of an exponential curve.
 * For long-running offline pipelines where a reflection
 * should stay useful for a known window (e.g. "this
 * Spring-AI-1.x quirk is relevant for ~14 days"), a linear
 * policy with {@code perDay = 1/14} is more interpretable
 * than an exponential half-life.
 *
 * <h2>Boundary behaviour</h2>
 *
 * <ul>
 *   <li>{@code perDay <= 0} → {@link IllegalArgumentException}.</li>
 *   <li>Result is clamped to {@code [0, 1]}: utility cannot
 *       go negative, and overshoots above 1 are capped.</li>
 *   <li>{@code now < createdAt} (clock skew) → treat as 0 elapsed.</li>
 * </ul>
 */
public final class LinearDecay implements UtilityDecay {

    private final double perDay;

    public LinearDecay(double perDay) {
        if (Double.isNaN(perDay) || perDay <= 0.0) {
            throw new IllegalArgumentException("perDay must be > 0, got " + perDay);
        }
        this.perDay = perDay;
    }

    @Override
    public double effectiveUtility(ReasoningUnit unit, Instant now) {
        if (unit == null) return 0.0;
        double base = unit.utility();
        if (base <= 0.0) return 0.0;
        Instant t = now == null ? Instant.now() : now;
        long elapsedMillis = Math.max(0L,
                t.toEpochMilli() - unit.createdAt().toEpochMilli());
        double elapsedDays = TimeUnit.MILLISECONDS.toDays(elapsedMillis)
                + (elapsedMillis % 86_400_000L) / 86_400_000.0;
        double u = base - perDay * elapsedDays;
        if (u < 0.0) return 0.0;
        if (u > 1.0) return 1.0;
        return u;
    }
}
