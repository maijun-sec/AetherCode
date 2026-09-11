package org.aethercode.deepagents.middleware;

import java.util.Set;

/**
 * Constants and shared prompts for the async subagent middleware.
 *
 * <p>Java-native port of the prompt templates and excluded-state-key
 * set at the top of
 * {@code deepagents.middleware.async_subagents}. The strings mirror
 * the Python port one-for-one so the model receives the same
 * guidance the Python port emits.</p>
 */
public final class AsyncSubAgentPrompts {
    private AsyncSubAgentPrompts() {}

    /** State key that the async subagent middleware writes to. */
    public static final String ASYNC_TASKS_KEY = "async_tasks";

    public static final String ASYNC_TASK_TOOL_DESCRIPTION = """
            Start an async subagent on a remote server. The subagent runs in the background and returns a task ID immediately.

            Available async agent types:
            {available_agents}

            ## Usage notes:
            1. This tool launches a background task and returns immediately with a task ID. Report the task ID to the user and stop — do NOT immediately check status.
            2. Use `check_async_task` only when the user asks for a status update or result.
            3. Use `update_async_task` to send new instructions to a running task.
            4. Multiple async subagents can run concurrently — launch several and let them run in the background.
            5. The subagent runs on a remote server, so it has its own tools and capabilities.""";

    public static final String CHECK_TOOL_DESCRIPTION =
            "Check the status of an async subagent task. Returns the current status and, if complete, the result. "
                    + "Statuses shown earlier in the conversation are always stale, so call this to get the current status "
                    + "rather than reporting a status from a previous tool result.";

    public static final String UPDATE_TOOL_DESCRIPTION =
            "Send updated instructions to an async subagent. Interrupts the current run and starts "
                    + "a new one on the same thread, so the subagent sees the full conversation history plus "
                    + "your new message. The task_id remains the same.";

    public static final String CANCEL_TOOL_DESCRIPTION =
            "Cancel a running async subagent task. Use this to stop a task that is no longer needed.";

    public static final String LIST_TOOL_DESCRIPTION =
            "List tracked async subagent tasks with their current live statuses. "
                    + "By default shows all tasks. Use `status_filter` to narrow by status "
                    + "(e.g. 'running', 'success', 'error', 'cancelled'). "
                    + "Use `check_async_task` to get the full result of a specific completed task. "
                    + "Statuses shown earlier in the conversation are always stale, so call this to read current "
                    + "statuses rather than reporting one from a previous tool result.";

    /** Status values used by the JSON result of a check. */
    public static final Set<String> CHECK_RESULT_KEYS = Set.of("status", "thread_id", "result", "error");
}
