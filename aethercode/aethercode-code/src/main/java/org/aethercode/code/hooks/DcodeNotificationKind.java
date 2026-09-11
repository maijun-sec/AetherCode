package org.aethercode.code.hooks;

/**
 * Kind of a dcode notification surfaced through the
 * {@link HookEvent#NOTIFICATION} lifecycle event.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.DcodeNotificationKind}
 * {@code StrEnum}.</p>
 */
public enum DcodeNotificationKind {
    PERMISSION_REQUIRED,
    AGENT_NEEDS_INPUT,
    AGENT_COMPLETED,
    COLD_CACHE_WARNING
}
