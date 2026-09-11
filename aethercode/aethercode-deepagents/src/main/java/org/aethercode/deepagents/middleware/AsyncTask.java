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
