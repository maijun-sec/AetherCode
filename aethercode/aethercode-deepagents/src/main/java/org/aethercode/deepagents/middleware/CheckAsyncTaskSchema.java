/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.deepagents.middleware;

/** Input schema for {@code check_async_task}. */
public record CheckAsyncTaskSchema(String taskId) {
    public CheckAsyncTaskSchema { if (taskId == null) taskId = ""; }
}
