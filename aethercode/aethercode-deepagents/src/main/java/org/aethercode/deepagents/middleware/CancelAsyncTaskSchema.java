/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.deepagents.middleware;

/** Input schema for {@code cancel_async_task}. */
public record CancelAsyncTaskSchema(String taskId) {
    public CancelAsyncTaskSchema { if (taskId == null) taskId = ""; }
}
