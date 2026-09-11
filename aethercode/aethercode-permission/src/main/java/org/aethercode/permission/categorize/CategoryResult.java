package org.aethercode.permission.categorize;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * T-230 / T-234 / design.md §3.2: the output of a categorisation.
 *
 * <pre>
 * type CategoryResult = {
 *   categories: string[];         // e.g. ["shell.command", "shell.package_install"]
 *   risk: "low" | "medium" | "high";
 *   matchedRules: string[];       // for explainability
 * };
 * </pre>
 *
 * <p>{@code categories} holds the union of every category emitted
 * by every rule that matched the call (preserves insertion order
 * so debug output is stable). {@code matchedRules} holds the rule
 * ids that fired, in the order they fired — used by the
 * {@code ConsentPrompt} to render "why is this risky?" tooltips.
 *
 * <p>{@code risk} is the max of every matched rule's risk, with
 * {@link Risk#LOW} as the default when no rule matched (which is
 * the right answer for an unknown tool — we don't know it is
 * risky, so we let the rest of the pipeline decide; the
 * downstream {@code ConsentChecker} will still treat an unknown
 * tool as medium by default).
 */
public final class CategoryResult {

    private final List<String> categories;
    private final Risk risk;
    private final List<String> matchedRules;

    public CategoryResult(List<String> categories, Risk risk, List<String> matchedRules) {
        // De-dupe categories (a rule may emit shell.command and
        // another emit shell.command too — the resolver looks
        // up by exact category string, so duplicates don't add
        // information, they just bloat the explanation).
        Set<String> catSet = new LinkedHashSet<>();
        if (categories != null) catSet.addAll(categories);
        this.categories = List.copyOf(catSet);
        this.risk = risk == null ? Risk.LOW : risk;
        this.matchedRules = matchedRules == null
                ? List.of()
                : List.copyOf(matchedRules);
    }

    public List<String> categories() { return categories; }
    public Risk risk() { return risk; }
    public List<String> matchedRules() { return matchedRules; }

    /** True when at least one rule fired. A result with no
     *  matched rules means the categorizer couldn't classify
     *  the call — the downstream consent checker will treat it
     *  as medium by default (see {@code ConsentChecker}). */
    public boolean hasMatch() {
        return !matchedRules.isEmpty();
    }

    /** Render a one-line human summary for logs / debug. */
    public String explain() {
        if (matchedRules.isEmpty()) {
            return "no rule matched (default: " + risk + ")";
        }
        return risk + " via " + String.join("+", matchedRules)
                + " -> " + String.join(",", categories);
    }

    @Override
    public String toString() {
        return "CategoryResult{risk=" + risk + ", categories=" + categories
                + ", matchedRules=" + matchedRules + "}";
    }

    /** Empty result: no categories, no matched rules, risk LOW.
     *  Used as the seed for builder-style accumulation. */
    public static CategoryResult empty() {
        return new CategoryResult(List.of(), Risk.LOW, List.of());
    }

    /** Immutable accumulator. Every {@code with*} method returns
     *  a fresh {@code CategoryResult}. */
    public CategoryResult withCategory(String category) {
        if (category == null || category.isBlank()) return this;
        if (categories.contains(category)) return this;
        java.util.ArrayList<String> next = new java.util.ArrayList<>(categories);
        next.add(category);
        return new CategoryResult(next, risk, matchedRules);
    }

    public CategoryResult withRule(String ruleId, Risk ruleRisk, java.util.List<String> ruleCategories) {
        if (ruleId == null || ruleId.isBlank()) return this;
        java.util.ArrayList<String> nextCats = new java.util.ArrayList<>(categories);
        if (ruleCategories != null) {
            for (String c : ruleCategories) {
                if (c != null && !c.isBlank() && !nextCats.contains(c)) {
                    nextCats.add(c);
                }
            }
        }
        java.util.ArrayList<String> nextRules = new java.util.ArrayList<>(matchedRules);
        nextRules.add(ruleId);
        return new CategoryResult(
                nextCats,
                Risk.max(this.risk, ruleRisk),
                nextRules);
    }

    /** Render an immutable list, tolerating a null input. */
    static <T> List<T> safeList(List<T> in) {
        if (in == null) return List.of();
        return List.copyOf(in);
    }
}
