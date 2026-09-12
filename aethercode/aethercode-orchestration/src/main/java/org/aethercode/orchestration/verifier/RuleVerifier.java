package org.aethercode.orchestration.verifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rule verifier — runs a list of {@link Rule}s over a string input.
 *
 * <p>Each rule is either a "must contain" / "must match" (pass) or
 * a "must not contain" / "must not match" (forbid). The verifier
 * fails on the first rule that does not hold, and reports which one.</p>
 *
 * <p>Designed for the cheap, deterministic checks you want to run on
 * every LLM output before it goes to a tool:</p>
 * <ul>
 *   <li>Action must be one of the allowed values</li>
 *   <li>Code must not contain {@code os.system} or {@code eval(}</li>
 *   <li>Free-form text must contain a citation</li>
 *   <li>Email / phone / URL must match a regex</li>
 * </ul>
 *
 * <p>For more complex grammars use a real parser; for the common case
 * of "does this string satisfy these few invariants", this is enough.</p>
 */
public class RuleVerifier implements Verifier<String> {

    public enum Kind { MUST_CONTAIN, MUST_NOT_CONTAIN, MUST_MATCH, MUST_NOT_MATCH }

    public record Rule(Kind kind, String pattern, String description) {
        public Rule {
            if (kind == null) throw new IllegalArgumentException("kind must be non-null");
            if (pattern == null) throw new IllegalArgumentException("pattern must be non-null");
            if (description == null) description = kind + ":" + pattern;
        }
    }

    private final String name;
    private final List<Rule> rules;
    private final Verifier.Severity severity;

    public RuleVerifier(String name, List<Rule> rules) {
        this(name, rules, Verifier.Severity.BLOCK);
    }

    public RuleVerifier(String name, List<Rule> rules, Verifier.Severity severity) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("rules must be non-empty");
        }
        this.name = name;
        this.rules = List.copyOf(rules);
        this.severity = severity == null ? Verifier.Severity.BLOCK : severity;
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() {
        return "Rule check: " + rules.size() + " rule(s)";
    }

    @Override
    public VerificationResult verify(String input) {
        if (input == null) {
            return VerificationResult.fail(severity, "input is null",
                    Map.of("verifier", name));
        }
        for (Rule rule : rules) {
            boolean matches = match(rule, input);
            boolean hold = switch (rule.kind()) {
                case MUST_CONTAIN, MUST_MATCH -> matches;
                case MUST_NOT_CONTAIN, MUST_NOT_MATCH -> !matches;
            };
            if (!hold) {
                return VerificationResult.fail(severity,
                        "rule failed (" + rule.kind() + ": " + rule.pattern() + "): " + rule.description(),
                        Map.of("verifier", name, "rule", rule.pattern(), "kind", rule.kind().toString()));
            }
        }
        return VerificationResult.pass("all " + rules.size() + " rule(s) satisfied",
                Map.of("verifier", name, "rules_checked", rules.size()));
    }

    static boolean match(Rule rule, String input) {
        return switch (rule.kind()) {
            case MUST_CONTAIN, MUST_NOT_CONTAIN -> input.contains(rule.pattern());
            case MUST_MATCH, MUST_NOT_MATCH -> Pattern.compile(rule.pattern()).matcher(input).find();
        };
    }

    public List<Rule> rules() { return rules; }
}
