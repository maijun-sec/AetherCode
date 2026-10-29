/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.deepagents.middleware;

/** Input schema for {@code update_async_task}. */
public record UpdateAsyncTaskSchema(String taskId, String message) {
    public UpdateAsyncTaskSchema {
        if (taskId == null) taskId = "";
        if (message == null) message = "";
    }
}
