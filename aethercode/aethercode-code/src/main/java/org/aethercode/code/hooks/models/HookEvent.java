package org.aethercode.code.hooks.models;

/**
 * Hook lifecycle event types.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.HookEvent} enum.</p>
 */
public enum HookEvent {
    SESSION_START("SessionStart"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    SESSION_END("SessionEnd"),
    PERMISSION_REQUEST("PermissionRequest"),
    NOTIFICATION("Notification"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure"),
    PRE_COMPACT("PreCompact"),
    STOP("Stop"),
    SUBAGENT_START("SubagentStart"),
    SUBAGENT_STOP("SubagentStop");

    private final String wireName;

    HookEvent(String wireName) { this.wireName = wireName; }

    /** PascalCase wire name. */
    public String wireName() { return wireName; }

    /** Look up by wire name; case-sensitive. */
    public static HookEvent fromWireName(String wireName) {
        for (HookEvent e : values()) {
            if (e.wireName.equals(wireName)) return e;
        }
        throw new IllegalArgumentException("Unknown hook event wire name: " + wireName);
    }
}
