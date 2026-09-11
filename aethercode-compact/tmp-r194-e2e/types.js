/**
 * Public types for the AetherCode context compression pipeline.
 *
 * Mirrors `design.md §2.1` and `spec.md §2`. All types are
 * implementation-agnostic: they describe shape only, not storage.
 */
/** Read-class tool names that Layer 1 inspects for body-clearing. */
export const READ_CLASS_TOOL_NAMES = new Set([
    "read_file",
    "bash",
    "grep_files",
    "glob_files",
    "web_search",
    "web_fetch",
    "edit_file",
    "write_file",
]);
/** Placeholder text used when a read-class tool result is cleared. */
export const CLEARED_PLACEHOLDER = "[Old tool result content cleared]";
