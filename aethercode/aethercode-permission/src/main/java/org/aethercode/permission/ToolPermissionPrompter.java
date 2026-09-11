package org.aethercode.permission;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hook the TUI / CLI implements to surface a permission ask to the user. The prompter
 * resolves with either an {@link PermissionResult.Allow} (possibly with rewritten input)
 * or a {@link PermissionResult.Deny}. If the prompter is {@code null}, the policy auto-allows
 * read-only tools and surfaces a deny to the orchestrator for everything else (which the
 * orchestrator surfaces to the model as a tool_result error).
 */
@FunctionalInterface
public interface ToolPermissionPrompter {
    CompletableFuture<PermissionResult> ask(Tool tool, Map<String, Object> input, String question);
}
