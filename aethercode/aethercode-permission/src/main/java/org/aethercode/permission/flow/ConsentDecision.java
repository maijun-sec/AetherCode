package org.aethercode.permission.flow;

import org.aethercode.permission.categorize.CategoryResult;
import org.aethercode.permission.categorize.Risk;
import org.aethercode.permission.grants.Grant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T-240..T-244 / design.md §3.3: the outcome of a single
 * {@link ConsentChecker#check} call.
 *
 * <p>Three terminal outcomes:
 * <ul>
 *   <li>{@link Outcome#ALLOW} — the tool runs silently. The
 *       reason is set for audit-log purposes; consumers should
 *       NOT use it as a user-facing string.</li>
 *   <li>{@link Outcome#DENY} — the tool is blocked. The
 *       reason is shown to the user.</li>
 *   <li>{@link Outcome#PROMPT} — a consent prompt is needed.
 *       The {@link #categories} and {@link #risk} fields
 *       are populated so the {@code ConsentPrompt} can render
 *       the right help text.</li>
 * </ul>
 *
 * <p>Sealed type so callers handle all three branches.
 */
public sealed interface ConsentDecision
        permits ConsentDecision.Allow,
                ConsentDecision.Deny,
                ConsentDecision.Prompt {

    enum Outcome { ALLOW, DENY, PROMPT }

    Outcome outcome();
    /** Risk of the underlying call (or {@link Risk#HIGH} for
     *  deny). */
    Risk risk();
    /** Categories emitted by the categorizer (may be empty
     *  for a low-risk auto-allow). */
    List<String> categories();
    /** Human-readable reason, set for audit + UI. */
    String reason();

    /** Convenience: present iff outcome is {@link Outcome#DENY}. */
    default boolean isDeny() { return outcome() == Outcome.DENY; }
    /** Convenience: present iff outcome is {@link Outcome#ALLOW}. */
    default boolean isAllow() { return outcome() == Outcome.ALLOW; }
    /** Convenience: present iff outcome is {@link Outcome#PROMPT}. */
    default boolean isPrompt() { return outcome() == Outcome.PROMPT; }

    // ------------------------------------------------------------------
    //  Allow
    // ------------------------------------------------------------------

    /** Auto-allow / allow-by-grant. The interface methods are
     *  satisfied by the record's auto-generated accessors
     *  {@code risk()}, {@code categories()}, {@code reason()}. */
    record Allow(Risk risk,
                 List<String> categories,
                 String reason,
                 Grant drivingGrant,
                 CategoryResult categoryResult)
            implements ConsentDecision {

        public Allow {
            Objects.requireNonNull(risk, "risk");
            categories = categories == null ? List.of() : List.copyOf(categories);
            Objects.requireNonNull(reason, "reason");
        }

        @Override public Outcome outcome() { return Outcome.ALLOW; }
        public Optional<Grant> drivingGrantOpt() {
            return Optional.ofNullable(drivingGrant);
        }
        public Optional<CategoryResult> categoryResultOpt() {
            return Optional.ofNullable(categoryResult);
        }

        public static Allow lowRisk(CategoryResult r) {
            return new Allow(Risk.LOW, List.of(),
                    "low risk: " + r.explain(), null, r);
        }
    }

    // ------------------------------------------------------------------
    //  Deny
    // ------------------------------------------------------------------

    /** Deny. Always has a human-readable {@code reason}.
     *  The interface methods are satisfied by the record's
     *  auto-generated accessors. */
    record Deny(String reason,
                Grant drivingGrant,
                CategoryResult categoryResult)
            implements ConsentDecision {

        public Deny {
            Objects.requireNonNull(reason, "reason");
        }

        @Override public Outcome outcome() { return Outcome.DENY; }
        @Override public Risk risk() { return Risk.HIGH; }
        @Override public List<String> categories() {
            return categoryResult == null ? List.of() : categoryResult.categories();
        }
        public Optional<Grant> drivingGrantOpt() {
            return Optional.ofNullable(drivingGrant);
        }
        public Optional<CategoryResult> categoryResultOpt() {
            return Optional.ofNullable(categoryResult);
        }
    }

    // ------------------------------------------------------------------
    //  Prompt
    // ------------------------------------------------------------------

    /** Needs a user decision via {@code ConsentPrompt}. The
     *  categories + matched rules come from the categorizer;
     *  risk is the max of the matched rules (or
     *  {@link Risk#MEDIUM} when no rule fired — the "unknown
     *  tool" fallback). */
    record Prompt(Risk risk,
                  List<String> categories,
                  String reason,
                  CategoryResult categoryResult)
            implements ConsentDecision {

        public Prompt {
            Objects.requireNonNull(risk, "risk");
            categories = categories == null ? List.of() : List.copyOf(categories);
            Objects.requireNonNull(reason, "reason");
        }

        @Override public Outcome outcome() { return Outcome.PROMPT; }
        public Optional<Grant> drivingGrantOpt() { return Optional.empty(); }
        public Optional<CategoryResult> categoryResultOpt() {
            return Optional.ofNullable(categoryResult);
        }
    }
}
