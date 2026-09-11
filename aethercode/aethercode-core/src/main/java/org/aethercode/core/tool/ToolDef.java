package org.aethercode.core.tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * The data definition of a tool. The {@link #call} function takes the validated input map and
 * the {@link Tool.CallContext}; everything else (schema, name, safety properties) is a plain
 * field. This is the shape {@link Tools#build(ToolDef)} consumes.
 */
public final class ToolDef {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final BiFunction<Map<String, Object>, Tool.CallContext, CompletableFuture<Tool.ToolResult>> call;
    private final java.util.function.BiFunction<Map<String, Object>, Tool.CallContext, CompletableFuture<org.aethercode.core.permission.PermissionResult>> checkPermissions;

    public ToolDef(
            String name,
            String description,
            Map<String, Object> inputSchema,
            BiFunction<Map<String, Object>, Tool.CallContext, CompletableFuture<Tool.ToolResult>> call
    ) {
        this(name, description, inputSchema, call, null);
    }

    public ToolDef(
            String name,
            String description,
            Map<String, Object> inputSchema,
            BiFunction<Map<String, Object>, Tool.CallContext, CompletableFuture<Tool.ToolResult>> call,
            java.util.function.BiFunction<Map<String, Object>, Tool.CallContext, CompletableFuture<org.aethercode.core.permission.PermissionResult>> checkPermissions
    ) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.call = call;
        this.checkPermissions = checkPermissions;
    }

    public String name() { return name; }
    public String description() { return description; }
    public Map<String, Object> inputSchema() { return inputSchema; }
    public BiFunction<Map<String, Object>, Tool.CallContext, CompletableFuture<Tool.ToolResult>> call() { return call; }
    public java.util.function.BiFunction<Map<String, Object>, Tool.CallContext, CompletableFuture<org.aethercode.core.permission.PermissionResult>> checkPermissions() { return checkPermissions; }
}
