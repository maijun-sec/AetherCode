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
