package org.aethercode.workflows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Phase 2.1 / T-2-08 (design.md §3.6): resolves the user-level
 * and project-level workflow directories and the per-workflow
 * YAML file path inside each.
 *
 * <p>Resolution rules (matching the spec's §11.3 + design.md §3.6):
 * <ul>
 *   <li>User workflows live at {@code <userHome>/.aethercode/workflows/<name>.yaml}.</li>
 *   <li>Project workflows live at {@code <cwd>/.aethercode/workflows/<name>.yaml}.</li>
 *   <li>For {@link #list(Path, Path)} the project layer takes precedence: any
 *       workflow that exists in the project directory shadows the same name in
 *       the user directory (so a project can override a user-shipped workflow
 *       without forking it).</li>
 *   <li>A null {@code userHome} is treated as an empty user layer (sandbox /
 *       test fixture). A null {@code cwd} throws — the project layer is
 *       always required because the project dir is also the "where do I
 *       actually run" anchor.</li>
 * </ul>
 *
 * <p>Two API shapes are exposed:
 * <ul>
 *   <li>Static helpers ({@link #userDir(Path)}, {@link #projectDir(Path)},
 *       {@link #userYaml(Path, String)}, {@link #projectYaml(Path, String)},
 *       {@link #resolve(Path, Path, String)}, {@link #list(Path, Path)})
 *       — convenient for one-off calls in the RPC handlers and the CLI.</li>
 *   <li>An instance API ({@link #WorkflowPaths(Path, Path)}) that captures
 *       {@code userHome} / {@code cwd} once and exposes
 *       {@link #userDir()} / {@link #projectDir()} / {@link #resolveFile(String)} /
 *       {@link #listAll()} — convenient for the
 *       {@code WorkflowEngine} which holds a single resolver across
 *       multiple operations in one run.</li>
 * </ul>
 *
 * <p>Both shapes share the same name-validation rules
 * ({@link #assertSafeName(String)}) so a path-traversal attempt is
 * caught the same way regardless of which entry point was used.
 */
public final class WorkflowPaths {

    /** Conventional user-layer directory name, under {@code userHome}. */
    public static final String USER_DIR_NAME = ".aethercode";

    /** Conventional user-layer workflows sub-directory. */
    public static final String WORKFLOWS_SUBDIR = "workflows";

    /** File extension for workflow YAML files. */
    public static final String WORKFLOW_EXTENSION = ".yaml";

    private final Path userHome;
    private final Path cwd;

    /** Build a resolver anchored at the given user-home + cwd. Either
     *  can be null; a null user-home means the user layer is empty
     *  (sandbox / no project override). A null cwd throws. */
    public WorkflowPaths(Path userHome, Path cwd) {
        if (cwd == null) {
            throw new IllegalArgumentException("cwd is required");
        }
        this.userHome = userHome;
        this.cwd = cwd;
    }

    /** Default user home, resolved from the JVM's {@code user.home}
     *  property. Returns null when the property is unset / blank
     *  (sandbox). */
    public static Path defaultUserHome() {
        String h = System.getProperty("user.home");
        if (h == null || h.isBlank()) return null;
        return Path.of(h);
    }

    /** Default project cwd, resolved from the JVM's {@code user.dir}
     *  property. Always non-null. */
    public static Path defaultCwd() {
        String d = System.getProperty("user.dir");
        return (d == null || d.isBlank()) ? Path.of(".").toAbsolutePath() : Path.of(d);
    }

    /** Convenience: build a resolver from {@code $user.home} and
     *  {@code $user.dir}. */
    public static WorkflowPaths current() {
        return new WorkflowPaths(defaultUserHome(), defaultCwd());
    }

    // ----- static helpers ---------------------------------------------

    /** Static helper: the user-layer workflows directory
     *  ({@code <userHome>/.aethercode/workflows}). */
    public static Path userDir(Path userHome) {
        if (userHome == null) return null;
        return userHome.resolve(USER_DIR_NAME).resolve(WORKFLOWS_SUBDIR);
    }

    /** Static helper: the project-layer workflows directory
     *  ({@code <cwd>/.aethercode/workflows}). */
    public static Path projectDir(Path cwd) {
        Objects.requireNonNull(cwd, "cwd");
        return cwd.resolve(USER_DIR_NAME).resolve(WORKFLOWS_SUBDIR);
    }

    /** Static helper: the user-layer YAML for a workflow. Returns
     *  null when {@code userHome} is null. */
    public static Path userYaml(Path userHome, String name) {
        Path dir = userDir(userHome);
        if (dir == null) return null;
        return dir.resolve(assertSafeName(name) + WORKFLOW_EXTENSION);
    }

    /** Static helper: the project-layer YAML for a workflow. */
    public static Path projectYaml(Path cwd, String name) {
        return projectDir(cwd).resolve(assertSafeName(name) + WORKFLOW_EXTENSION);
    }

    /**
     * Static helper: resolve a workflow by name. Project layer wins
     * over the user layer. Returns null if the workflow exists in
     * neither layer.
     */
    public static Path resolve(Path userHome, Path cwd, String name) {
        assertSafeName(name);
        Path project = projectYaml(cwd, name);
        if (Files.exists(project)) return project;
        Path user = userYaml(userHome, name);
        if (user != null && Files.exists(user)) return user;
        return null;
    }

    /**
     * Static helper: list every workflow visible from {@code cwd},
     * merging the user and project layers with project precedence.
     * The result is sorted by workflow name and contains no duplicates.
     * Names that fail {@link #assertSafeName} (a corrupt file on disk)
     * are silently skipped — the listing path is best-effort and
     * must not throw on filesystem weirdness.
     */
    public static List<String> list(Path userHome, Path cwd) {
        Set<String> names = new LinkedHashSet<>();
        collectLayer(userDir(userHome), names);
        collectLayer(projectDir(cwd), names);
        List<String> out = new ArrayList<>(names);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * Where a new workflow should be written by default. Currently
     * always the project layer (the design.md §3.6 spec says
     * "project-local takes precedence"; the implicit corollary is
     * "new workflows land in the project layer first"). Falls back
     * to the user layer if the project directory cannot be created
     * (read-only fs, sandbox).
     */
    public static Path defaultWriteTarget(Path userHome, Path cwd, String name) {
        Path project = projectYaml(cwd, name);
        Path projectDir = project.getParent();
        if (projectDir != null && ensureWritable(projectDir)) {
            return project;
        }
        Path user = userYaml(userHome, name);
        if (user == null) {
            return project;
        }
        Path userDir = user.getParent();
        if (userDir != null) ensureWritable(userDir);
        return user;
    }

    /**
     * Validates a workflow name. Allowed: ASCII letters, digits,
     * dot, dash, underscore. Must be 1..120 chars. Rejects anything
     * that could let the resolved path escape its parent directory
     * (e.g. {@code ..}, {@code /}, {@code \}, NUL). The check is
     * intentionally narrow — a workflow name is a tag, not a
     * free-form path segment.
     */
    public static String assertSafeName(String name) {
        if (name == null) throw new IllegalArgumentException("workflow name is null");
        if (name.isBlank()) throw new IllegalArgumentException("workflow name is blank");
        if (name.length() > 120) {
            throw new IllegalArgumentException("workflow name too long: " + name.length());
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException(
                        "workflow name contains illegal character at index " + i + ": '" + c + "'");
            }
        }
        if (name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("workflow name cannot be '.' or '..'");
        }
        return name;
    }

    // ----- instance API ------------------------------------------------

    /** Instance form: the user-layer workflows directory. */
    public Path userDir() { return userDir(userHome); }

    /** Instance form: the project-layer workflows directory. */
    public Path projectDir() { return projectDir(cwd); }

    /**
     * Find the on-disk location to load a named workflow from. The
     * order is project → user → null (caller falls back to the
     * bundled resources). The name is validated before the lookup
     * so a path-traversal attempt is caught at the API boundary.
     */
    public Path resolveFile(String name) {
        try {
            assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            return null;
        }
        Path p = projectDir();
        if (p != null) {
            Path candidate = p.resolve(name + WORKFLOW_EXTENSION);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        Path u = userDir();
        if (u != null) {
            Path candidate = u.resolve(name + WORKFLOW_EXTENSION);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * List every workflow visible to this resolver's cwd, project
     * first. The result entries include the on-disk path + source
     * (project / user / bundled) so callers can render a
     * "from project" badge without re-running the lookup.
     */
    public List<Entry> listAll() {
        java.util.Map<String, Entry> byName = new java.util.LinkedHashMap<>();
        addAllFrom(byName, projectDir(), Source.PROJECT);
        addAllFrom(byName, userDir(), Source.USER);
        List<Entry> out = new ArrayList<>(byName.values());
        out.sort(Comparator.comparing(Entry::name));
        return out;
    }

    private static void addAllFrom(java.util.Map<String, Entry> into,
                                   Path dir, Source source) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String fileName = p.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(WORKFLOW_EXTENSION)) continue;
                String name = fileName.substring(0, fileName.length() - WORKFLOW_EXTENSION.length());
                if (!isValidName(name)) continue;
                // First write wins; project dir is walked first so
                // the user copy never overrides it.
                into.putIfAbsent(name, new Entry(name, p, source));
            }
        } catch (IOException ignored) {
            // best-effort: a permission error on a layer dir must
            // not break listing
        }
    }

    /** True when {@code name} is a non-empty string made of the
     *  same character set {@link #assertSafeName} accepts. Used
     *  for the listing path where a name is read off disk and we
     *  don't want a corrupt filename to crash the whole walk. */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) return false;
        }
        return !name.equals(".") && !name.equals("..");
    }

    /** One visible workflow. */
    public record Entry(String name, Path file, Source source) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(source, "source");
        }
    }

    /** Where a workflow came from on disk. */
    public enum Source {
        PROJECT, USER, BUNDLED
    }

    // ----- internal helpers -------------------------------------------

    /** Best-effort mkdir for the parent directory of a target file.
     *  Returns true if the directory exists (and is writable) afterwards. */
    private static boolean ensureWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.isWritable(dir);
        } catch (IOException e) {
            return false;
        }
    }

    /** Walks one layer directory and adds every {@code *.yaml} basename. */
    private static void collectLayer(Path layerDir, Set<String> sink) {
        if (layerDir == null || !Files.isDirectory(layerDir)) return;
        try (Stream<Path> s = Files.list(layerDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(WORKFLOW_EXTENSION))
                    .forEach(p -> {
                        String fn = p.getFileName().toString();
                        String base = fn.substring(0, fn.length() - WORKFLOW_EXTENSION.length());
                        if (isValidName(base)) sink.add(base);
                    });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
