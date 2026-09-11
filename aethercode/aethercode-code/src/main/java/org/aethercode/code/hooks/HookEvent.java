package org.aethercode.code.hooks;

/**
 * Lifecycle event the Hooks v2 system can fire.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.HookEvent} {@code StrEnum}.
 * Values are kept lowercase to match the canonical
 * <code>hook_event_name</code> strings used on the wire.</p>
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

    HookEvent(String wireName) {
        this.wireName = wireName;
    }

    /** Canonical PascalCase wire name used by the
     *  <code>hook_event_name</code> JSON discriminator. */
    public String wireName() {
        return wireName;
    }

    /**
     * Look up a hook event from its canonical wire name.
     *
     * @param wireName PascalCase wire name such as {@code "PreToolUse"}.
     * @return Matching {@link HookEvent}.
     * @throws IllegalArgumentException when {@code wireName} does not match
     *     a known event.
     */
    public static HookEvent fromWireName(String wireName) {
        for (HookEvent e : values()) {
            if (e.wireName.equals(wireName)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown hook event wire name: " + wireName);
    }

    @Override
    public String toString() {
        return wireName;
    }
}
