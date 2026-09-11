package org.aethercode.permission.categorize;

import org.aethercode.permission.categorize.Rule.Match;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T-230..T-234 / design.md §3.2: turn a {@link ToolCall} into a
 * {@link CategoryResult} (categories + risk + matched rules).
 *
 * <p>The categorizer holds an ordered list of {@link Rule}s
 * (defaults from {@link DefaultRules#all()}). Each call to
 * {@link #categorize} walks the list in order, accumulates every
 * rule that matches, and returns a {@code CategoryResult} with:
 * <ul>
 *   <li>the <em>union</em> of every matched rule's categories
 *       (de-duplicated, insertion order preserved);</li>
 *   <li>the <em>max</em> of every matched rule's risk
 *       (see {@link Risk#max});</li>
 *   <li>the list of matched rule ids (in evaluation order) for
 *       the {@code ConsentPrompt} to render in the help view.</li>
 * </ul>
 *
 * <p>T-232: callers can extend the rule table by passing extra
 * rules to {@link #withRule} / {@link #withRules}. User rules
 * are evaluated <em>after</em> the defaults, so a user rule can
 * <em>override</em> a default by adding a higher-risk category
 * but cannot suppress a default (the union is the safe
 * behaviour).
 *
 * <p>T-234: the {@code matchedRules} field is the explainability
 * hook. The {@code ConsentPrompt} calls
 * {@code result.matchedRules()} to render "this call matched
 * rule {@code shell_destructive}, which marks destructive
 * shell commands as high risk".
 *
 * <p>Thread-safety: the rule list is held in a
 * {@link CopyOnWriteArrayList} so {@code withRule} can be
 * called from any thread without locking the read path.
 */
public final class RiskCategorizer {

    private final List<Rule> rules;

    public RiskCategorizer() {
        this(DefaultRules.all());
    }

    public RiskCategorizer(List<Rule> rules) {
        Objects.requireNonNull(rules, "rules");
        // Defensive copy + drop nulls.
        List<Rule> copy = new ArrayList<>(rules.size());
        for (Rule r : rules) {
            if (r != null) copy.add(r);
        }
        // The rules list must be thread-safe for reads (we
        // walk it from arbitrary threads when categorizing
        // a tool call) AND for writes (withRule is callable
        // from the runtime as the user extends the table).
        // CopyOnWriteArrayList is the right tool — reads
        // are lock-free and never see a half-applied
        // mutation. {@link #rules()} returns a true snapshot
        // (a List.copyOf) so callers can iterate without
        // worrying about a concurrent withRule.
        this.rules = new CopyOnWriteArrayList<>(copy);
    }

    /** T-232: add a single rule. Returns {@code this} so calls
     *  can be chained. The new rule is evaluated AFTER every
     *  rule already in the table (so it can add a category but
     *  cannot suppress a default that already fired). */
    public RiskCategorizer withRule(Rule rule) {
        Objects.requireNonNull(rule, "rule");
        this.rules.add(rule);
        return this;
    }

    /** T-232: bulk add. Same semantics as {@link #withRule}. */
    public RiskCategorizer withRules(List<Rule> more) {
        Objects.requireNonNull(more, "more");
        for (Rule r : more) {
            if (r != null) this.rules.add(r);
        }
        return this;
    }

    /** Read-only snapshot of the current rule table. The
     *  returned list is a true copy (not a live view), so a
     *  concurrent {@link #withRule} call does not mutate the
     *  snapshot the caller is iterating. */
    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    /** T-231: classify {@code call}. See the class-level javadoc
     *  for the accumulation rules. */
    public CategoryResult categorize(ToolCall call) {
        Objects.requireNonNull(call, "call");
        CategoryResult acc = CategoryResult.empty();
        for (Rule r : rules) {
            Match m = r.matches(call);
            if (m == null) continue;
            acc = acc.withRule(r.id(), m.risk(), m.categories());
        }
        return acc;
    }
}
