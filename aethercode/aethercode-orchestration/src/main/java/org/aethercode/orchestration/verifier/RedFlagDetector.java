package org.aethercode.orchestration.verifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Paper 2511.09030 MAKER-style red-flag detector.
 * <p>
 * Detects high-risk agent outputs that should be rejected and re-sampled, even
 * if the output is technically parseable. The paper found that red-flagging
 * is essential for achieving zero-error on long-horizon tasks.
 * <p>
 * The detector checks 5 heuristics:
 * <ol>
 *   <li>Length bounds (too short or too long)</li>
 *   <li>Empty or whitespace-only</li>
 *   <li>Repeated character (e.g. 20 'a's in a row)</li>
 *   <li>JSON / structure malformed (curly / bracket imbalance)</li>
 *   <li>Contradictory content (heuristic: "yes" + "no" in same response)</li>
 * </ol>
 */
public final class RedFlagDetector {

    public enum Severity { LOW, MEDIUM, HIGH }

    public record RedFlag(String rule, Severity severity, String detail) {
        public RedFlag {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public record Thresholds(
        int minLength,
        int maxLength,
        int maxRepeatRun
    ) {
        public static Thresholds defaults() {
            return new Thresholds(5, 50_000, 20);
        }
    }

    private final Thresholds thresholds;

    public RedFlagDetector() {
        this(Thresholds.defaults());
    }

    public RedFlagDetector(Thresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /** Inspect a single output, return all red flags. */
    public List<RedFlag> inspect(String output) {
        List<RedFlag> flags = new ArrayList<>();
        if (output == null) {
            flags.add(new RedFlag("NULL_OUTPUT", Severity.HIGH, "output is null"));
            return flags;
        }
        // Rule 1: empty / whitespace
        if (output.trim().isEmpty()) {
            flags.add(new RedFlag("EMPTY", Severity.HIGH, "output is empty or whitespace"));
        }
        // Rule 2: too short
        if (output.length() < thresholds.minLength) {
            flags.add(new RedFlag("TOO_SHORT", Severity.MEDIUM,
                "length=" + output.length() + " < min=" + thresholds.minLength));
        }
        // Rule 3: too long
        if (output.length() > thresholds.maxLength) {
            flags.add(new RedFlag("TOO_LONG", Severity.MEDIUM,
                "length=" + output.length() + " > max=" + thresholds.maxLength));
        }
        // Rule 4: repeated character
        if (hasLongRepeatRun(output, thresholds.maxRepeatRun)) {
            flags.add(new RedFlag("REPEAT_RUN", Severity.HIGH,
                "found character repeated >= " + thresholds.maxRepeatRun + " times"));
        }
        // Rule 5: bracket imbalance
        if (hasImbalancedBrackets(output)) {
            flags.add(new RedFlag("BRACKET_IMBALANCE", Severity.HIGH,
                "curly/bracket imbalance detected"));
        }
        // Rule 6: contradiction (yes + no in same response)
        if (looksContradictory(output)) {
            flags.add(new RedFlag("CONTRADICTION", Severity.MEDIUM,
                "yes + no found in same response"));
        }
        return flags;
    }

    /** True if any red flag is HIGH severity. */
    public boolean isRedFlagged(List<RedFlag> flags) {
        return flags.stream().anyMatch(f -> f.severity() == Severity.HIGH);
    }

    /** Convenience: inspect + check. */
    public boolean isRedFlagged(String output) {
        return isRedFlagged(inspect(output));
    }

    private static boolean hasLongRepeatRun(String s, int maxRun) {
        if (s.isEmpty()) return false;
        char prev = s.charAt(0);
        int run = 1;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == prev) {
                run++;
                if (run >= maxRun) return true;
            } else {
                prev = s.charAt(i);
                run = 1;
            }
        }
        return false;
    }

    private static final Pattern YES = Pattern.compile("\\byes\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO = Pattern.compile("\\bno\\b", Pattern.CASE_INSENSITIVE);

    private static boolean looksContradictory(String s) {
        return YES.matcher(s).find() && NO.matcher(s).find();
    }

    private static boolean hasImbalancedBrackets(String s) {
        int curly = 0, square = 0, paren = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') curly++;
            else if (c == '}') curly--;
            else if (c == '[') square++;
            else if (c == ']') square--;
            else if (c == '(') paren++;
            else if (c == ')') paren--;
        }
        return curly != 0 || square != 0 || paren != 0;
    }
}
