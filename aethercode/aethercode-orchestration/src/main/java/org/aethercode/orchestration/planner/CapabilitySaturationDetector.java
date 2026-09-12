package org.aethercode.orchestration.planner;

import java.util.List;
import java.util.Objects;

/**
 * Paper 2512.08296-style capability saturation detector.
 * <p>
 * Given a series of single-agent runs, estimates whether the model has saturated
 * its single-agent capability. The paper measured a 0.45 inflection point: above
 * that, multi-agent stops yielding gains.
 * <p>
 * This implementation uses two heuristics:
 * <ol>
 *   <li>Mean single-agent success rate</li>
 *   <li>Variance across runs (low variance = saturated)</li>
 * </ol>
 */
public final class CapabilitySaturationDetector {

    /** Result of a single run. */
    public record RunResult(double successScore) {
        public RunResult {
            if (successScore < 0.0 || successScore > 1.0) {
                throw new IllegalArgumentException("successScore must be 0..1");
            }
        }
    }

    /** A saturation assessment. */
    public record SaturationReport(
        double meanScore,
        double variance,
        double saturationLevel, // 0..1
        boolean isSaturated,
        String recommendation
    ) {}

    private final double saturationThreshold;

    public CapabilitySaturationDetector() {
        this(0.45);
    }

    public CapabilitySaturationDetector(double saturationThreshold) {
        this.saturationThreshold = saturationThreshold;
    }

    public SaturationReport assess(List<RunResult> runs) {
        Objects.requireNonNull(runs, "runs");
        if (runs.isEmpty()) {
            return new SaturationReport(0.0, 0.0, 0.0, false, "no runs provided");
        }
        double mean = mean(runs);
        double variance = variance(runs, mean);
        // Saturation level: weighted combination of mean and (1 - variance)
        // High mean + low variance = high saturation
        double level = 0.6 * mean + 0.4 * (1.0 - Math.min(1.0, variance * 2.0));
        boolean saturated = mean >= saturationThreshold;
        String rec = saturated
            ? "Saturated (mean=" + String.format("%.2f", mean) + " >= " + saturationThreshold + "); multi-agent unlikely to help"
            : "Below saturation (mean=" + String.format("%.2f", mean) + " < " + saturationThreshold + "); multi-agent may add value";
        return new SaturationReport(mean, variance, level, saturated, rec);
    }

    /** 0.45 inflection from paper 2512.08296. */
    public double getSaturationThreshold() {
        return saturationThreshold;
    }

    private static double mean(List<RunResult> runs) {
        double sum = 0;
        for (RunResult r : runs) sum += r.successScore();
        return sum / runs.size();
    }

    private static double variance(List<RunResult> runs, double mean) {
        if (runs.size() < 2) return 0.0;
        double sum = 0;
        for (RunResult r : runs) {
            double d = r.successScore() - mean;
            sum += d * d;
        }
        return sum / runs.size();
    }
}
