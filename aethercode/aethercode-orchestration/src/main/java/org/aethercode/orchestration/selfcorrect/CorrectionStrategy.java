package org.aethercode.orchestration.selfcorrect;

import org.aethercode.orchestration.verifier.Verifier.VerificationResult;

/**
 * Strategy for producing a corrected action after a verification failure.
 *
 * <p>Self-correction (paper 2508.17281 §5.2 "反思重规划"; Reflexion /
 * Self-Refine families) is the bridge between the V verifier set
 * (R-radar-6) and the actual action: when V rejects an action, the
 * agent must decide what to do next — retry, ask the LLM to revise,
 * ask a human, fall back, etc.</p>
 *
 * <p>The strategy is parameterised on the action type {@code T} so
 * the same loop can self-correct structured actions (tool calls,
 * JSON responses) and free-form strings (LLM continuations) without
 * losing type safety.</p>
 */
public interface CorrectionStrategy<T> {

    /**
     * Produce the next action to try, given the previous action and
     * the verifier's failure result.
     *
     * <p>Returns {@code null} to signal "give up" (no more options).
     * The loop will then terminate with a {@code LoopResult} whose
     * {@code terminal} field is set to {@code TerminalReason.GAVE_UP}.</p>
     */
    T next(T previous, VerificationResult failure, int attempt);

    /** Optional one-line description of what this strategy does. */
    default String description() { return ""; }

    /** Why the strategy chose to give up. */
    enum GiveUpReason {
        /** Strategy returned null. */
        STRATEGY_RETURNED_NULL,
        /** Out of budget. */
        BUDGET_EXHAUSTED,
        /** Verifier passed — no correction needed. */
        PASSED,
        /** External interrupt (e.g. timeout). */
        INTERRUPTED
    }
}
