/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
