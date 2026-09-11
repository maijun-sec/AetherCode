package org.aethercode.code.hooks;

/**
 * Notification payload carried by a {@link NotificationEvent}.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.DcodeNotification} record.</p>
 */
public record DcodeNotification(
        DcodeNotificationKind type,
        String message,
        String title) {

    public DcodeNotification {
        if (message == null) {
            message = "";
        }
    }
}
