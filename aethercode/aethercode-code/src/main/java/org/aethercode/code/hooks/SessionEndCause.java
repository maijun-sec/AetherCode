package org.aethercode.code.hooks;

/**
 * Reason a session ended.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.SessionEndCause}
 * {@code StrEnum}.</p>
 */
public enum SessionEndCause {
    EXIT,
    CLEAR,
    RESUME,
    LOGOUT,
    PROMPT_INPUT_EXIT,
    OTHER
}
