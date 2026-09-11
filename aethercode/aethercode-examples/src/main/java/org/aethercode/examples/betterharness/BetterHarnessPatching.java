package org.aethercode.examples.betterharness;

import org.aethercode.examples.support.MiniJson;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Surface patching helpers.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/better-harness/better_harness/patching.py}.
 * Provides variant construction, module-attribute overrides,
 * workspace file overrides, and the {@code sitecustomize} shim
 * that the Python port uses to apply a variant to a subprocess.</p>
 *
 * <p>The Java port mirrors the same shape &mdash; it works on
 * class-level static fields instead of module attributes, but the
 * apply contract is identical: a {@code module:field} target and a
 * new value.</p>
 */
public final class BetterHarnessPatching {
    private BetterHarnessPatching() {}

    /** Environment variable carrying the path to a serialized variant. */
    public static final String VARIANT_ENV = "BETTER_HARNESS_VARIANT_FILE";

    /**
     * Build the baseline variant from the configured surface bases.
     * Mirrors the Python port's {@code build_baseline_variant}.
     */
    public static BetterHarnessCore.Variant buildBaselineVariant(
            BetterHarnessCore.Experiment experiment) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, BetterHarnessCore.Surface> e : experiment.surfaces().entrySet()) {
            values.put(e.getKey(), e.getValue().baseValue());
        }
        return new BetterHarnessCore.Variant(
                "baseline",
                experiment.model(),
                List.of(),
                experiment.surfaces(),
                values);
    }

    /**
     * Build one variant from raw surface values. Mirrors the Python
     * port's {@code build_variant}.
     */
    public static BetterHarnessCore.Variant buildVariant(
            BetterHarnessCore.Experiment experiment,
            String label,
            Map<String, String> values) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, BetterHarnessCore.Surface> e : experiment.surfaces().entrySet()) {
            String name = e.getKey();
            String base = e.getValue().baseValue();
            if (!base.equals(values.get(name))) {
                changed.add(name);
            }
        }
        java.util.Collections.sort(changed);
        return new BetterHarnessCore.Variant(
                label,
                experiment.model(),
                List.copyOf(changed),
                experiment.surfaces(),
                values);
    }

    /**
     * Apply module-attribute overrides using Java reflection.
     * Mirrors the Python port's {@code patch_module_attrs}.
     *
     * <p>The Python port targets module attributes ({@code pkg.attr});
     * the Java port targets class static fields ({@code pkg.Class.field}).
     * The shape of the override map is the same: a fully-qualified
     * target and a string value. The value is converted to the
     * field's declared type using a best-effort coercion (string for
     * {@code String}, parsed int / long / double / boolean for
     * primitives; raw string otherwise).</p>
     */
    public static void patchModuleAttrs(Map<String, String> overrides) {
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            String target = e.getKey();
            String value = e.getValue();
            int lastDot = target.lastIndexOf('.');
            int lastColon = target.lastIndexOf(':');
            int sep = Math.max(lastDot, lastColon);
            if (sep <= 0) {
                throw new IllegalArgumentException(
                        "invalid module_attr target '" + target
                                + "'; expected class:field or class.field");
            }
            String className = target.substring(0, sep);
            String fieldName = target.substring(sep + 1);
            try {
                Class<?> cls = Class.forName(className);
                Field field = cls.getDeclaredField(fieldName);
                field.setAccessible(true);
                if (Modifier.isStatic(field.getModifiers())) {
                    field.set(null, coerce(value, field.getType()));
                } else {
                    throw new IllegalStateException(
                            "field " + target + " is not static; cannot patch");
                }
            } catch (ClassNotFoundException | NoSuchFieldException exc) {
                throw new IllegalStateException("cannot resolve " + target, exc);
            } catch (IllegalAccessException exc) {
                throw new IllegalStateException("cannot write " + target, exc);
            }
        }
    }

    /** Coerce a string value to a target Java type. */
    static Object coerce(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == long.class || type == Long.class) return Long.parseLong(value);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == double.class || type == Double.class) return Double.parseDouble(value);
        if (type == float.class || type == Float.class) return Float.parseFloat(value);
        return value;
    }

    /**
     * Temporarily replace files in the target workspace. Mirrors
     * the Python port's {@code workspace_override_context}.
     *
     * <p>Returns an {@link AutoCloseable} that restores the original
     * files when closed. The Java port uses try-with-resources
     * instead of Python's {@code with} context manager.</p>
     */
    public static WorkspaceOverride workspaceOverride(
            Path workspaceRoot,
            Map<String, String> overrides) {
        return new WorkspaceOverride(workspaceRoot, overrides);
    }

    /**
     * Java equivalent of the Python port's
     * {@code workspace_override_context}.
     */
    public static final class WorkspaceOverride implements AutoCloseable {
        private final Path root;
        private final Map<Path, String> backups = new LinkedHashMap<>();

        WorkspaceOverride(Path workspaceRoot, Map<String, String> overrides) {
            this.root = workspaceRoot;
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                Path target = root.resolve(e.getKey());
                String original = null;
                if (Files.exists(target)) {
                    try { original = Files.readString(target); }
                    catch (IOException exc) { original = null; }
                }
                backups.put(target, original);
                try {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, e.getValue());
                } catch (IOException exc) {
                    throw new RuntimeException("failed to write override " + target, exc);
                }
            }
        }

        @Override
        public void close() {
            for (Map.Entry<Path, String> e : backups.entrySet()) {
                Path target = e.getKey();
                String original = e.getValue();
                try {
                    if (original == null) {
                        Files.deleteIfExists(target);
                    } else {
                        Files.writeString(target, original,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    }
                } catch (IOException exc) {
                    // Best-effort restore.
                }
            }
        }
    }

    /**
     * Put one or more paths first on the classpath / module path.
     * Mirrors the Python port's {@code prepend_pythonpath}. The Java
     * port is a no-op stub because it does not manage a Python
     * subprocess; callers can adapt it as needed.
     */
    public static String prependModulePath(List<Path> paths, String existing) {
        List<String> parts = new ArrayList<>();
        for (Path p : paths) parts.add(p.toString());
        if (existing != null && !existing.isEmpty()) parts.add(existing);
        return String.join(java.io.File.pathSeparator, parts);
    }

    /**
     * Stub that mirrors the Python port's {@code ensure_sitecustomize}.
     * Writes a small Java launcher class that calls
     * {@link #patchFromEnv()} when the subprocess boots. The Java
     * port returns the runtime directory without writing a launcher
     * because there is no equivalent of Python's {@code sitecustomize}
     * auto-import.
     */
    public static Path ensureSiteCustomize(Path runtimeDir) {
        try {
            Files.createDirectories(runtimeDir);
        } catch (IOException exc) {
            throw new RuntimeException("cannot create runtime dir " + runtimeDir, exc);
        }
        return runtimeDir;
    }

    /**
     * Apply the variant encoded in the file pointed to by
     * {@link #VARIANT_ENV}. Mirrors the Python port's
     * {@code patch_from_env}.
     */
    public static void patchFromEnv() {
        String raw = System.getenv(VARIANT_ENV);
        if (raw == null || raw.isEmpty()) return;
        try {
            String json = Files.readString(Path.of(raw));
            BetterHarnessCore.Variant v = BetterHarnessCore.Variant.loadFromJson(json);
            patchModuleAttrs(v.attrOverrides());
        } catch (IOException exc) {
            throw new RuntimeException("cannot load variant from " + raw, exc);
        }
    }

    /** Stream of files in a directory tree (helper for variants). */
    public static Stream<Path> walkTree(Path root) {
        try {
            return Files.walk(root);
        } catch (IOException exc) {
            return Stream.empty();
        }
    }
}
