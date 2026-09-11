package org.aethercode.core.fs;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * project-relative path utilities. The TUI and tools all display
 * paths in the form {@code src/foo/Bar.java} rather than the absolute path,
 * so a single helper centralises the "is this inside cwd?" / "make it
 * relative" / "validate" logic.
 */
public final class PathNormalizer {

    private PathNormalizer() {}

    /**
     * normalise a user-supplied path. If the input is absolute,
     * resolve it against the file system. If relative, treat it as
     * relative to {@code cwd}. Returns a canonicalised absolute path.
     */
    public static Path normalize(String raw, Path cwd) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(cwd, "cwd");
        Path base = cwd.toAbsolutePath().normalize();
        Path p;
        try {
            p = Paths.get(raw);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid path: " + raw, e);
        }
        if (p.isAbsolute()) return p.normalize();
        return base.resolve(p).normalize();
    }

    /**
     * make {@code target} relative to {@code cwd} if possible. If
     * the target is on a different drive (Windows) or escapes the cwd,
     * returns the absolute path unchanged.
     */
    public static String relativize(Path target, Path cwd) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cwd, "cwd");
        Path absTarget = target.isAbsolute() ? target.normalize() : cwd.toAbsolutePath().normalize().resolve(target).normalize();
        Path absCwd = cwd.toAbsolutePath().normalize();
        if (!absTarget.startsWith(absCwd)) return absTarget.toString();
        Path rel = absCwd.relativize(absTarget);
        // Use forward slashes for display regardless of OS.
        return rel.toString().replace('\\', '/');
    }

    /**
     * true iff {@code candidate} is inside {@code cwd} (or equal
     * to it). Symlinks are <b>not</b> resolved — this is a lexical check.
     */
    public static boolean isInside(Path candidate, Path cwd) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(cwd, "cwd");
        Path a = candidate.isAbsolute() ? candidate.normalize() : cwd.toAbsolutePath().normalize().resolve(candidate).normalize();
        Path b = cwd.toAbsolutePath().normalize();
        return a.startsWith(b);
    }

    /**
     * ensure the path stays inside {@code cwd}. Throws
     * {@link PathEscapeException} if any {@code ..} segment would resolve
     * outside the cwd. Returns the absolute normalised path.
     */
    public static Path safeResolve(String raw, Path cwd) {
        Path n = normalize(raw, cwd);
        if (!isInside(n, cwd)) {
            throw new PathEscapeException("path escapes cwd: " + raw + " -> " + n);
        }
        return n;
    }

    /**
     * split a POSIX-style or Windows-style path into its segments.
     * Always uses {@code /} as the separator on the way out.
     */
    public static List<String> segments(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        String norm = raw.replace('\\', '/');
        if (norm.startsWith("/")) norm = norm.substring(1);
        if (norm.endsWith("/") && norm.length() > 1) norm = norm.substring(0, norm.length() - 1);
        if (norm.isEmpty()) return List.of();
        return new ArrayList<>(List.of(norm.split("/")));
    }

    /**
     * join segments with {@code /} (forward slashes, OS-independent).
     * Empty segments are dropped; leading/trailing slashes are stripped.
     */
    public static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            for (String s : p.replace('\\', '/').split("/")) {
                if (!s.isEmpty()) out.add(s);
            }
        }
        return String.join("/", out);
    }

    /** true if {@code child} is a descendant of {@code parent}. */
    public static boolean isDescendant(Path child, Path parent) {
        if (child == null || parent == null) return false;
        Path a = child.toAbsolutePath().normalize();
        Path b = parent.toAbsolutePath().normalize();
        return a.startsWith(b) && !a.equals(b);
    }
}
