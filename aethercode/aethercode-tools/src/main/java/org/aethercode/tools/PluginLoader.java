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
