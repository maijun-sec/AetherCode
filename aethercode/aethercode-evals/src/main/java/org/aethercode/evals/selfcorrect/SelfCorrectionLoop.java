package org.aethercode.evals.selfcorrect;

import org.aethercode.evals.selfcorrect.CorrectionStrategy.GiveUpReason;
import org.aethercode.evals.verifier.Verifier;
import org.aethercode.evals.verifier.Verifier.VerificationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-correction loop. Runs {@code T -> V -> [if fail -> Correction -> T]} until
 * the verifier passes, the strategy gives up, or the budget runs out.
 *
 * <p>Mirrors the "Plan-and-Execute" + "Reflexion" pattern from paper
 * 2508.17281 §5.2: do something, check the result, reflect and retry
 * if it failed. The verifier is the "check", the correction strategy
 * is the "reflect", and the loop is the integration.</p>
 *
 * <p>Default policy:</p>
 * <ul>
 *   <li>First attempt: run {@code action} through the verifier. If
 *       it passes, return immediately.</li>
 *   <li>On failure: ask the {@link CorrectionStrategy} for a revised
 *       action and try again.</li>
 *   <li>Stop after {@code maxAttempts} attempts OR when the strategy
 *       returns {@code null}.</li>
 *   <li>The full attempt history is in the {@link LoopResult}, so an
 *       audit log or eval report can see every (action, verdict)
 *       pair the loop considered.</li>
 * </ul>
 */
public class SelfCorrectionLoop<T> {

    private final String name;
    private final Verifier<T> verifier;
    private final CorrectionStrategy<T> strategy;
    private final int maxAttempts;

    public SelfCorrectionLoop(String name, Verifier<T> verifier,
                              CorrectionStrategy<T> strategy, int maxAttempts) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (verifier == null) {
            throw new IllegalArgumentException("verifier must be non-null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must be non-null");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.name = name;
        this.verifier = verifier;
        this.strategy = strategy;
        this.maxAttempts = maxAttempts;
    }

    public LoopResult<T> run(T initial) {
        List<Attempt<T>> attempts = new ArrayList<>();
        T current = initial;
        GiveUpReason terminal = GiveUpReason.PASSED;
        for (int i = 1; i <= maxAttempts; i++) {
            VerificationResult result;
            try {
                result = verifier.verify(current);
            } catch (RuntimeException ex) {
                // Verifier crash: surface as a BLOCK failure but keep the
                // loop alive; a single crash shouldn't poison the whole
                // self-correction budget.
                result = VerificationResult.fail(Verifier.Severity.BLOCK,
                        "verifier crashed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                        Map.of("exception", ex.getClass().getName(),
                                "verifier", verifier.name(),
                                "attempt", i));
            }
            attempts.add(new Attempt<>(i, current, result));
            if (result.passed()) {
                terminal = GiveUpReason.PASSED;
                break;
            }
            if (i == maxAttempts) {
                terminal = GiveUpReason.BUDGET_EXHAUSTED;
                break;
            }
            T next;
            try {
                next = strategy.next(current, result, i);
            } catch (RuntimeException ex) {
                // Strategy crash: same as null (give up gracefully).
                attempts.add(new Attempt<>(i, current,
                        VerificationResult.fail(Verifier.Severity.BLOCK,
                                "strategy crashed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                                Map.of("exception", ex.getClass().getName(),
                                        "attempt", i))));
                terminal = GiveUpReason.STRATEGY_RETURNED_NULL;
                break;
            }
            if (next == null) {
                terminal = GiveUpReason.STRATEGY_RETURNED_NULL;
                break;
            }
            current = next;
        }
        return new LoopResult<>(name, terminal, attempts, maxAttempts, verifier.name());
    }

    public String name() { return name; }
    public Verifier<T> verifier() { return verifier; }
    public CorrectionStrategy<T> strategy() { return strategy; }
    public int maxAttempts() { return maxAttempts; }

    /** One (action, verifier-result) pair. */
    public record Attempt<T>(int attempt, T action, VerificationResult result) {
        public Attempt {
            if (attempt < 1) throw new IllegalArgumentException("attempt must be >= 1");
            if (action == null) throw new IllegalArgumentException("action must be non-null");
            if (result == null) throw new IllegalArgumentException("result must be non-null");
        }
    }

    /** Final loop outcome. */
    public record LoopResult<T>(
            String loopName,
            GiveUpReason terminal,
            List<Attempt<T>> attempts,
            int maxAttempts,
            String verifierName) {

        /** True iff the loop ended with the verifier passing. */
        public boolean passed() {
            return terminal == GiveUpReason.PASSED;
        }

        /** True iff the loop ended without passing. */
        public boolean failed() {
            return !passed();
        }

        /** Last attempt, or empty if the loop made no attempts. */
        public Attempt<T> lastAttempt() {
            return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        }

        /** How many attempts the loop used. */
        public int attemptsUsed() { return attempts.size(); }

        /** All failure reasons in order, joined with {@code " | "}. */
        public String failureTrail() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < attempts.size(); i++) {
                Attempt<T> a = attempts.get(i);
                if (a.result().passed()) continue;
                if (sb.length() > 0) sb.append(" | ");
                sb.append("attempt ").append(a.attempt()).append(": ").append(a.result().reason());
            }
            return sb.toString();
        }

        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loop", loopName);
            m.put("verifier", verifierName);
            m.put("terminal", terminal);
            m.put("passed", passed());
            m.put("attempts_used", attemptsUsed());
            m.put("max_attempts", maxAttempts);
            return m;
        }
    }
}
