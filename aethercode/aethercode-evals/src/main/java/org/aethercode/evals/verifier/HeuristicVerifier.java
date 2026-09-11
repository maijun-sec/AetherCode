package org.aethercode.evals.verifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Heuristic verifier — runs a list of cheap numeric / structural
 * checks over a string input.
 *
 * <p>Where {@link RuleVerifier} is about content ("does it contain X"),
 * this is about shape ("how long is it", "is it repetitive", "does it
 * have a coherent structure"). Used to catch the failure modes the
 * paper calls out: long-horizon drift, repetitive loops, truncated
 * output, or the "looks-good" failure pattern where a fluent
 * paragraph hides the absence of an answer.</p>
 *
 * <p>Built-in checks:</p>
 * <ul>
 *   <li>{@link MinLengthCheck} / {@link MaxLengthCheck} — char count
 *       bounds (catches truncation and runaway generation)</li>
 *   <li>{@link RepetitionCheck} — flags output where the same N-gram
 *       repeats more than K times (catches loop / mode-collapse)</li>
 *   <li>{@link NonEmptyCheck} — fails on empty / whitespace-only</li>
 * </ul>
 */
public class HeuristicVerifier implements Verifier<String> {

    public interface Check {
        String name();
        /** Returns null on pass, or a failure reason. */
        String apply(String input);
    }

    public record MinLengthCheck(int min) implements Check {
        public MinLengthCheck {
            if (min < 0) throw new IllegalArgumentException("min must be >= 0");
        }
        @Override public String name() { return "min_length[" + min + "]"; }
        @Override public String apply(String input) {
            if (input.length() < min) return "input is " + input.length() + " chars, expected >= " + min;
            return null;
        }
    }

    public record MaxLengthCheck(int max) implements Check {
        public MaxLengthCheck {
            if (max < 0) throw new IllegalArgumentException("max must be >= 0");
        }
        @Override public String name() { return "max_length[" + max + "]"; }
        @Override public String apply(String input) {
            if (input.length() > max) return "input is " + input.length() + " chars, expected <= " + max;
            return null;
        }
    }

    public record NonEmptyCheck() implements Check {
        @Override public String name() { return "non_empty"; }
        @Override public String apply(String input) {
            if (input.strip().isEmpty()) return "input is empty or whitespace";
            return null;
        }
    }

    /**
     * Flags output where the same N-character substring repeats more
     * than {@code maxRepetitions} times consecutively. Default N=50
     * (a sentence-ish chunk) is sensitive to loops without false-
     * flagging normal language.
     */
    public record RepetitionCheck(int n, int maxRepetitions) implements Check {
        public RepetitionCheck {
            if (n < 4) throw new IllegalArgumentException("n must be >= 4");
            if (maxRepetitions < 2) throw new IllegalArgumentException("maxRepetitions must be >= 2");
        }
        public RepetitionCheck(int maxRepetitions) { this(50, maxRepetitions); }
        @Override public String name() { return "repetition[n=" + n + ",max=" + maxRepetitions + "]"; }
        @Override public String apply(String input) {
            if (input.length() < n) return null;
            for (int i = 0; i + n <= input.length(); i++) {
                String chunk = input.substring(i, i + n);
                int reps = 1;
                int j = i + n;
                while (j + n <= input.length() && input.substring(j, j + n).equals(chunk)) {
                    reps++;
                    j += n;
                }
                if (reps > maxRepetitions) {
                    return "n-gram '" + preview(chunk) + "' repeated " + reps +
                            " times (max " + maxRepetitions + ")";
                }
            }
            return null;
        }
        private static String preview(String s) {
            return s.length() <= 30 ? s : s.substring(0, 30) + "...";
        }
    }

    private final String name;
    private final List<Check> checks;
    private final Verifier.Severity severity;

    public HeuristicVerifier(String name, List<Check> checks) {
        this(name, checks, Verifier.Severity.BLOCK);
    }

    public HeuristicVerifier(String name, List<Check> checks, Verifier.Severity severity) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (checks == null || checks.isEmpty()) {
            throw new IllegalArgumentException("checks must be non-empty");
        }
        this.name = name;
        this.checks = List.copyOf(checks);
        this.severity = severity == null ? Verifier.Severity.BLOCK : severity;
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return "Heuristic check: " + checks.size() + " check(s)"; }

    @Override
    public VerificationResult verify(String input) {
        if (input == null) {
            return VerificationResult.fail(severity, "input is null",
                    Map.of("verifier", name));
        }
        for (Check c : checks) {
            String reason = c.apply(input);
            if (reason != null) {
                return VerificationResult.fail(severity,
                        "check failed (" + c.name() + "): " + reason,
                        Map.of("verifier", name, "check", c.name()));
            }
        }
        return VerificationResult.pass("all " + checks.size() + " check(s) passed",
                Map.of("verifier", name, "checks_run", checks.size()));
    }

    public List<Check> checks() { return checks; }
}
