package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.PrivateStateAttr;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * State schema for {@link RubricMiddleware}.
 *
 * <p>Java-native port of {@code deepagents.middleware.rubric.RubricState}.
 * Only {@code rubric} is part of the public I/O schema &mdash; callers
 * write a rubric and read the improved agent response back from
 * {@code messages}. Everything else is bookkeeping: status, iteration
 * count, accumulated evaluations, and rubric-attempt tracking are
 * annotated with {@link PrivateStateAttr} so they are omitted from
 * input/output schemas. Tests, evals, and observability consumers can
 * still reach them via the {@code on_evaluation} callback, the
 * {@code rubric_evaluation_*} stream events, or a checkpointed
 * state snapshot.</p>
 *
 * <p>The Java port models the state as a record wrapping an
 * {@link AgentState} plus optional private-attr holders. Each private
 * attr is exposed as a typed {@link Optional} accessor.</p>
 *
 * @param state     underlying agent state (messages, files, extensions)
 * @param rubric    optional caller-supplied rubric string
 * @param status    optional {@link RubricResult}; most recent terminal
 *                  status, or absent after a fresh rubric attempt
 * @param iterations optional count of grader evaluations performed
 *                   for the current rubric
 * @param evaluations optional accumulated grader evaluations across rubrics
 * @param gradingRunId optional tracking id for the active grading run
 * @param activeRubric optional rubric that minted the
 *                     {@code gradingRunId}
 */
public record RubricState(
        AgentState state,
        @PrivateStateAttr String rubric,
        @PrivateStateAttr RubricResult status,
        @PrivateStateAttr Integer iterations,
        @PrivateStateAttr List<RubricEvaluation> evaluations,
        @PrivateStateAttr String gradingRunId,
        @PrivateStateAttr String activeRubric) {

    public RubricState {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
    }

    /** Build a {@link RubricState} from an existing {@link AgentState}. */
    public RubricState(AgentState state) {
        this(state, null, null, null, null, null, null);
    }

    /** Empty initial state with no rubric. */
    public static RubricState empty() {
        return new RubricState(AgentState.empty());
    }

    /** Update the underlying state. */
    public RubricState withState(AgentState next) {
        return new RubricState(next, rubric, status, iterations,
                evaluations, gradingRunId, activeRubric);
    }

    /** Update the messages list. */
    public RubricState withMessages(List<Message> messages) {
        return withState(state.withMessages(messages));
    }

    /** Set the rubric. */
    public RubricState withRubric(String newRubric) {
        return new RubricState(state, newRubric, status, iterations,
                evaluations, gradingRunId,
                newRubric == null ? null : newRubric);
    }

    /** Update the private status. */
    public RubricState withStatus(RubricResult newStatus) {
        return new RubricState(state, rubric, newStatus, iterations,
                evaluations, gradingRunId, activeRubric);
    }

    /** Update the private iteration count. */
    public RubricState withIterations(Integer newIterations) {
        return new RubricState(state, rubric, status, newIterations,
                evaluations, gradingRunId, activeRubric);
    }

    /** Append a new evaluation. */
    public RubricState withEvaluation(RubricEvaluation eval) {
        java.util.List<RubricEvaluation> next = new java.util.ArrayList<>();
        if (evaluations != null) next.addAll(evaluations);
        next.add(eval);
        return new RubricState(state, rubric, status, iterations,
                next, gradingRunId, activeRubric);
    }

    /** Update the private grading-run id. */
    public RubricState withGradingRunId(String newId) {
        return new RubricState(state, rubric, status, iterations,
                evaluations, newId, activeRubric);
    }

    /** Typed accessors that return {@link Optional}. */
    public Optional<String> rubricOpt() { return Optional.ofNullable(rubric); }
    public Optional<RubricResult> statusOpt() { return Optional.ofNullable(status); }
    public Optional<Integer> iterationsOpt() { return Optional.ofNullable(iterations); }
    public Optional<List<RubricEvaluation>> evaluationsOpt() {
        return Optional.ofNullable(evaluations);
    }
    public Optional<String> gradingRunIdOpt() { return Optional.ofNullable(gradingRunId); }
    public Optional<String> activeRubricOpt() { return Optional.ofNullable(activeRubric); }

    /** Convert the underlying state to a {@code Map<String, Object>}
     *  representation, with the private-attr fields included under
     *  their {@code _} prefix. */
    public Map<String, Object> toMap() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        if (rubric != null) m.put("rubric", rubric);
        if (status != null) m.put("_rubric_status", status.jsonValue());
        if (iterations != null) m.put("_rubric_iterations", iterations);
        if (evaluations != null) m.put("_rubric_evaluations", evaluations);
        if (gradingRunId != null) m.put("_current_grading_run_id", gradingRunId);
        if (activeRubric != null) m.put("_active_rubric", activeRubric);
        return m;
    }
}
