package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.HumanMessage;

/**
 * Rubric middleware for self-evaluated agent iteration.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.rubric.RubricMiddleware}. Declares
 * what done looks like via a rubric. Each time the agent would
 * otherwise finish, the middleware invokes a separate grader
 * sub-agent against the transcript. If the grader returns
 * {@code needs_revision}, its feedback is injected as a
 * {@link HumanMessage} and the agent loop resumes.
 * Grading repeats until the grader returns
 * {@code satisfied}/{@code failed} or {@code max_iterations} is
 * reached.</p>
 *
 * <p>The actual grader invocation requires a chat-model adapter
 * (prior round); the Java port exposes the {@link Grader} SPI so tests
 * can inject a stub and the R3 graph runtime can wire a real
 * grader in.</p>
 */
public class RubricMiddleware implements Middleware {
    private static final Logger LOGGER = Logger.getLogger(RubricMiddleware.class.getName());

    /** SPI for invoking the grader sub-agent. The default stub
     *  returns {@code needs_revision} for every evaluation, which
     *  drives the loop up to {@code maxIterations} and exits with
     *  {@code max_iterations_reached}. */
    public interface Grader {
        GraderResponse grade(String rubric, List<Message> transcript, AgentState state);
    }

    /** The default stub grader: every iteration returns
     *  {@code needs_revision} with a placeholder gap. */
    public static final Grader STUB_GRADER = (rubric, transcript, state) ->
            GraderResponse.needsRevision(
                    List.of(CriterionEval.fail("placeholder", "grader not wired")),
                    "Stub grader; wire a real Grader implementation to score this rubric.");

    private final String rubric;
    private final int maxIterations;
    private final Grader grader;
    private final String systemPrompt;

    public RubricMiddleware(String rubric, int maxIterations, Grader grader, String systemPrompt) {
        this.rubric = Objects.requireNonNull(rubric, "rubric");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1, got " + maxIterations);
        }
        this.maxIterations = maxIterations;
        this.grader = grader == null ? STUB_GRADER : grader;
        this.systemPrompt = systemPrompt == null ? RubricPrompts.GRADER_SYSTEM_PROMPT : systemPrompt;
    }

    public RubricMiddleware(String rubric, int maxIterations) {
        this(rubric, maxIterations, null, null);
    }

    public String rubric() { return rubric; }
    public int maxIterations() { return maxIterations; }
    public Grader grader() { return grader; }
    public String systemPrompt() { return systemPrompt; }

    @Override
    public String name() { return "RubricMiddleware"; }

    // -----------------------------------------------------------------
    // afterModel: run the grader and decide whether to continue
    // -----------------------------------------------------------------

    @Override
    public AgentState afterModel(AgentState state, AIMessage aiMessage, Runtime runtime) {
        if (aiMessage == null) return state;
        // The agent has finished a turn. Run the grader against the
        // transcript and either accept the result or inject a
        // synthetic HumanMessage to drive another iteration.
        List<RubricEvaluation> evaluations = evaluationsFor(state);
        if (evaluations.size() >= maxIterations) {
            return state;  // Cap reached; the runtime should surface the
                            // transcript as-is.
        }
        int iteration = evaluations.size() + 1;
        List<Message> transcript = state.messages();
        GraderResponse response;
        try {
            response = grader.grade(rubric, transcript, state);
        } catch (RuntimeException e) {
            LOGGER.warning("Grader error: " + e.getMessage());
            return appendEvaluation(state, new RubricEvaluation(iteration,
                    RubricResult.GRADER_ERROR, null,
                    Map.of("error", String.valueOf(e.getMessage()))));
        }
        if (response == null) {
            return appendEvaluation(state, new RubricEvaluation(iteration,
                    RubricResult.GRADER_ERROR, null, Map.of("error", "null response")));
        }
        RubricResult result = switch (response.result()) {
            case SATISFIED -> RubricResult.SATISFIED;
            case NEEDS_REVISION -> RubricResult.NEEDS_REVISION;
            case FAILED -> RubricResult.FAILED;
        };
        AgentState next = appendEvaluation(state, new RubricEvaluation(
                iteration, result, response, Map.of()));
        if (result == RubricResult.NEEDS_REVISION && iteration < maxIterations) {
            return injectRevisionFeedback(next, response);
        }
        return next;
    }

    @SuppressWarnings("unchecked")
    private static List<RubricEvaluation> evaluationsFor(AgentState state) {
        Object raw = state.extensions().get(RubricPrompts.RUBRIC_EVALUATIONS_KEY);
        if (raw instanceof List<?> list) {
            List<RubricEvaluation> out = new ArrayList<>();
            for (Object o : list) if (o instanceof RubricEvaluation re) out.add(re);
            return out;
        }
        return List.of();
    }

    private static AgentState appendEvaluation(AgentState state, RubricEvaluation eval) {
        List<RubricEvaluation> existing = evaluationsFor(state);
        List<RubricEvaluation> next = new ArrayList<>(existing.size() + 1);
        next.addAll(existing);
        next.add(eval);
        return state.withExtension(RubricPrompts.RUBRIC_EVALUATIONS_KEY, next);
    }

    private static AgentState injectRevisionFeedback(AgentState state, GraderResponse response) {
        String feedback = buildFeedbackText(response);
        HumanMessage human = new HumanMessage(
                "rubric-revision-" + System.nanoTime(),
                List.of(ContentBlock.text(feedback)));
        List<Message> newMessages = new ArrayList<>(state.messages().size() + 1);
        newMessages.addAll(state.messages());
        newMessages.add(human);
        return state.withMessages(newMessages);
    }

    private static String buildFeedbackText(GraderResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("The grader reports the following gaps in your work:\n");
        for (CriterionEval eval : response.evaluations()) {
            if (eval instanceof CriterionEval.Fail f) {
                sb.append("- ").append(f.name()).append(": ").append(f.gap()).append("\n");
            }
        }
        if (response.comments() != null && !response.comments().isBlank()) {
            sb.append("\nAdditional comments: ").append(response.comments()).append("\n");
        }
        sb.append("\nPlease address these gaps and try again.");
        return sb.toString();
    }
}
