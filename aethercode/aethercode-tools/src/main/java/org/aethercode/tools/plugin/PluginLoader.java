package org.aethercode.tools.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * discovers and loads plugins from a directory tree. The default layout is
 * {@code <root>/<plugin-name>/plugin.json} where each sub-directory is one plugin.
 *
 * <p>Loading is a two-step process:
 * <ol>
 *   <li>Read {@code plugin.json} (or {@code .aethercode-plugin.json}) via {@link PluginManifest}.</li>
 *   <li>Build a {@link URLClassLoader} from the plugin dir and any {@code lib/*.jar} files,
 *       then load the entryPoint class by name and instantiate it (must have a public
 *       no-arg constructor and implement {@link Plugin}).</li>
 * </ol>
 *
 * <p>{@link #shutdownAll()} calls {@link Plugin#shutdown()} on every loaded plugin in
 * reverse-load order and clears the registry. Safe to call multiple times.
 *
 * <p>The loader is thread-safe; the loaded-plugin list is a {@link CopyOnWriteArrayList}.
 */
public final class PluginLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PluginLoader.class);

    private final Path root;
    private final Map<String, Object> services;
    private final List<LoadedPlugin> loaded = new CopyOnWriteArrayList<>();
    private volatile boolean shutdown = false;

    public PluginLoader(Path root) {
        this(root, Map.of());
    }

    public PluginLoader(Path root, Map<String, Object> services) {
        this.root = Objects.requireNonNull(root, "root");
        this.services = services == null ? Map.of() : Map.copyOf(services);
    }

    /** The directory the loader scans for plugin sub-directories. */
    public Path root() { return root; }

    /** A read-only view of the currently loaded plugins. */
    public List<LoadedPlugin> loaded() { return Collections.unmodifiableList(loaded); }

    /**
     * Scan the root directory and load every valid plugin. Failures for individual plugins
     * are logged and skipped; the loader keeps going so one bad plugin can't block the rest.
     *
     * @return the list of plugins that loaded successfully
     */
    public List<LoadedPlugin> loadAll() {
        if (shutdown) throw new IllegalStateException("PluginLoader is shut down");
        if (!Files.isDirectory(root)) {
            LOG.debug("plugin root does not exist or is not a directory: {}", root);
            return List.of();
        }
        List<LoadedPlugin> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            // Sort for deterministic load order.
            List<Path> subdirs = new ArrayList<>();
            for (Path p : stream) if (Files.isDirectory(p)) subdirs.add(p);
            subdirs.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            for (Path pluginDir : subdirs) {
                LoadedPlugin lp = loadOne(pluginDir);
                if (lp != null) out.add(lp);
            }
        } catch (IOException e) {
            LOG.warn("failed to scan plugin root {}: {}", root, e.getMessage());
        }
        return out;
    }

    /**
     * Load a single plugin from its directory. Returns null if the directory has no
     * manifest, the manifest is invalid, or instantiation fails.
     */
    public LoadedPlugin loadOne(Path pluginDir) {
        Path manifestPath = resolveManifest(pluginDir);
        if (manifestPath == null) {
            LOG.debug("no manifest in {}", pluginDir);
            return null;
        }
        PluginManifest.Manifest manifest;
        try {
            manifest = PluginManifest.loadFrom(manifestPath);
        } catch (PluginManifestException e) {
            LOG.warn("failed to load manifest at {}: {}", manifestPath, e.getMessage());
            return null;
        }
        List<String> issues = PluginManifest.validate(manifest);
        if (!issues.isEmpty()) {
            LOG.warn("manifest {} invalid: {}", manifestPath, String.join("; ", issues));
            return null;
        }

        URLClassLoader cl = buildClassLoader(pluginDir);
        Plugin instance;
        try {
            Class<?> klass = Class.forName(manifest.entryPoint(), true, cl);
            if (!Plugin.class.isAssignableFrom(klass)) {
                LOG.warn("entryPoint {} does not implement Plugin", manifest.entryPoint());
                try { cl.close(); } catch (IOException ignored) {}
                return null;
            }
            instance = (Plugin) klass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            LOG.warn("failed to instantiate {} from {}: {}",
                    manifest.entryPoint(), pluginDir, t.getMessage());
            try { cl.close(); } catch (IOException ignored) {}
            return null;
        }

        Plugin.PluginContext ctx = Plugin.PluginContext.of(manifest, pluginDir, services);
        try {
            instance.init(ctx);
        } catch (Throwable t) {
            LOG.warn("plugin {} init failed: {}", manifest.name(), t.getMessage());
            try {
                instance.shutdown();
            } catch (Throwable ignored) {}
            try { cl.close(); } catch (IOException ignored) {}
            return null;
        }

        LoadedPlugin lp = new LoadedPlugin(manifest, pluginDir, instance, cl);
        loaded.add(lp);
        LOG.info("loaded plugin: {} v{} from {}", manifest.name(), manifest.version(), pluginDir);
        return lp;
    }

    /** Shut down all loaded plugins in reverse-load order and close their classloaders. */
    public void shutdownAll() {
        if (shutdown) return;
        shutdown = true;
        // Reverse iteration to tear down newest-first.
        for (int i = loaded.size() - 1; i >= 0; i--) {
            LoadedPlugin lp = loaded.get(i);
            try {
                lp.instance().shutdown();
            } catch (Throwable t) {
                LOG.warn("plugin {} shutdown failed: {}", lp.manifest().name(), t.getMessage());
            }
            try {
                lp.classLoader().close();
            } catch (IOException ignored) {}
        }
        loaded.clear();
    }

    /** A successfully loaded plugin: the manifest, the install dir, the instance, and the CL. */
    public record LoadedPlugin(
            PluginManifest.Manifest manifest,
            Path pluginDir,
            Plugin instance,
            URLClassLoader classLoader
    ) {}

    // ----- internals -----

    private static Path resolveManifest(Path pluginDir) {
        // Accept either plugin.json or .aethercode-plugin.json.
        Path p1 = pluginDir.resolve("plugin.json");
        if (Files.isRegularFile(p1)) return p1;
        Path p2 = pluginDir.resolve(".aethercode-plugin.json");
        if (Files.isRegularFile(p2)) return p2;
        return null;
    }

    private static URLClassLoader buildClassLoader(Path pluginDir) {
        List<URL> urls = new ArrayList<>();
        // Add the plugin's own directory so entryPoints can sit as .class files alongside.
        try { urls.add(pluginDir.toUri().toURL()); } catch (Exception ignored) {}
        // Add any jars under <plugin>/lib/*.jar
        Path lib = pluginDir.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(lib, "*.jar")) {
                for (Path jar : stream) {
                    try { urls.add(jar.toUri().toURL()); } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
        }
        // Use the loader's own classloader as the parent so plugins can see host types
        // (Plugin interface, etc.) without being able to reload them. The plugin dir is
        // consulted first, so a plugin can still shadow a host class if it really needs to.
        ClassLoader parent = PluginLoader.class.getClassLoader();
        if (parent == null) parent = ClassLoader.getSystemClassLoader();
        return new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    /** Build a fresh loader with no services. */
    public static PluginLoader at(Path root) {
        return new PluginLoader(root, Map.of());
    }

    /** Build a fresh loader with the given services. */
    public static PluginLoader at(Path root, Map<String, Object> services) {
        return new PluginLoader(root, services);
    }

    /** Quick helper: discover+load+shutdown. Useful for one-shot CLI use. */
    public static List<LoadedPlugin> loadAndShutdown(Path root, Map<String, Object> services) {
        PluginLoader loader = new PluginLoader(root, services);
        try {
            return loader.loadAll();
        } finally {
            loader.shutdownAll();
        }
    }
}
