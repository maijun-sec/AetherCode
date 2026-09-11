package org.aethercode.core.permission;

/**
 * The high-level permission posture the user has selected for the current session.
 *
 * <p>Inspired by Claude Code's {@code --permission-mode} flag.
 */
public enum PermissionMode {
    /** No tool runs without explicit user approval — ask for every invocation. */
    DEFAULT,
    /**
     * explicit "ask before every tool call" mode. Semantically
     * identical to {@link #DEFAULT} (both route every non-read-only tool
     * call through the user's permission prompter), but the name is
     * more discoverable in the TUI's status bar and command palette.
     *
     * <p>This is the mode the user gets when they want to be
     * interrupted mid-loop, before every tool call, with no implicit
     * allow. Recommended when exploring an unfamiliar codebase or
     * when running a model in an environment where the user is the
     * safety net.
     *
     * <p>Set via:
     * <ul>
     *   <li>{@code /mode ask-before-tool} in the TUI</li>
     *   <li>{@code AETHERCODE_DEFAULT_PERMISSION_MODE=ASK_BEFORE_TOOL} env var</li>
     *   <li>{@code .aethercode/config.json :: defaultPermissionMode}</li>
     * </ul>
     */
    ASK_BEFORE_TOOL,
    /** Auto-allow file edits; ask for bash / network. */
    ACCEPT_EDITS,
    /** Skip all prompts — the user is on the hook for everything. */
    BYPASS_PERMISSIONS,
    /** Built-in safe tools run, but mutations prompt. */
    PLAN,
    /** Auto-allow only tools classified as read-only. */
    AUTO_READ_ONLY,
    /**
     * auto-allow all tool calls within a single task / sub-task.
     * When the model emits a {@code sub_todo_write} event (the start
     * of a new sub-task) the engine treats it as a "task boundary"
     * and the NEXT permission ask is surfaced to the user again.
     * All calls inside the same sub-task are auto-allowed. Read-only
     * tools are always auto-allowed regardless of the boundary.
     *
     * <p>This is the default mode for the TUI's "single task, no
     * mid-task stops" UX. The user only sees one decision prompt
     * per sub-task instead of one per tool call.
     */
    ACCEPT_TASK
}
