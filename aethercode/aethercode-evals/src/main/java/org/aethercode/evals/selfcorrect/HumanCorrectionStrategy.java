package org.aethercode.evals.selfcorrect;

import org.aethercode.evals.verifier.Verifier.VerificationResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Human-gated self-correction. When a verification fails, ask a human
 * to write a corrected action.
 *
 * <p>This is the "if you can't trust the LLM, ask the human" branch
 * of the self-correction tree. Useful for:</p>
 * <ul>
 *   <li>High-stakes actions the LLM has never seen before</li>
 *   <li>Recovery from LLM hallucination (when the LLM keeps producing
 *       the same broken action, escalate to the human)</li>
 *   <li>Audit-mode loops where every correction needs to be reviewed
 *       by a human before it's executed</li>
 * </ul>
 *
 * <p>Three modes:</p>
 * <ul>
 *   <li><b>Wired callback</b> — production path; pass a {@link AskHuman}
 *       that returns a {@link CompletableFuture} for the corrected
 *       action. Tests use this with a stub future.</li>
 *   <li><b>Auto-give-up</b> — if no callback is configured, every
 *       call returns {@code null} immediately, which the loop
 *       interprets as "give up". Same fail-loud pattern as
 *       {@link org.aethercode.evals.verifier.HumanVerifier}.</li>
 *   <li><b>Timeout</b> — wait up to {@code timeoutS} for the human;
 *       if the wait times out, return {@code null} so the loop
 *       gracefully terminates.</li>
 * </ul>
 */
public class HumanCorrectionStrategy<T> implements CorrectionStrategy<T> {

    /** Hook a UI uses to ask the human for a corrected action. */
    @FunctionalInterface
    public interface AskHuman<T> {
        CompletableFuture<T> ask(T previous, VerificationResult failure, int attempt);
    }

    private final String name;
    private final AskHuman<T> askHuman;
    private final int timeoutS;
    private final CorrectionStrategy<T> fallback;

    public HumanCorrectionStrategy(String name, AskHuman<T> askHuman, int timeoutS) {
        this(name, askHuman, timeoutS, null);
    }

    public HumanCorrectionStrategy(String name, AskHuman<T> askHuman, int timeoutS,
                                    CorrectionStrategy<T> fallback) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (timeoutS < 1) {
            throw new IllegalArgumentException("timeoutS must be >= 1");
        }
        this.name = name;
        this.askHuman = askHuman;
        this.timeoutS = timeoutS;
        this.fallback = fallback;
    }

    @Override
    public T next(T previous, VerificationResult failure, int attempt) {
        if (askHuman == null) {
            // No callback wired → consult fallback, otherwise give up.
            return fallback != null ? fallback.next(previous, failure, attempt) : null;
        }
        CompletableFuture<T> future;
        try {
            future = askHuman.ask(previous, failure, attempt);
        } catch (RuntimeException ex) {
            // Treat ask-callback crash as "give up" rather than crashing the loop.
            return fallback != null ? fallback.next(previous, failure, attempt) : null;
        }
        if (future == null) {
            // Ask callback returned null (the human closed the prompt or
            // chose "cancel"). Fall back if configured, otherwise give up.
            return fallback != null ? fallback.next(previous, failure, attempt) : null;
        }
        try {
            return future.get(timeoutS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            return fallback != null ? fallback.next(previous, failure, attempt) : null;
        } catch (java.util.concurrent.ExecutionException ex) {
            return fallback != null ? fallback.next(previous, failure, attempt) : null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fallback != null ? fallback.next(previous, failure, attempt) : null;
        }
    }

    @Override
    public String description() {
        return "Human correction" + (askHuman == null ? " (auto-give-up)" : "")
                + (fallback == null ? "" : " with fallback");
    }

    public String name() { return name; }
    public boolean isWired() { return askHuman != null; }
    public int timeoutS() { return timeoutS; }
}
