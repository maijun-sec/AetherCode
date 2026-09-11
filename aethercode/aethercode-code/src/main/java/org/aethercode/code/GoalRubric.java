package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Goal-rubric surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.goal_rubric}
 * module. The Java port exposes the small set of helpers used by the
 * TUI's goal status display and the agent's update_goal tool.</p>
 */
public final class GoalRubric {
    private GoalRubric() {}

    /** A rubric verdict. */
    public enum Verdict {
        COMPLETE, INCOMPLETE, BLOCKED, NEEDS_REVISION;

        public String wireName() { return name().toLowerCase(); }
    }

    /** A rubric grading result. */
    public record Result(
            String objective,
            String rubric,
            Verdict verdict,
            String reason,
            long gradedAt) {
    }

    /** Decide whether the agent's reply carries a goal/rubric proposal. */
    public static boolean looksLikeProposal(String text) {
        if (text == null) return false;
        return text.toLowerCase().contains("objective:") || text.toLowerCase().contains("rubric:");
    }

    /** Parse a goal proposal from a text block. */
    public static Result parseProposal(String text) {
        if (text == null) return null;
        String objective = "";
        String rubric = "";
        for (String line : text.split("\n")) {
            String lower = line.toLowerCase();
            if (lower.startsWith("objective:")) {
                objective = line.substring("objective:".length()).strip();
            } else if (lower.startsWith("rubric:")) {
                rubric = line.substring("rubric:".length()).strip();
            }
        }
        return new Result(objective, rubric, Verdict.NEEDS_REVISION, null, System.currentTimeMillis());
    }
}
