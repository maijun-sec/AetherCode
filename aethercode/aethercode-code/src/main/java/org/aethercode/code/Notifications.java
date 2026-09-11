package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-app notification surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.notifications}
 * module. The Java port exposes a small listener-driven API so the TUI
 * host can subscribe to user-visible notifications and render them via
 * the in-app toasts.</p>
 */
public final class Notifications {
    private Notifications() {}

    private static final Logger LOG = LoggerFactory.getLogger(Notifications.class);

    /** A queued user-visible notification. */
    public record PendingNotification(
            String id,
            ActionId actionId,
            String title,
            String body,
            long createdAtEpochMs) {
    }

    /** Action identifiers carried with each notification. */
    public enum ActionId {
        UPDATE_AVAILABLE,
        INSTALL_REMINDER,
        RESTART_REQUIRED,
        CONNECTION_LOST,
        TOOL_CANCELLED
    }

    private static final CopyOnWriteArrayList<Consumer<PendingNotification>> LISTENERS =
            new CopyOnWriteArrayList<>();

    /** Add a notification listener. */
    public static void addListener(Consumer<PendingNotification> listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    /** Remove a notification listener. */
    public static void removeListener(Consumer<PendingNotification> listener) {
        LISTENERS.remove(listener);
    }

    /** Emit a new notification. */
    public static PendingNotification emit(ActionId actionId, String title, String body) {
        PendingNotification n = new PendingNotification(
                UUID.randomUUID().toString(), actionId,
                title, body, System.currentTimeMillis());
        LOG.debug("Notification: {} - {}", title, body);
        for (Consumer<PendingNotification> l : LISTENERS) {
            try { l.accept(n); } catch (Exception e) { LOG.debug("Listener failed", e); }
        }
        return n;
    }
}
