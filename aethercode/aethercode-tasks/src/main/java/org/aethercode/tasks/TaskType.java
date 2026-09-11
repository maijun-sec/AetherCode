package org.aethercode.tasks;

/**
 * Type of a {@link Task}. Modelled after Claude Code's {@code TaskType}
 * (local_bash / local_agent / remote_agent / teammate / workflow / monitor /
 * dream). We collapse to a smaller set for the Java runtime: every long-lived
 * unit of work is either a {@code USER} task (a top-level query from the
 * human), an {@code AGENT} task (a subagent spawned by AgentTool), or a
 * {@code TOOL_BATCH} (one tool-call batch inside a turn).
 */
public enum TaskType {
    /** A top-level query from the user. Has no parent. */
    USER,
    /** A subagent spawned via AgentTool. Has a parent task. */
    AGENT,
    /** A batch of tool calls inside a turn. Used for grouping / progress. */
    TOOL_BATCH,
    /** A workflow — a multi-step automated procedure. */
    WORKFLOW;

    /** ID prefix used in {@link Task#id()}. */
    public String idPrefix() {
        return switch (this) {
            case USER       -> "u";
            case AGENT      -> "a";
            case TOOL_BATCH -> "b";
            case WORKFLOW   -> "w";
        };
    }
}
