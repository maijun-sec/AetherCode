package org.aethercode.core.middleware;

/**
 * Input schema for the {@code task} tool.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.subagents.TaskToolSchema} Pydantic
 * model. The Java port is a record so the model adapter can render
 * the field metadata into provider-specific JSON schemas.</p>
 */
public record TaskToolSchema(String description, String subagentType) {

    public TaskToolSchema {
        if (description == null) description = "";
        if (subagentType == null) subagentType = "";
    }
}
