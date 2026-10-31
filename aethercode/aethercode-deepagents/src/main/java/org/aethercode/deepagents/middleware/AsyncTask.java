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

package org.aethercode.deepagents.middleware;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A tracked async subagent task persisted in agent state.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.async_subagents.AsyncTask}. The
 * record carries the identifiers, status, and ISO-8601 timestamps
 * the middleware needs to track a background task across model
 * invocations and context compactions.</p>
 */
public record AsyncTask(
        String taskId,
        String agentName,
        String threadId,
        String runId,
        String status,
        String createdAt,
        String lastCheckedAt,
        String lastUpdatedAt) {

    public AsyncTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(agentName, "agentName");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(runId, "runId");
        if (status == null) status = "running";
    }

    public static final Set<String> TERMINAL_STATUSES = Set.of(
            "cancelled", "success", "error", "timeout", "interrupted");

    public boolean isTerminal() { return TERMINAL_STATUSES.contains(status); }

    /** Replace the status and the last-checked timestamp; leave the
     *  other fields untouched. Mirrors the Python port's
     *  spread-and-override pattern. */
    public AsyncTask withChecked(String newStatus, String now) {
        String updatedAt = !Objects.equals(newStatus, status) ? now : lastUpdatedAt;
        return new AsyncTask(taskId, agentName, threadId, runId, newStatus,
                createdAt, now, updatedAt == null ? now : updatedAt);
    }

    /** Status filter used by the list tool. Mirrors the Python
     *  {@code Literal} type. */
    public enum StatusFilter {
        RUNNING, SUCCESS, ERROR, CANCELLED, ALL;

        public static StatusFilter parse(String s) {
            if (s == null || s.isBlank() || s.equalsIgnoreCase("all")) return ALL;
            return StatusFilter.valueOf(s.trim().toUpperCase());
        }
    }
}
