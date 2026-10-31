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
