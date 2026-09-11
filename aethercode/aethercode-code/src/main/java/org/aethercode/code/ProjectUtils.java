package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utilities for project root detection and project-specific configuration.
 *
 * <p>Java-native port of the Python {@code deepagents_code.project_utils}
 * module.</p>
 */
public final class ProjectUtils {
    private ProjectUtils() {}

    private static final Logger LOG = LoggerFactory.getLogger(ProjectUtils.class);

    /** Env-var prefix carrying server project context transport data. */
    public static final String SERVER_ENV_PREFIX = "DEEPAGENTS_CODE_SERVER_";

    /**
     * Explicit user/project path context for project-sensitive behavior.
     */
    public record ProjectContext(Path userCwd, Path projectRoot) {
        public ProjectContext {
            if (userCwd == null || !userCwd.isAbsolute()) {
                throw new IllegalArgumentException("user_cwd must be absolute, got " + userCwd);
            }
            if (projectRoot != null && !projectRoot.isAbsolute()) {
                throw new IllegalArgumentException("project_root must be absolute, got " + projectRoot);
            }
        }

        /** Build a project context from an explicit user working directory. */
        public static ProjectContext fromUserCwd(String userCwd) {
            return fromUserCwd(Path.of(userCwd));
        }

        /** Build a project context from an explicit user working directory. */
        public static ProjectContext fromUserCwd(Path userCwd) {
            Path resolved = userCwd.toAbsolutePath().normalize();
            return new ProjectContext(resolved, findProjectRoot(resolved));
        }

        /** Resolve a path relative to the explicit user working directory. */
        public Path resolveUserPath(String path) {
            return resolveUserPath(Path.of(path));
        }

        /** Resolve a path relative to the explicit user working directory. */
        public Path resolveUserPath(Path path) {
            Path candidate = path.toAbsolutePath();
            if (candidate.startsWith(userCwd)) {
                return candidate.normalize();
            }
            return userCwd.resolve(candidate).normalize();
        }

        /** Return project-level {@code AGENTS.md} files for this context. */
        public List<Path> projectAgentMdPaths() {
            if (projectRoot == null) {
                return List.of();
            }
            return findProjectAgentMd(projectRoot);
        }

        /** Return the project {@code .deepagents/skills} directory, if any. */
        public Path projectSkillsDir() {
            return projectRoot == null ? null : projectRoot.resolve(".deepagents/skills");
        }

        /** Return the project {@code .deepagents/agents} directory, if any. */
        public Path projectAgentsDir() {
            return projectRoot == null ? null : projectRoot.resolve(".deepagents/agents");
        }

        /** Return the project {@code .agents/skills} directory, if any. */
        public Path projectAgentSkillsDir() {
            return projectRoot == null ? null : projectRoot.resolve(".agents/skills");
        }
    }

    /**
     * Read the server project context from environment transport data.
     *
     * @return reconstructed project context, or {@code null} if no server
     *         context exists
     */
    public static ProjectContext getServerProjectContext(Map<String, String> env) {
        Map<String, String> environment = env == null ? System.getenv() : env;
        String rawCwd = environment.get(SERVER_ENV_PREFIX + "CWD");
        if (rawCwd == null || rawCwd.isEmpty()) {
            return null;
        }
        try {
            Path userCwd = Path.of(rawCwd).toAbsolutePath().normalize();
            String rawProjectRoot = environment.get(SERVER_ENV_PREFIX + "PROJECT_ROOT");
            Path projectRoot = (rawProjectRoot != null && !rawProjectRoot.isEmpty())
                    ? Path.of(rawProjectRoot).toAbsolutePath().normalize()
                    : findProjectRoot(userCwd);
            return new ProjectContext(userCwd, projectRoot);
        } catch (Exception e) {
            LOG.warn("Could not resolve server project context from CWD={}", rawCwd, e);
            return null;
        }
    }

    /**
     * Find the project root by looking for git metadata.
     */
    public static Path findProjectRoot(Path startPath) {
        Path current = (startPath == null ? Path.of("") : startPath).toAbsolutePath().normalize();
        return Git.findGitRoot(current);
    }

    /**
     * Find project-specific {@code AGENTS.md} files. Returns the existing
     * entries from {@code .deepagents/AGENTS.md} and {@code AGENTS.md}, with
     * in-tree symlinks pre-resolved to their targets.
     */
    public static List<Path> findProjectAgentMd(Path projectRoot) {
        if (projectRoot == null) return List.of();
        Path resolvedRoot = projectRoot.toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                resolvedRoot.resolve(".deepagents/AGENTS.md"),
                resolvedRoot.resolve("AGENTS.md"));
        List<Path> paths = new ArrayList<>();
        for (Path candidate : candidates) {
            Path resolved;
            try {
                resolved = candidate.toRealPath();
            } catch (NoSuchFileException e) {
                continue;
            } catch (IOException | RuntimeException e) {
                LOG.warn("Skipping AGENTS.md candidate {}: {}", candidate, e.toString());
                continue;
            }
            if (!resolved.startsWith(resolvedRoot)) {
                LOG.warn("Skipping AGENTS.md symlink {}: target {} is outside the project root {}",
                        candidate, resolved, resolvedRoot);
                continue;
            }
            if (candidate.toAbsolutePath().equals(resolved)) {
                paths.add(candidate);
            } else {
                paths.add(resolved);
            }
        }
        return paths;
    }
}
