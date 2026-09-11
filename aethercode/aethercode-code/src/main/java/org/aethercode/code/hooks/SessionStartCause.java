package org.aethercode.code.hooks;

/**
 * Reason a session started.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.SessionStartCause}
 * {@code StrEnum}.</p>
 */
public enum SessionStartCause {
    STARTUP,
    RESUME,
    CLEAR,
    COMPACT
}
