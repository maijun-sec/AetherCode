/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.deepagents.middleware;

/**
 * Input schema for the {@code start_async_task} tool.
 *
 * <p>Java-native port of the Python
 * {@code StartAsyncTaskSchema} Pydantic model.</p>
 */
public record StartAsyncTaskSchema(String description, String subagentType) {
    public StartAsyncTaskSchema {
        if (description == null) description = "";
        if (subagentType == null) subagentType = "";
    }
}
