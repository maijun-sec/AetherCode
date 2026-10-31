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

package org.aethercode.tasks;

import java.util.List;

/**
 * a one-line summary of the session's task state. Combines
 * {@link TaskStats} and an optional plan-summary string into a
 * single human-readable line. Designed for the status bar
 * footer.
 *
 * <p>The plan summary is a plain String (not a PlanStats record)
 * to avoid a dependency on the SDK module. The TUI passes
 * {@code plan.summary()} as the second argument.
 *
 * <p>Format: {@code "12 tasks (3 running) ↑ | plan: 5 steps in 12s"}.
 * If the plan summary is null, the plan segment is omitted.
 */
public final class SessionSummary {

    private SessionSummary() {}

    /** build a one-line summary. */
    public static String oneLine(TaskStats stats, String planSummary) {
        if (stats == null) stats = new TaskStats(0, 0, 0, 0, 0, 0, 0, 0L);
        StringBuilder sb = new StringBuilder();
        sb.append(stats.total()).append(" tasks");
        if (stats.running() > 0 || stats.pending() > 0) {
            sb.append(" (").append(stats.running()).append(" running");
            if (stats.pending() > 0) {
                sb.append(", ").append(stats.pending()).append(" queued");
            }
            sb.append(")");
        }
        if (planSummary != null && !planSummary.isEmpty()) {
            sb.append(" | plan: ").append(planSummary);
        }
        return sb.toString();
    }

    /** structured breakdown — useful for the {@code /stats}
     *  slash command which wants labeled lines. */
    public static List<String> breakdown(TaskStats stats,
                                         String planSummary,
                                         StatsHistory history) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (stats == null) return out;
        out.add("tasks: " + stats.summary());
        if (planSummary != null && !planSummary.isEmpty()) {
            out.add("plan:  " + planSummary);
        }
        if (history != null && history.size() >= 2) {
            int trend = history.trend();
            String arrow = trend > 0 ? "↑" : trend < 0 ? "↓" : "·";
            out.add("trend: " + arrow + " (" + history.size() + " samples)");
        }
        return out;
    }
}
