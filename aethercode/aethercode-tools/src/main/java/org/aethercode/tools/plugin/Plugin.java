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

package org.aethercode.tools.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

/**
 * a third-party extension loaded by {@link PluginLoader}. Plugins live in
 * {@code .aethercode/plugins/<name>/} and are declared by a {@link PluginManifest.Manifest}.
 *
 * <p>The {@link PluginManifest.Manifest#entryPoint()} field names a class on the plugin's
 * classpath that implements this interface and has a public no-arg constructor. The loader
 * calls {@link #init(PluginContext)} once after instantiation, then {@link #shutdown()} when
 * the host tears down.
 */
public interface Plugin {

    /**
     * Called once after the plugin is loaded. The {@code ctx} carries the host services the
     * plugin can use: a logger, the plugin's install dir, the parsed manifest, and a small
     * service registry the host passes in.
     */
    void init(PluginContext ctx);

    /**
     * Called once at host shutdown. The plugin should release any resources it acquired in
     * {@link #init}. Idempotent — the loader may call it more than once.
     */
    void shutdown();

    /**
     * Optional human-readable name. Defaults to the class's simple name.
     */
    default String name() { return getClass().getSimpleName(); }

    /**
     * The host-side context passed to {@link #init(PluginContext)}. {@code services} is an
     * open map the host uses to pass tool registries, config tables, or other shared state.
     * Plugins should look up keys by name and tolerate missing entries.
     */
    record PluginContext(
            PluginManifest.Manifest manifest,
            Path pluginDir,
            Logger log,
            Map<String, Object> services
    ) {
        public static PluginContext of(PluginManifest.Manifest m, Path dir) {
            return new PluginContext(m, dir, LoggerFactory.getLogger("plugin." + m.name()), Map.of());
        }
        public static PluginContext of(PluginManifest.Manifest m, Path dir, Map<String, Object> services) {
            return new PluginContext(m, dir, LoggerFactory.getLogger("plugin." + m.name()), services);
        }
    }
}
