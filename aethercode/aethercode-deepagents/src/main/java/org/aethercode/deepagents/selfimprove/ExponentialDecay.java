package org.aethercode.deepagents.selfimprove;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * R241.3 (O-3): exponential utility decay with a half-life.
 *
 * <pre>
 *   u_eff = u * 2^(-Δseconds / halfLifeSeconds)
 * </pre>
 *
 * <p>Useful for "use it or lose it" semantics: a unit that
 * has not been recalled for {@code halfLife} has its utility
 * halved; for two half-lives, quartered; and so on. The shape
 * matches Ebbinghaus's forgetting curve, which the AI Agent
 * memory survey (arXiv:2512.13564 §5.2.3) cites as the
 * empirical reference for agent memory decay.
 *
 * <h2>Boundary behaviour</h2>
 *
 * <ul>
 *   <li>{@code halfLife <= 0} → {@link IllegalArgumentException}.</li>
 *   <li>{@code now < createdAt} (clock skew) → treat as 0 elapsed
 *       and return the stored utility.</li>
 *   <li>{@code utility == 0} → return 0 (no surprise negatives).</li>
 * </ul>
 */
public final class ExponentialDecay implements UtilityDecay {

    private final long halfLifeSeconds;

    public ExponentialDecay(Duration halfLife) {
        if (halfLife == null) throw new IllegalArgumentException("halfLife must not be null");
        if (halfLife.isZero() || halfLife.isNegative()) {
            throw new IllegalArgumentException("halfLife must be > 0, got " + halfLife);
        }
        this.halfLifeSeconds = halfLife.getSeconds();
    }

    @Override
    public double effectiveUtility(ReasoningUnit unit, Instant now) {
        if (unit == null) return 0.0;
        double base = unit.utility();
        if (base <= 0.0) return 0.0;
        Instant t = now == null ? Instant.now() : now;
        long elapsedSeconds = Math.max(0L,
                TimeUnit.MILLISECONDS.toSeconds(t.toEpochMilli() - unit.createdAt().toEpochMilli()));
        // 2^(-x) = exp(-x * ln 2).  Use Math.pow for readability;
        // the call is dominated by the surrounding recall sort.
        double factor = Math.pow(2.0, -((double) elapsedSeconds / (double) halfLifeSeconds));
        double u = base * factor;
        if (u < 0.0) return 0.0;
        if (u > 1.0) return 1.0;
        return u;
    }
}
