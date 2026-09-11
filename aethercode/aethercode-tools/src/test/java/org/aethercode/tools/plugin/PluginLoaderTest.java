package org.aethercode.tools.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PluginLoaderTest {

    @Test
    void loadAll_findsValidPluginAndCallsLifecycle(@TempDir Path tmp) throws Exception {
        Path pluginDir = tmp.resolve("hello");
        Files.createDirectories(pluginDir);
        writeFile(pluginDir.resolve("plugin.json"),
                "{\"name\":\"hello\",\"version\":\"1.0.0\",\"entryPoint\":\"HelloPlugin\"}");
        // Compile a simple plugin class
        Path src = pluginDir.resolve("HelloPlugin.java");
        writeFile(src, ""
                + "import org.aethercode.tools.plugin.Plugin;\n"
                + "import org.slf4j.Logger;\n"
                + "public class HelloPlugin implements Plugin {\n"
                + "  public static String LAST;\n"
                + "  public static String LAST_NAME;\n"
                + "  public void init(PluginContext ctx) {\n"
                + "    LAST = \"init:\" + ctx.manifest().name();\n"
                + "    LAST_NAME = ctx.manifest().name();\n"
                + "  }\n"
                + "  public void shutdown() { LAST = \"shutdown:\" + LAST; }\n"
                + "}\n");
        compile(src, pluginDir);

        PluginLoader loader = PluginLoader.at(tmp);
        List<PluginLoader.LoadedPlugin> loaded = loader.loadAll();
        assertEquals(1, loaded.size());
        PluginLoader.LoadedPlugin lp = loaded.get(0);
        assertEquals("hello", lp.manifest().name());
        assertEquals("1.0.0", lp.manifest().version());
        assertNotNull(lp.instance());
        assertInstanceOf(Plugin.class, lp.instance());
        // Verify init ran by querying the static field through a separate classloader invocation
        // (static fields are per-classloader, so we check via reflection on the plugin's class).
        Class<?> klass = lp.instance().getClass();
        assertEquals("init:hello", klass.getField("LAST").get(null));
        assertEquals("hello", klass.getField("LAST_NAME").get(null));

        loader.shutdownAll();
        assertEquals("shutdown:init:hello", klass.getField("LAST").get(null));
    }

    @Test
    void loadAll_skipsDirectoryWithoutManifest(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("notaplugin"));
        PluginLoader loader = PluginLoader.at(tmp);
        assertTrue(loader.loadAll().isEmpty());
    }

    @Test
    void loadAll_skipsInvalidManifest(@TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad");
        Files.createDirectories(bad);
        writeFile(bad.resolve("plugin.json"), "{\"name\":\"x\"}");  // missing version, entryPoint
        PluginLoader loader = PluginLoader.at(tmp);
        assertTrue(loader.loadAll().isEmpty());
    }

    @Test
    void loadAll_skipsMalformedJson(@TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("broken");
        Files.createDirectories(bad);
        writeFile(bad.resolve("plugin.json"), "{ not json");
        PluginLoader loader = PluginLoader.at(tmp);
        assertTrue(loader.loadAll().isEmpty());
    }

    @Test
    void loadAll_skipsEntryPointThatDoesNotImplementPlugin(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("notplugin");
        Files.createDirectories(p);
        writeFile(p.resolve("plugin.json"),
                "{\"name\":\"notplugin\",\"version\":\"1.0.0\",\"entryPoint\":\"WrongType\"}");
        writeFile(p.resolve("WrongType.java"),
                "public class WrongType { public WrongType() {} }\n");
        compile(p.resolve("WrongType.java"), p);
        PluginLoader loader = PluginLoader.at(tmp);
        assertTrue(loader.loadAll().isEmpty());
    }

    @Test
    void loadAll_continuesAfterOneFails(@TempDir Path tmp) throws Exception {
        // First plugin: missing entryPoint class
        Path bad = tmp.resolve("broken");
        Files.createDirectories(bad);
        writeFile(bad.resolve("plugin.json"),
                "{\"name\":\"broken\",\"version\":\"1.0.0\",\"entryPoint\":\"NoSuchClass\"}");
        // Second plugin: valid
        Path good = tmp.resolve("good");
        Files.createDirectories(good);
        writeFile(good.resolve("plugin.json"),
                "{\"name\":\"good\",\"version\":\"1.0.0\",\"entryPoint\":\"GoodPlugin\"}");
        writeFile(good.resolve("GoodPlugin.java"),
                "import org.aethercode.tools.plugin.Plugin;\n"
              + "public class GoodPlugin implements Plugin {\n"
              + "  public void init(PluginContext c) {}\n"
              + "  public void shutdown() {}\n"
              + "}\n");
        compile(good.resolve("GoodPlugin.java"), good);

        PluginLoader loader = PluginLoader.at(tmp);
        List<PluginLoader.LoadedPlugin> loaded = loader.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("good", loaded.get(0).manifest().name());
    }

    @Test
    void loadAll_propagatesServiceMapToInit(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("svc");
        Files.createDirectories(p);
        writeFile(p.resolve("plugin.json"),
                "{\"name\":\"svc\",\"version\":\"1.0.0\",\"entryPoint\":\"SvcPlugin\"}");
        writeFile(p.resolve("SvcPlugin.java"), ""
                + "import org.aethercode.tools.plugin.Plugin;\n"
                + "public class SvcPlugin implements Plugin {\n"
                + "  public static String SAVED;\n"
                + "  public void init(PluginContext ctx) { SAVED = String.valueOf(ctx.services().get(\"greeting\")); }\n"
                + "  public void shutdown() {}\n"
                + "}\n");
        compile(p.resolve("SvcPlugin.java"), p);

        PluginLoader loader = new PluginLoader(tmp, Map.of("greeting", "hello-svc"));
        List<PluginLoader.LoadedPlugin> loaded = loader.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("hello-svc", loaded.get(0).instance().getClass().getField("SAVED").get(null));
    }

    @Test
    void loadAll_acceptsAethercodePrefixedManifestName(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("dot");
        Files.createDirectories(p);
        // Use the alternate name ".aethercode-plugin.json"
        writeFile(p.resolve(".aethercode-plugin.json"),
                "{\"name\":\"dot\",\"version\":\"1.0.0\",\"entryPoint\":\"DotPlugin\"}");
        writeFile(p.resolve("DotPlugin.java"),
                "import org.aethercode.tools.plugin.Plugin;\n"
              + "public class DotPlugin implements Plugin {\n"
              + "  public void init(PluginContext c) {}\n"
              + "  public void shutdown() {}\n"
              + "}\n");
        compile(p.resolve("DotPlugin.java"), p);
        PluginLoader loader = PluginLoader.at(tmp);
        assertEquals(1, loader.loadAll().size());
    }

    @Test
    void shutdownAll_isIdempotent(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("idem");
        Files.createDirectories(p);
        writeFile(p.resolve("plugin.json"),
                "{\"name\":\"idem\",\"version\":\"1.0.0\",\"entryPoint\":\"IdemPlugin\"}");
        writeFile(p.resolve("IdemPlugin.java"),
                "import org.aethercode.tools.plugin.Plugin;\n"
              + "public class IdemPlugin implements Plugin {\n"
              + "  public void init(PluginContext c) {}\n"
              + "  public void shutdown() {}\n"
              + "}\n");
        compile(p.resolve("IdemPlugin.java"), p);
        PluginLoader loader = PluginLoader.at(tmp);
        loader.loadAll();
        loader.shutdownAll();
        loader.shutdownAll(); // second call must not throw
        assertTrue(loader.loaded().isEmpty());
    }

    @Test
    void loadAll_sortsPluginsDeterministically(@TempDir Path tmp) throws Exception {
        // Create plugins in non-alphabetical order; loader should return them sorted.
        for (String n : new String[]{"zeta", "alpha", "mu"}) {
            Path p = tmp.resolve(n);
            Files.createDirectories(p);
            writeFile(p.resolve("plugin.json"),
                    "{\"name\":\"" + n + "\",\"version\":\"1.0.0\",\"entryPoint\":\"" + cap(n) + "Plugin\"}");
            writeFile(p.resolve(cap(n) + "Plugin.java"),
                    "import org.aethercode.tools.plugin.Plugin;\n"
                  + "public class " + cap(n) + "Plugin implements Plugin {\n"
                  + "  public void init(PluginContext c) {}\n"
                  + "  public void shutdown() {}\n"
                  + "}\n");
            compile(p.resolve(cap(n) + "Plugin.java"), p);
        }
        PluginLoader loader = PluginLoader.at(tmp);
        List<PluginLoader.LoadedPlugin> loaded = loader.loadAll();
        assertEquals(3, loaded.size());
        assertEquals("alpha", loaded.get(0).manifest().name());
        assertEquals("mu",    loaded.get(1).manifest().name());
        assertEquals("zeta",  loaded.get(2).manifest().name());
    }

    @Test
    void loadAll_returnsEmptyForMissingRoot() {
        PluginLoader loader = PluginLoader.at(Path.of("Z:/this/does/not/exist"));
        assertTrue(loader.loadAll().isEmpty());
    }

    @Test
    void loadedCarriesClassLoader(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("cl");
        Files.createDirectories(p);
        writeFile(p.resolve("plugin.json"),
                "{\"name\":\"cl\",\"version\":\"1.0.0\",\"entryPoint\":\"ClPlugin\"}");
        writeFile(p.resolve("ClPlugin.java"),
                "import org.aethercode.tools.plugin.Plugin;\n"
              + "public class ClPlugin implements Plugin {\n"
              + "  public void init(PluginContext c) {}\n"
              + "  public void shutdown() {}\n"
              + "}\n");
        compile(p.resolve("ClPlugin.java"), p);
        PluginLoader loader = PluginLoader.at(tmp);
        PluginLoader.LoadedPlugin lp = loader.loadAll().get(0);
        assertNotNull(lp.classLoader());
        assertInstanceOf(URLClassLoader.class, lp.classLoader());
    }

    // ---------- helpers ----------

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static void writeFile(Path p, String content) throws IOException {
        Files.writeString(p, content);
    }

    private static void compile(Path javaSrc, Path outDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler; tests need a JDK, not a JRE");
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(javaSrc.toFile());
            // Compile with the test classpath plus the source output dir.
            String classpath = System.getProperty("java.class.path") + java.io.File.pathSeparator + outDir;
            // Tools module (aethercode-tools) must already be on the classpath.
            List<String> opts = List.of("-d", outDir.toString(), "-cp", classpath);
            javax.tools.DiagnosticCollector<JavaFileObject> diagnostics = new javax.tools.DiagnosticCollector<>();
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, opts, null, units);
            Boolean ok = task.call();
            if (!Boolean.TRUE.equals(ok)) {
                StringBuilder sb = new StringBuilder("compilation failed for " + javaSrc + ":\n");
                for (javax.tools.Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    sb.append("  ").append(d.getKind()).append(": ")
                      .append(d.getSource()).append(":").append(d.getLineNumber())
                      .append(" — ").append(d.getMessage(null)).append('\n');
                }
                throw new IOException(sb.toString());
            }
        }
    }
}
