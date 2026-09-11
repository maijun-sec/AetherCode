package org.aethercode.evals.harbor;

/**
 * Statistical utilities for eval score reporting.
 *
 * <p>Provides Wilson score confidence intervals and minimum detectable effect
 * estimation, as recommended by Anthropic's infrastructure noise research.
 * Java 21 port of {@code deepagents_harbor.stats}.</p>
 */
public final class Stats {

    private Stats() {}

    /**
     * Compute the Wilson score confidence interval for a binomial proportion.
     *
     * <p>More accurate than the normal approximation for small samples and
     * proportions near 0 or 1. Recommended by Anthropic's infrastructure
     * noise research for eval score reporting.</p>
     *
     * @param successes number of successes (e.g., passed tasks)
     * @param total     total number of trials
     * @param z         z-score for desired confidence level ({@code 1.96 = 95% CI})
     * @return a {@code double[2]} of {@code [lower_bound, upper_bound]} as
     *         proportions in {@code [0, 1]}
     */
    public static double[] wilsonCi(int successes, int total, double z) {
        if (total == 0) {
            return new double[]{0.0, 0.0};
        }
        double p = (double) successes / total;
        double z2 = z * z;
        double denom = 1.0 + z2 / total;
        double center = (p + z2 / (2.0 * total)) / denom;
        double margin = (z / denom) * Math.sqrt(p * (1.0 - p) / total + z2 / (4.0 * total * total));
        return new double[]{Math.max(0.0, center - margin), Math.min(1.0, center + margin)};
    }

    /** Convenience overload using the 95% CI z-score ({@code 1.96}). */
    public static double[] wilsonCi(int successes, int total) {
        return wilsonCi(successes, total, 1.96);
    }

    /**
     * Format a success rate with a Wilson confidence interval.
     *
     * <p>Example output: {@code "72.3% [68.1%, 76.2%] (95% CI, n=90)"}.</p>
     *
     * @param successes number of successes
     * @param total     total number of trials
     * @param z         z-score for desired confidence level
     * @return formatted string like {@code "72.3% [68.1%, 76.2%] (95% CI, n=90)"}
     */
    public static String formatCi(int successes, int total, double z) {
        if (total == 0) {
            return "N/A (no trials)";
        }
        double rate = (successes / (double) total) * 100.0;
        double[] ci = wilsonCi(successes, total, z);
        double confidence = erf(z / Math.sqrt(2.0)) * 100.0;
        return String.format(
                "%.1f%% [%.1f%%, %.1f%%] (%.0f%% CI, n=%d)",
                rate, ci[0] * 100.0, ci[1] * 100.0, confidence, total);
    }

    /**
     * Gaussian error function. Java 17+ exposes {@code Math.erf} on
     * OpenJDK / Hotspot, but the function is missing on some
     * pre-JDK-21 runtimes; the closed-form Abramowitz &amp; Stegun
     * approximation keeps the port self-contained.
     *
     * @param x input
     * @return erf(x)
     */
    static double erf(double x) {
        // Numerical Recipes Chebyshev approximation; max error ~1.2e-7.
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double ans = t * Math.exp(-x * x - 1.26551223
                + t * (1.00002368
                + t * (0.37409196
                + t * (0.09678418
                + t * (-0.18628806
                + t * (0.27886807
                + t * (-1.13520398
                + t * (1.48851587
                + t * (-0.82215223
                + t * 0.17087277)))))))));
        return x >= 0 ? 1.0 - ans : ans - 1.0;
    }

    /** Convenience overload using the 95% CI z-score ({@code 1.96}). */
    public static String formatCi(int successes, int total) {
        return formatCi(successes, total, 1.96);
    }

    /**
     * Estimate the minimum detectable effect size for a given sample count.
     *
     * <p>The MDE is the smallest difference in success rates between two
     * runs that can be considered statistically significant. If two runs
     * score 72% and 78% but the MDE is 14pp, that 6pp gap is
     * indistinguishable from noise at the chosen confidence level.</p>
     *
     * <p>Derived from the standard error of the difference between two
     * independent proportions: {@code MDE = z * sqrt(2 * p * (1-p) / n)}.
     * Assumes equal sample sizes in both runs. Defaults to
     * {@code p=0.5} because that maximizes {@code p*(1-p)}, giving the
     * most conservative (widest) estimate.</p>
     *
     * @param total number of tasks per run (assumes both runs have the same count)
     * @param z     z-score for desired confidence level ({@code 1.96 = 95% CI})
     * @param p     assumed base proportion; {@code 0.5} is the conservative default
     *              since it maximizes variance
     * @return the minimum detectable difference as a proportion
     *         (e.g., {@code 0.042 = 4.2pp})
     */
    public static double minDetectableEffect(int total, double z, double p) {
        if (total == 0) {
            return 1.0;
        }
        // Two-sample proportion test: MDE ~= z * sqrt(2 * p * (1-p) / n)
        return z * Math.sqrt(2.0 * p * (1.0 - p) / total);
    }

    /** Convenience overload using {@code z=1.96} and {@code p=0.5}. */
    public static double minDetectableEffect(int total) {
        return minDetectableEffect(total, 1.96, 0.5);
    }
}
