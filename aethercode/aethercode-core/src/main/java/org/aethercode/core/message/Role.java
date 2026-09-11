package org.aethercode.core.message;

/**
 * Message role — the three that matter for an LLM conversation.
 *
 * <p>Mirrors the TS original's `message.role` literal union.
 */
public enum Role {
    USER,
    ASSISTANT,
    SYSTEM,
    /** synthetic "user" message carrying a tool result; not the model role. */
    TOOL_RESULT
}
