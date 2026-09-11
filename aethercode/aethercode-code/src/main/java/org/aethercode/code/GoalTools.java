package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Goal-tools surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.goal_tools}
 * module. The Java port exposes the helper that builds a goal-criteria
 * request payload the agent graph consumes when entering the criteria
 * review step.</p>
 */
public final class GoalTools {
    private GoalTools() {}

    /** One proposed goal or amendment. */
    public record GoalProposal(String objective, String rubric) {}

    /** Goal-criteria request payload. */
    public record GoalCriteriaRequest(
            String type,
            String objective,
            String rubric,
            ResumeState.GoalProposalKind kind) {
    }

    /** Build a request payload from a proposal. */
    public static GoalCriteriaRequest buildRequest(GoalProposal proposal,
                                                    ResumeState.GoalProposalKind kind) {
        if (proposal == null) {
            return new GoalCriteriaRequest("goal", "", "", kind);
        }
        return new GoalCriteriaRequest("goal",
                proposal.objective() == null ? "" : proposal.objective(),
                proposal.rubric() == null ? "" : proposal.rubric(),
                kind);
    }

    /** Build a request payload from a text representation. */
    public static GoalCriteriaRequest fromText(String text) {
        if (text == null || text.isBlank()) {
            return new GoalCriteriaRequest("goal", "", "", null);
        }
        int nl = text.indexOf('\n');
        if (nl < 0) {
            return new GoalCriteriaRequest("goal", text.strip(), "", null);
        }
        String objective = text.substring(0, nl).strip();
        String rubric = text.substring(nl + 1).strip();
        return new GoalCriteriaRequest("goal", objective, rubric, null);
    }
}
