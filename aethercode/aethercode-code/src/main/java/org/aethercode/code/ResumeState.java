package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Schema and middleware for per-checkpoint state restored when resuming.
 *
 * <p>Java-native port of the Python {@code deepagents_code.resume_state}
 * module. The Java port keeps the channel constants and helper accessors
 * (used by the TUI's resume path and by {@code ConfigurableModelMiddleware})
 * while leaving the actual {@code AgentState} wiring to the
 * {@code deepagents-core} port's middleware contracts.</p>
 */
public final class ResumeState {
    private ResumeState() {}

    /** Lifecycle status of a TUI-owned goal. */
    public enum GoalStatus { ACTIVE, PAUSED, BLOCKED, COMPLETE;
        public String wireName() { return name().toLowerCase(); }
        public static GoalStatus coerce(Object value) {
            if (value instanceof GoalStatus gs) return gs;
            if (value instanceof String s) {
                try { return GoalStatus.valueOf(s.toUpperCase()); }
                catch (IllegalArgumentException ignored) { return null; }
            }
            return null;
        }
    }

    /** Whether a pending review creates a goal or amends the current one. */
    public enum GoalProposalKind { CREATE, AMEND;
        public String wireName() { return name().toLowerCase(); }
    }

    /** Every verdict the rubric grader can emit. */
    public static final Set<String> RUBRIC_RESULT_VALUES = Set.of(
            "complete", "incomplete", "blocked", "needs_revision");

    /** Channel name for the total context tokens from the latest model call. */
    public static final String CHANNEL_CONTEXT_TOKENS = "_context_tokens";

    /** Channel name for the model spec effective for the turn. */
    public static final String CHANNEL_MODEL_SPEC = "_model_spec";

    /** Channel name for the model params effective for the turn. */
    public static final String CHANNEL_MODEL_PARAMS = "_model_params";

    /** Channel name for the last-model-request UTC timestamp. */
    public static final String CHANNEL_LAST_MODEL_REQUEST_AT = "_last_model_request_at";

    /** Channel name for the requested model identity at the last model call. */
    public static final String CHANNEL_LAST_CACHE_MODEL_SPEC = "_last_cache_model_spec";

    /** Channel name for the accepted goal objective (TUI-owned). */
    public static final String CHANNEL_GOAL_OBJECTIVE = "_goal_objective";

    /** Channel name for the goal lifecycle status. */
    public static final String CHANNEL_GOAL_STATUS = "_goal_status";

    /** Channel name for the goal rubric. */
    public static final String CHANNEL_GOAL_RUBRIC = "_goal_rubric";

    /** Channel name for the goal status note. */
    public static final String CHANNEL_GOAL_STATUS_NOTE = "_goal_status_note";

    /** Channel name for the agent-provided completion note. */
    public static final String CHANNEL_PENDING_GOAL_COMPLETION_NOTE = "_pending_goal_completion_note";

    /** Channel name for the persistent TUI-owned rubric. */
    public static final String CHANNEL_STICKY_RUBRIC = "_sticky_rubric";

    /** Channel name for the pending goal proposal. */
    public static final String CHANNEL_PENDING_GOAL_OBJECTIVE = "_pending_goal_objective";

    /** Channel name for the pending goal rubric. */
    public static final String CHANNEL_PENDING_GOAL_RUBRIC = "_pending_goal_rubric";

    /** Channel name for the pending goal proposal kind. */
    public static final String CHANNEL_PENDING_GOAL_KIND = "_pending_goal_kind";

    /** Channel name for the pending goal request id. */
    public static final String CHANNEL_PENDING_GOAL_REQUEST_ID = "_pending_goal_request_id";

    /** Read a single channel from a checkpointed state map. */
    public static Object getChannel(Map<String, Object> state, String channel) {
        return state == null ? null : state.get(channel);
    }

    /** Read the goal status, narrowing to the {@link GoalStatus} literal. */
    public static GoalStatus getGoalStatus(Map<String, Object> state) {
        return GoalStatus.coerce(getChannel(state, CHANNEL_GOAL_STATUS));
    }

    /** Read the goal proposal kind, narrowing to the literal. */
    public static GoalProposalKind getGoalProposalKind(Map<String, Object> state) {
        Object raw = getChannel(state, CHANNEL_PENDING_GOAL_KIND);
        if (raw instanceof GoalProposalKind gpk) return gpk;
        if (raw instanceof String s) {
            try { return GoalProposalKind.valueOf(s.toUpperCase()); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    /** Return a defensive copy of {@code state} with the given channel updated. */
    public static Map<String, Object> withChannel(Map<String, Object> state,
                                                  String channel, Object value) {
        Map<String, Object> out = state == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state);
        out.put(channel, value);
        return out;
    }
}
