package org.aethercode.mcp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/**
 * cross-platform "open URL in the user's default browser" helper. Used by the
 * OAuth flow to surface the authorisation URL.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Try {@link Desktop#browse(URI)} — works on macOS / most Linux desktops / Windows</li>
 *   <li>Fall back to platform-specific commands: {@code xdg-open} / {@code open} /
 *       {@code wslview}</li>
 *   <li>If all else fails, print the URL so the user can copy / paste</li>
 * </ol>
 */
public class BrowserLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserLauncher.class);

    public static boolean open(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception e) {
            LOG.warn("Desktop.browse failed: {}", e.getMessage());
        }
        // Fallback: shell out to the OS.
        String[] cmd = pickCommand();
        if (cmd == null) return false;
        try {
            new ProcessBuilder(cmd).inheritIO().start();
            return true;
        } catch (IOException e) {
            LOG.warn("{} failed: {}", String.join(" ", cmd), e.getMessage());
            return false;
        }
    }

    private static String[] pickCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return new String[]{"open"};
        if (os.contains("win")) return new String[]{"rundll32", "url.dll,FileProtocolHandler"};
        if (os.contains("nix") || os.contains("nux")) {
            if (exists("xdg-open")) return new String[]{"xdg-open"};
            if (exists("wslview")) return new String[]{"wslview"};
        }
        return null;
    }

    private static boolean exists(String cmd) {
        try {
            return new ProcessBuilder("which", cmd).start().waitFor() == 0;
        } catch (Exception e) { return false; }
    }
}
