package org.aethercode.permission;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * a small static set of "safe" tool names that the
 * permission checker can auto-approve without prompting.
 * Modelled on Claude Code's safe-list — read-only tools and
 * local-only operations that have no destructive side effects.
 *
 * <p>Unlike {@link QuickAllowList}, this list is determined by
 * the platform (i.e. AetherCode's hard-coded defaults) and not
 * by the user. The user can extend it at runtime via
 * {@link #addToRuntimeSafe(String)} if they trust more tools.
 *
 * <p>Default safe tools: read-only file operations (Read,
 * Glob, Grep) and bash commands whose first word is a known
 * safe command (ls, cat, head, tail, find, grep, pwd, echo).
 */
public final class ToolSafeList {

    private static final Set<String> DEFAULT_SAFE_TOOLS = Set.of(
            "Read", "Glob", "Grep", "ListFiles", "ListDir", "Stat",
            // file_write / file_edit / todo_write / file_create are
            // auto-approved by PermissionDialog.ask (no user prompt)
            // because every one of them is sandboxed by the engine:
            //   - file_write / file_edit refuse paths outside the
            //     configured cwd (FileWriteTool.isPathAllowed)
            //   - todo_write mutates only the in-session todo list
            //     (TodoWriteTool, no filesystem side effect)
            //   - file_create is the same as file_write
            // previously, the engine's StreamingToolExecutor skipped
            // the policy entirely and the model could call these
            // tools freely — but in the new policy-first path the
            // default permission mode (DEFAULT) routes through
            // PermissionDialog, and a headless daemon that asks for
            // stdin but gets none deadlocks the engine. Adding these
            // tools here short-circuits that case. The user can still
            // re-prompt per-call by switching to a stricter mode
            // (PLAN / strict ASK) or by running
            //   /mode BYPASS_PERMISSIONS   (turns the whole policy off).
            "file_write", "file_edit", "file_create", "todo_write"
    );

    private static final Set<String> DEFAULT_SAFE_BASH = Set.of(
            "ls", "cat", "head", "tail", "find", "grep", "pwd",
            "echo", "wc", "diff", "file", "which", "type"
    );

    private static final Set<String> runtimeSafe = new LinkedHashSet<>();

    private ToolSafeList() {}

    public static boolean isToolSafe(String toolName) {
        if (toolName == null) return false;
        if (DEFAULT_SAFE_TOOLS.contains(toolName)) return true;
        if (runtimeSafe.contains(toolName)) return true;
        return false;
    }

    public static boolean isBashCommandSafe(String command) {
        if (command == null || command.isBlank()) return false;
        // First whitespace-delimited token must be a known safe command.
        String[] parts = command.trim().split("\\s+", 2);
        String first = parts[0];
        // strip path prefix (e.g. /bin/ls)
        int slash = first.lastIndexOf('/');
        if (slash >= 0) first = first.substring(slash + 1);
        return DEFAULT_SAFE_BASH.contains(first);
    }

    public static synchronized boolean addToRuntimeSafe(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        return runtimeSafe.add(toolName);
    }

    public static synchronized boolean removeFromRuntimeSafe(String toolName) {
        if (toolName == null) return false;
        return runtimeSafe.remove(toolName);
    }

    public static synchronized Set<String> runtimeSafeSnapshot() {
        return Set.copyOf(runtimeSafe);
    }

    public static int defaultSafeCount() { return DEFAULT_SAFE_TOOLS.size(); }
    public static int defaultSafeBashCount() { return DEFAULT_SAFE_BASH.size(); }
}
