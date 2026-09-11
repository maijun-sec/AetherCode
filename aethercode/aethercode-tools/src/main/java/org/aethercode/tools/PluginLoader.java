package org.aethercode.tools;

import org.aethercode.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * third-party {@link Tool} discovery via {@link ServiceLoader}. Modelled
 * on the TS {@code services/plugins/loader.ts}.
 *
 * <p>Plugins are drop-in classes that implement {@link Tool} and are listed
 * in {@code META-INF/services/org.aethercode.core.tool.Tool} inside the
 * plugin jar. {@link #loadAll()} walks the classpath and returns every
 * implementor.
 *
 * <p>Loading is best-effort: a malformed class is logged and skipped, so
 * one broken plugin doesn't break the others.
 */
public final class PluginLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PluginLoader.class);

    public static final String SERVICE_INTERFACE = "org.aethercode.core.tool.Tool";

    private final ClassLoader classLoader;

    public PluginLoader() { this(Thread.currentThread().getContextClassLoader()); }
    public PluginLoader(ClassLoader cl) { this.classLoader = cl == null ? PluginLoader.class.getClassLoader() : cl; }

    /** load every Tool service registered on the classpath. */
    public List<Tool> loadAll() {
        List<Tool> out = new ArrayList<>();
        try {
            ServiceLoader<Tool> sl = ServiceLoader.load(Tool.class, classLoader);
            for (Tool t : sl) {
                if (t == null) continue;
                out.add(t);
            }
        } catch (ServiceConfigurationError e) {
            LOG.warn("plugin load failed: {}", e.getMessage());
        }
        return out;
    }

    /** count, useful for status banners. */
    public int count() { return loadAll().size(); }

    /** filter loaded tools by name. */
    public List<Tool> findByName(String name) {
        if (name == null) return List.of();
        List<Tool> out = new ArrayList<>();
        for (Tool t : loadAll()) {
            if (name.equals(t.name())) out.add(t);
        }
        return out;
    }

    /** a static helper for callers that don't want to instantiate. */
    public static List<Tool> loadAllStatic() {
        return new PluginLoader().loadAll();
    }
}
