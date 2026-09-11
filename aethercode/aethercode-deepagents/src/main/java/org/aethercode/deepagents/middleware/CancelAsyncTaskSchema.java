package org.aethercode.deepagents.middleware;

/** Input schema for {@code cancel_async_task}. */
public record CancelAsyncTaskSchema(String taskId) {
    public CancelAsyncTaskSchema { if (taskId == null) taskId = ""; }
}
