package org.aethercode.acp.schema;

import java.util.Optional;

/**
 * Tool kind discriminant (matches the {@code kind} field of
 * {@code ToolCallStart} / {@code ToolCallUpdate}). Mirrors
 * {@code acp.schema.ToolKind} string union.
 */
public final class ToolKind {
    private ToolKind() {}

    public static final String READ = "read";
    public static final String EDIT = "edit";
    public static final String DELETE = "delete";
    public static final String MOVE = "move";
    public static final String SEARCH = "search";
    public static final String EXECUTE = "execute";
    public static final String THINK = "think";
    public static final String FETCH = "fetch";
    public static final String SWITCH_MODE = "switch_mode";
    public static final String OTHER = "other";

    private ToolKind(String ignored) {}
}
