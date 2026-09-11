package org.aethercode.permission.categorize;

import java.util.List;
import java.util.regex.Pattern;

/**
 * T-230 / T-232 / design.md §3.2: a single categorisation rule.
 *
 * <p>A rule says: "if a tool call matches this matcher, emit
 * these category tokens and treat the call as this risk". The
 * default rule table ({@link DefaultRules}) ships a curated set
 * aligned with the spec's table; users can extend it (T-232) by
 * passing extra {@code Rule}s to {@link RiskCategorizer#withRule}.
 *
 * <p>Backed by a Java sealed type so the default {@code matches}
 * contract can be implemented as either:
 * <ul>
 *   <li>{@link ToolNameRule} — match the tool name (with a
 *       single wildcard {@code *} suffix supported); OR</li>
 *   <li>{@link ArgRegexRule} — match a regex against an
 *       argument value (e.g. the {@code command} of a
 *       {@code bash} call).</li>
 * </ul>
 *
 * <p>Both produce the same {@link Match} (a risk + category
 * list) so the categorizer's loop is uniform.
 */
public sealed interface Rule
        permits Rule.ToolNameRule, Rule.ArgRegexRule {

    /** Stable id for the rule — used by {@code matchedRules} so
     *  the {@code ConsentPrompt} can show "matched rule
     *  bash_destructive" instead of "rule #5". */
    String id();

    /** The risk to assign when this rule fires. The
     *  categorizer takes the max of every matched rule's risk. */
    Risk risk();

    /** The categories to emit when this rule fires (duplicates
     *  collapsed by the result builder). */
    List<String> categories();

    /** Run the matcher against a call. Returns {@code null}
     *  when the rule doesn't apply. */
    Match matches(ToolCall call);

    /** Successful match. */
    record Match(List<String> categories, Risk risk) {
        public Match {
            categories = categories == null ? List.of() : List.copyOf(categories);
            risk = risk == null ? Risk.LOW : risk;
        }
    }

    /** Match by tool name. Supports a single trailing {@code *}
     *  for prefix matching (e.g. {@code "mcp_*"} matches any
     *  tool whose name starts with {@code "mcp_"}). */
    record ToolNameRule(String id, String toolPattern, Risk risk, List<String> categories)
            implements Rule {

        private static final Pattern WILDCARD_TAIL =
                Pattern.compile("^(.*)\\*$");

        public ToolNameRule {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id blank");
            if (toolPattern == null || toolPattern.isBlank()) {
                throw new IllegalArgumentException("toolPattern blank");
            }
            if (risk == null) risk = Risk.MEDIUM;
            categories = categories == null ? List.of() : List.copyOf(categories);
        }

        @Override
        public Match matches(ToolCall call) {
            if (call == null) return null;
            String t = call.tool();
            // Exact match first (fast path, no allocation).
            if (toolPattern.equals(t)) {
                return new Match(categories, risk);
            }
            // Wildcard: "mcp_*" -> prefix "mcp_"
            var m = WILDCARD_TAIL.matcher(toolPattern);
            if (m.matches()) {
                String prefix = m.group(1);
                if (t.startsWith(prefix)) {
                    return new Match(categories, risk);
                }
            }
            return null;
        }
    }

    /** Match by regex against a named argument (e.g.
     *  {@code argName="command"}). The regex is compiled once
     *  in the constructor. */
    record ArgRegexRule(String id,
                        String argName,
                        Pattern pattern,
                        Risk risk,
                        List<String> categories)
            implements Rule {

        public ArgRegexRule(String id, String argName, String regex,
                            Risk risk, List<String> categories) {
            this(id, argName,
                    regex == null ? null : Pattern.compile(regex),
                    risk, categories);
        }

        public ArgRegexRule {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id blank");
            if (argName == null || argName.isBlank()) {
                throw new IllegalArgumentException("argName blank");
            }
            if (pattern == null) throw new IllegalArgumentException("pattern null");
            if (risk == null) risk = Risk.MEDIUM;
            categories = categories == null ? List.of() : List.copyOf(categories);
        }

        @Override
        public Match matches(ToolCall call) {
            if (call == null) return null;
            String value = call.argString(argName);
            if (value == null) return null;
            if (pattern.matcher(value).find()) {
                return new Match(categories, risk);
            }
            return null;
        }
    }
}
