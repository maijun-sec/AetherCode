package org.aethercode.sdk;

import java.util.List;

/**
 * events emitted by {@link PlanExecutor}. A sealed
 * interface — callers exhaustively switch on the type. All events
 * carry the step index (or -1 for whole-plan events).
 */
public sealed interface PlanEvent {

    /** A step has started. The executor is now driving the
     *  step's query. */
    record StepStarted(int stepIndex, String title) implements PlanEvent {}

    /** A step completed successfully. {@code summary} is the
     *  agent's final text. */
    record StepCompleted(int stepIndex, String title, String summary, long elapsedMs)
            implements PlanEvent {}

    /** A step failed. {@code error} is the exception message. */
    record StepFailed(int stepIndex, String title, String error) implements PlanEvent {}

    /** A step was skipped (because the plan was aborted before
     *  it was started). */
    record StepSkipped(int stepIndex, String title, String reason) implements PlanEvent {}

    /** The plan was aborted mid-execution. {@code atStep} is the
     *  index where the abort was acknowledged. */
    record PlanAborted(int atStep, String reason) implements PlanEvent {}

    /** The plan completed (all steps reached a terminal state,
     *  either COMPLETED or FAILED). */
    record PlanCompleted(List<PlanExecutor.StepResult> results) implements PlanEvent {}
}
