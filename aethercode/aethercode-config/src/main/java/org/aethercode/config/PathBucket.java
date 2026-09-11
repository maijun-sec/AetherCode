package org.aethercode.config;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Logical path bucket — a coarse classification of where a path lives
 * inside a project. Used for logging, metrics, and as a quick lookup shortcut
 * for permission policy decisions. The matrix in {@link PermissionMatrix}
 * still uses raw globs; this enum is for higher-level reasoning.
 *
 * <p>The detection rules are deliberately simple and conservative: when in
 * doubt, the path falls into {@link #OTHER}. Project-specific paths should
 * be classified via the matrix's path-glob directly, not via this enum.
 */
public enum PathBucket {
    /** Source code, e.g. {@code src/main/**}, {@code lib/**}, {@code app/**}. */
    SRC_MAIN,
    /** Test code, e.g. {@code src/test/**}, {@code test/**}, {@code tests/**}, {@code __tests__/**}. */
    SRC_TEST,
    /** Build output, e.g. {@code target/**}, {@code build/**}, {@code dist/**}, {@code out/**}, {@code node_modules/**}. */
    BUILD,
    /** Cache / transient, e.g. {@code .cache/**}, {@code .gradle/**}, {@code .idea/**}, {@code .vscode/**}. */
    CACHE,
    /** AetherCode internal config, e.g. {@code .aethercode/**}, {@code .aethercode/**}. */
    CONFIG,
    /** Documentation, e.g. {@code docs/**}, {@code README*}, {@code *.md}. */
    DOCS,
    /** System / outside-cwd paths, e.g. {@code /etc/**}, {@code /usr/**}, {@code $HOME/.config/**}. */
    EXTERNAL,
    /** Anything that does not match the above. */
    OTHER;

    /**
     * Classify a path. Returns {@link #OTHER} for null / empty / unparseable input.
     * The {@code projectRoot} is used to compute the relative path; pass null to
     * use the path's own first segment as the hint.
     */
    public static PathBucket classify(String path, Path projectRoot) {
        if (path == null || path.isBlank()) return OTHER;
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        // Strip leading drive letter / root prefix for matching
        String norm = p;
        if (norm.length() >= 2 && Character.isLetter(norm.charAt(0)) && norm.charAt(1) == ':') {
            norm = norm.substring(2);
        }
        if (norm.startsWith("/etc/") || norm.startsWith("/usr/") || norm.startsWith("/var/")
                || norm.startsWith("/proc/") || norm.startsWith("/sys/")) {
            return EXTERNAL;
        }
        if (norm.startsWith(".aethercode/") || norm.contains("/.aethercode/")) {
            return CONFIG;
        }
        if (norm.startsWith("src/main/") || norm.startsWith("src\\main\\")
                || norm.startsWith("lib/") || norm.startsWith("app/")) {
            return SRC_MAIN;
        }
        if (norm.startsWith("src/test/") || norm.startsWith("src\\test\\")
                || norm.startsWith("test/") || norm.startsWith("tests/")
                || norm.startsWith("__tests__/") || norm.startsWith("spec/")) {
            return SRC_TEST;
        }
        if (norm.startsWith("target/") || norm.startsWith("build/") || norm.startsWith("dist/")
                || norm.startsWith("out/") || norm.startsWith("bin/") || norm.startsWith("obj/")) {
            return BUILD;
        }
        if (norm.startsWith("node_modules/") || norm.startsWith(".gradle/")
                || norm.startsWith(".cache/") || norm.startsWith(".idea/")
                || norm.startsWith(".vscode/") || norm.startsWith(".pytest_cache/")
                || norm.startsWith(".next/") || norm.startsWith(".nuxt/")) {
            return CACHE;
        }
        if (norm.startsWith("docs/") || norm.startsWith("doc/") || norm.endsWith(".md")
                || norm.endsWith(".rst") || norm.endsWith(".adoc")) {
            return DOCS;
        }
        if (projectRoot != null) {
            try {
                Path abs = Path.of(path);
                if (!abs.isAbsolute()) abs = projectRoot.resolve(abs).toAbsolutePath();
                Path rel = projectRoot.toAbsolutePath().relativize(abs);
                if (rel.startsWith("..")) return EXTERNAL;
            } catch (RuntimeException ignore) {}
        }
        return OTHER;
    }
}
