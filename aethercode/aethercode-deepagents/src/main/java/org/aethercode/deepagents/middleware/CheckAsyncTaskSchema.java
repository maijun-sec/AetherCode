package org.aethercode.deepagents.middleware;

/** Input schema for {@code check_async_task}. */
public record CheckAsyncTaskSchema(String taskId) {
    public CheckAsyncTaskSchema { if (taskId == null) taskId = ""; }
}
