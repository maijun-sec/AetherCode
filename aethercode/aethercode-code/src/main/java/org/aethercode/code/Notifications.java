/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
