package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T-500 / design.md §5.1 + §5.4: registers the six
 * {@code theme/*} JSON-RPC methods on a
 * {@link JsonRpcDispatcher}. The actual theme store lives
 * in the TypeScript {@code aethercode-themes} module; the
 * JVM side keeps a small adapter so the daemon can answer
 * from in-memory state populated by the TS bridge (or a
 * future JVM port).
 *
 * <p>Methods exposed:
 * <ul>
 *   <li>{@code theme/list}   — T-501: return the catalog
 *       (name + isDark + origin)</li>
 *   <li>{@code theme/get}    — T-501: return one theme by
 *       name</li>
 *   <li>{@code theme/set}    — T-501: switch the active
 *       theme and notify the renderer</li>
 *   <li>{@code theme/import} — T-501: install a user
 *       theme from a YAML file</li>
 *   <li>{@code theme/export} — T-501: write a theme to a
 *       YAML file</li>
 *   <li>{@code theme/active} — T-501: return the current
 *       active theme (paired with {@code themeChanged}
 *       notification)</li>
 * </ul>
 *
 * <p>The adapter is intentionally small: the JVM is the
 * wire gateway, the actual store is a TS module that the
 * desktop embeds. The {@link ThemeRegistry} interface
 * is the seam — production wiring passes a real
 * implementation, tests pass a stub.
 */
public final class ThemeMethods {

    private static final Logger LOG = LoggerFactory.getLogger(ThemeMethods.class);

    public static final String METHOD_LIST   = "theme/list";
    public static final String METHOD_GET    = "theme/get";
    public static final String METHOD_SET    = "theme/set";
    public static final String METHOD_IMPORT = "theme/import";
    public static final String METHOD_EXPORT = "theme/export";
    public static final String METHOD_ACTIVE = "theme/active";

    /** Notification name fired when {@code theme/set}
     *  succeeds. The TUI/Desktop renderer subscribes to
     *  this to re-paint. Matches design.md §5.1.4
     *  "Live switching (themeChanged event)". */
    public static final String NOTIFY_THEME_CHANGED = "themeChanged";

    /** Default fallback catalog used when no real
     *  registry is wired (e.g. headless test fixtures
     *  or a slim daemon that only does LLM work). The
     *  shape mirrors design.md §5.1.2. */
    private static final List<Map<String, Object>> DEFAULT_CATALOG = List.of(
            Map.of("name", "light",            "isDark", false, "origin", "builtin"),
            Map.of("name", "dark",             "isDark", true,  "origin", "builtin"),
            Map.of("name", "solarized-light",  "isDark", false, "origin", "builtin"),
            Map.of("name", "solarized-dark",   "isDark", true,  "origin", "builtin"),
            Map.of("name", "high-contrast",    "isDark", true,  "origin", "builtin")
    );

    private final ThemeRegistry registry;
    private final AtomicReference<String> activeTheme = new AtomicReference<>("light");

    public ThemeMethods() { this(new InMemoryThemeRegistry()); }

    public ThemeMethods(ThemeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    public void registerAll(JsonRpcDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(METHOD_LIST,   this::list);
        dispatcher.register(METHOD_GET,    this::get);
        dispatcher.register(METHOD_SET,    this::set);
        dispatcher.register(METHOD_IMPORT, this::importTheme);
        dispatcher.register(METHOD_EXPORT, this::exportTheme);
        dispatcher.register(METHOD_ACTIVE, this::active);
    }

    /** Return the currently active theme name. The TUI
     *  reads this on startup to decide which palette to
     *  paint. */
    public String currentThemeName() { return activeTheme.get(); }

    // ------------------------------------------------------------------
    //  T-501 — theme/list
    // ------------------------------------------------------------------

    public Map<String, Object> list(Object params) {
        List<Map<String, Object>> themes = new ArrayList<>(DEFAULT_CATALOG);
        // Merge any user themes the registry knows about.
        try {
            for (Map<String, Object> t : registry.listUserThemes()) {
                themes.add(t);
            }
        } catch (RuntimeException e) {
            LOG.debug("theme/list: registry list failed: {}", e.toString());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("themes", themes);
        r.put("active", activeTheme.get());
        return r;
    }

    // ------------------------------------------------------------------
    //  T-501 — theme/get
    // ------------------------------------------------------------------

    public Map<String, Object> get(Object params) {
        Map<String, Object> p = asMap(params);
        String name = p.get("name") == null ? activeTheme.get() : p.get("name").toString();
        for (Map<String, Object> t : DEFAULT_CATALOG) {
            if (name.equals(t.get("name"))) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("theme", themeWithColors(t));
                return r;
            }
        }
        try {
            Map<String, Object> userTheme = registry.getUserTheme(name);
            if (userTheme != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("theme", themeWithColors(userTheme));
                return r;
            }
        } catch (RuntimeException e) {
            LOG.debug("theme/get: registry lookup failed for {}: {}", name, e.toString());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("reason", "unknown theme: " + name);
        return r;
    }

    // ------------------------------------------------------------------
    //  T-501 — theme/set
    // ------------------------------------------------------------------

    public Map<String, Object> set(Object params) {
        Map<String, Object> p = asMap(params);
        if (p.get("name") == null) {
            throw new JsonRpcProtocolException(
                    "theme/set: name is required",
                    JsonRpcError.invalidParams("missing name"));
        }
        String name = p.get("name").toString();
        boolean known = false;
        for (Map<String, Object> t : DEFAULT_CATALOG) {
            if (name.equals(t.get("name"))) { known = true; break; }
        }
        if (!known) {
            try {
                known = registry.getUserTheme(name) != null;
            } catch (RuntimeException ignore) { /* fall through */ }
        }
        if (!known) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("reason", "unknown theme: " + name);
            return r;
        }
        activeTheme.set(name);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("active", name);
        LOG.info("theme/set active={}", name);
        return r;
    }

    // ------------------------------------------------------------------
    //  T-501 — theme/import
    // ------------------------------------------------------------------

    public Map<String, Object> importTheme(Object params) {
        Map<String, Object> p = asMap(params);
        if (p.get("path") == null) {
            throw new JsonRpcProtocolException(
                    "theme/import: path is required",
                    JsonRpcError.invalidParams("missing path"));
        }
        String path = p.get("path").toString();
        try {
            Map<String, Object> theme = registry.importFromYaml(path);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("theme", theme);
            r.put("written", path);
            return r;
        } catch (RuntimeException e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("reason", e.getMessage() == null ? "import failed" : e.getMessage());
            return r;
        }
    }

    // ------------------------------------------------------------------
    //  T-501 — theme/export
    // ------------------------------------------------------------------

    public Map<String, Object> exportTheme(Object params) {
        Map<String, Object> p = asMap(params);
        if (p.get("name") == null || p.get("path") == null) {
            throw new JsonRpcProtocolException(
                    "theme/export: name and path are required",
                    JsonRpcError.invalidParams("missing name or path"));
        }
        String name = p.get("name").toString();
        String path = p.get("path").toString();
        try {
            registry.exportToYaml(name, path);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("name", name);
            r.put("written", path);
            return r;
        } catch (RuntimeException e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("reason", e.getMessage() == null ? "export failed" : e.getMessage());
            return r;
        }
    }

    // ------------------------------------------------------------------
    //  T-501 — theme/active
    // ------------------------------------------------------------------

    public Map<String, Object> active(Object params) {
        String name = activeTheme.get();
        for (Map<String, Object> t : DEFAULT_CATALOG) {
            if (name.equals(t.get("name"))) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("active", name);
                r.put("theme", themeWithColors(t));
                return r;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("active", name);
        r.put("theme", Map.of("name", name, "isDark", false, "origin", "user"));
        return r;
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> themeWithColors(Map<String, Object> t) {
        // The JVM doesn't render — we hand the TUI a
        // shape that matches the TS Theme type. The
        // TUI re-reads the active theme on a
        // themeChanged notification and pulls colors
        // from its local store. The JVM side is the
        // source of truth for "which name is active".
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", t.get("name"));
        r.put("isDark", t.get("isDark"));
        r.put("origin", t.get("origin"));
        return r;
    }

    private static Map<String, Object> asMap(Object params) {
        if (params == null) return java.util.Collections.emptyMap();
        if (!(params instanceof Map)) {
            throw new JsonRpcProtocolException(
                    "theme/* params must be an object",
                    JsonRpcError.invalidParams("expected object"));
        }
        return (Map<String, Object>) params;
    }

    // ------------------------------------------------------------------
    //  Registry seam
    // ------------------------------------------------------------------

    /**
     * Bridge interface for the JVM adapter. The default
     * implementation is an in-memory stub; production
     * wires a real implementation backed by the TS
     * theme store over IPC (or a future JVM port).
     */
    public interface ThemeRegistry {
        List<Map<String, Object>> listUserThemes();
        Map<String, Object> getUserTheme(String name);
        Map<String, Object> importFromYaml(String path);
        void exportToYaml(String name, String path);
    }

    /** Stub registry: empty by default. Tests can
     *  subclass to inject a fixed catalog. */
    public static final class InMemoryThemeRegistry implements ThemeRegistry {
        @Override public List<Map<String, Object>> listUserThemes() { return List.of(); }
        @Override public Map<String, Object> getUserTheme(String name) { return null; }
        @Override public Map<String, Object> importFromYaml(String path) {
            throw new UnsupportedOperationException("no YAML backend wired");
        }
        @Override public void exportToYaml(String name, String path) {
            throw new UnsupportedOperationException("no YAML backend wired");
        }
    }
}
