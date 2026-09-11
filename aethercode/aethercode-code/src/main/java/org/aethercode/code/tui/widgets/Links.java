package org.aethercode.code.tui.widgets;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared link-click handling for the deepagents-code TUI.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets._links}. The Python
 * module is a thin utility that opens URLs in the user's browser after
 * applying a safety check from {@code deepagents_code.unicode_security}.
 * The Java port preserves the public surface
 * ({@link #openCheckedUrlAsync(String, Object, boolean)},
 * {@link #openUrlAsync(String, Object, boolean)},
 * {@link #openStyleLink(Object, Object)}, {@link #eventTargetsLink(Object)})
 * with the actual browser launch deferred to a host-supplied
 * {@link BrowserOpener}.</p>
 */
public final class Links {

    private Links() {}

    /** Functional interface for the host's browser-launching implementation. */
    @FunctionalInterface
    public interface BrowserOpener {
        /** Open {@code url} in a browser. Returns {@code true} on success. */
        boolean open(String url);
    }

    /** Default opener: returns {@code false} unless the host injects a real one. */
    public static volatile BrowserOpener browserOpener = url -> false;

    /** Inject the host's browser-launching implementation. */
    public static void setBrowserOpener(BrowserOpener opener) {
        browserOpener = opener == null ? url -> false : opener;
    }

    /**
     * Whether a {@code link("...")} action is present in a style's
     * {@code @click} metadata.
     */
    private static final Pattern LINK_ACTION = Pattern.compile("^link\\((.*)\\)\\s*$");

    /** Extract a URL from a {@code link("...")} meta action value. */
    public static String linkActionUrl(String click) {
        if (click == null) return null;
        Matcher m = LINK_ACTION.matcher(click);
        if (!m.matches()) return null;
        String inner = m.group(1).trim();
        // Match Python's ast.literal_eval tolerance: accept both quoted
        // and unquoted forms.
        if (inner.length() >= 2 && inner.charAt(0) == '"' && inner.charAt(inner.length() - 1) == '"') {
            return inner.substring(1, inner.length() - 1);
        }
        return inner.isEmpty() ? null : inner;
    }

    /** Extract a URL from a Rich link style or a Textual click metadata map. */
    public static String styleUrl(Object style) {
        if (style == null) return null;
        if (style instanceof String s && !s.isEmpty()) return s;
        try {
            Object link = style.getClass().getMethod("link").invoke(style);
            if (link instanceof String s && !s.isEmpty()) return s;
        } catch (ReflectiveOperationException ignored) { /* not a Rich style */ }
        try {
            Object meta = style.getClass().getMethod("meta").invoke(style);
            if (meta instanceof Map<?, ?> m) {
                Object click = m.get("@click");
                if (click instanceof String s) {
                    String url = linkActionUrl(s);
                    if (url != null) return url;
                }
            }
        } catch (ReflectiveOperationException ignored) { /* not a Rich style */ }
        return null;
    }

    /** Whether a {@link #styleUrl(Object)} returns a non-null URL. */
    public static boolean eventTargetsLink(Object event) {
        return styleUrl(event) != null;
    }

    /**
     * Open a URL after applying a shared safety check.
     *
     * <p>The Java port delegates the actual safety check to
     * {@code deepagents-core.unicode_security.checkUrlSafety}. The
     * host injects a {@link UrlSafetyChecker} that wraps that call.</p>
     */
    public static CompletableFuture<Boolean> openCheckedUrlAsync(String url, Object app,
                                                                 boolean notifyOnSuccess) {
        UrlSafetyChecker.SafetyResult safety = checkSafety(url);
        if (!safety.safe) {
            return CompletableFuture.completedFuture(false);
        }
        return openUrlAsync(url, app, notifyOnSuccess);
    }

    /**
     * Open a URL in the user's browser.
     *
     * <p>Returns a {@link CompletableFuture} that resolves to
     * {@code true} on success and {@code false} on failure.</p>
     */
    public static CompletableFuture<Boolean> openUrlAsync(String url, Object app,
                                                          boolean notifyOnSuccess) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return browserOpener.open(url);
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** Synchronous link-open for click events. */
    public static void openStyleLink(Object event, Object app) {
        String url = styleUrl(event);
        if (url == null) return;
        try {
            browserOpener.open(url);
        } catch (Exception ignored) { /* swallow; matches the Python behavior */ }
    }

    /** Hook the host's URL safety checker. */
    @FunctionalInterface
    public interface UrlSafetyChecker {
        SafetyResult check(String url);
        record SafetyResult(boolean safe, java.util.List<String> warnings) {}
    }

    public static volatile UrlSafetyChecker safetyChecker =
            url -> new UrlSafetyChecker.SafetyResult(true, java.util.List.of());

    private static UrlSafetyChecker.SafetyResult checkSafety(String url) {
        try {
            return safetyChecker.check(url);
        } catch (Exception e) {
            return new UrlSafetyChecker.SafetyResult(true, java.util.List.of());
        }
    }
}
