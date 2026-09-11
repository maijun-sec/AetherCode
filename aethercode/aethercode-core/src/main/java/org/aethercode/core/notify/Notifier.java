package org.aethercode.core.notify;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * a pluggable surface for OS-level notifications (macOS Notification Center,
 * Windows toast, Linux libnotify). The default impl uses {@link SystemTray} where
 * available; the {@link NoOp} variant silently drops notifications — useful for
 * tests and for embedded / CI use.
 *
 * <p>{@link #notify(String, String, Level)} returns false when the host can't
 * surface the notification (no tray, no permission, etc.) — callers should
 * treat that as best-effort, not as an error.
 */
public interface Notifier {

    enum Level { INFO, SUCCESS, WARNING, ERROR }

    boolean notify(String title, String body, Level level);

    /** collect every notification, never show them. Used in unit tests. */
    final class Recording implements Notifier {
        public record Entry(String title, String body, Level level) {}
        private final List<Entry> entries = new ArrayList<>();
        @Override public boolean notify(String title, String body, Level level) {
            entries.add(new Entry(title, body, level));
            return true;
        }
        public List<Entry> entries() { return List.copyOf(entries); }
    }

    /** silent — for tests and headless builds. */
    final class NoOp implements Notifier {
        @Override public boolean notify(String title, String body, Level level) { return false; }
    }

    /** real OS notification via {@link SystemTray}. */
    final class SystemTrayImpl implements Notifier {
        private static final Logger LOG = LoggerFactory.getLogger(SystemTrayImpl.class);
        private final TrayIcon icon;

        public SystemTrayImpl() {
            TrayIcon created = null;
            try {
                if (SystemTray.isSupported()) {
                    TrayIcon ti = new TrayIcon(createIcon(), "AetherCode");
                    ti.setImageAutoSize(true);
                    SystemTray.getSystemTray().add(ti);
                    created = ti;
                }
            } catch (AWTException | RuntimeException e) {
                LOG.warn("SystemTray init failed: {}", e.getMessage());
            }
            this.icon = created;
        }

        @Override
        public boolean notify(String title, String body, Level level) {
            if (icon == null) return false;
            try {
                TrayIcon.MessageType mt = switch (level) {
                    case SUCCESS -> TrayIcon.MessageType.INFO;
                    case INFO    -> TrayIcon.MessageType.INFO;
                    case WARNING -> TrayIcon.MessageType.WARNING;
                    case ERROR   -> TrayIcon.MessageType.ERROR;
                };
                icon.displayMessage(title, body, mt);
                return true;
            } catch (RuntimeException e) {
                LOG.warn("notify failed: {}", e.getMessage());
                return false;
            }
        }

        private static Image createIcon() {
            try {
                return Toolkit.getDefaultToolkit().createImage(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, 0, 4);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
