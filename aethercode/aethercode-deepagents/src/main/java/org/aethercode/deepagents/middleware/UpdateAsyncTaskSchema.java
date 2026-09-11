package org.aethercode.deepagents.middleware;

/** Input schema for {@code update_async_task}. */
public record UpdateAsyncTaskSchema(String taskId, String message) {
    public UpdateAsyncTaskSchema {
        if (taskId == null) taskId = "";
        if (message == null) message = "";
    }
}
