package org.aethercode.runtime.tool;

import java.util.List;
import java.util.Map;

/**
 * Sealed union of every tool shape the agent runtime understands.
 *
 * <p>Java-native equivalent of langchain's <code>BaseTool</code>:
 * the agent only ever depends on this interface, never on a concrete
 * tool class. Two variants cover the common cases:</p>
 * <ul>
 *   <li>{@link StructuredTool} — schema-driven, JSON in / String out</li>
 *   <li>{@link DynamicTool}   — free-form callable; for ad-hoc tools</li>
 * </ul>
 */
public sealed interface Tool permits StructuredTool, DynamicTool {

    /** Tool name. Must be unique within an agent. */
    String name();

    /** Human-readable description; surfaced to the LLM. */
    String description();

    /**
     * Convert this tool into a JSON-Schema map suitable for the
     * LLM's <code>tools</code> parameter.
     */
    Map<String, Object> toJsonSchema();
}
