package org.aethercode.core.middleware;

import java.util.List;
import java.util.Set;

/**
 * A single filesystem permission rule.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.filesystem.FilesystemPermission} record.
 * Each rule is a (mode, operations, paths) triple: the {@code mode}
 * decides how the rule resolves (allow / deny / interrupt), the
 * {@code operations} set narrows which file ops it covers, and the
 * {@code paths} list is a list of glob patterns matched against the
 * tool call's path argument.</p>
 *
 * <p>Path validation (mirrors Python's {@code __post_init__}):</p>
 * <ul>
 *   <li>Each path must start with {@code "/"}.</li>
 *   <li>Backslashes are normalized to forward slashes before traversal
 *       checks, so a Windows-style {@code \..\ } is still detected.</li>
 *   <li>No path component may be {@code ".."} (raises
 *       {@link IllegalArgumentException}).</li>
 *   <li>No path component may be {@code "~"} (raises
 *       {@link UnsupportedOperationException}, matching Python's
 *       {@code NotImplementedError}).</li>
 * </ul>
 */
public record FilesystemPermission(Mode mode,
                                   Set<FilesystemOperation> operations,
                                   List<String> paths) {

    public enum Mode { ALLOW, DENY, INTERRUPT }

    /** Convenience: operations + paths (mode defaults to ALLOW). */
    public FilesystemPermission(Set<FilesystemOperation> operations, List<String> paths) {
        this(Mode.ALLOW, operations, paths);
    }

    public FilesystemPermission {
        // Validate every path. Mirrors Python's
        // FilesystemPermission.__post_init__ (filesystem.py):
        //   parts = PurePosixPath(path.replace("\\", "/")).parts
        // We use a simple slash split (Paths.get rejects `*` in glob
        // patterns, but Python's PurePosixPath accepts them).
        for (String path : paths) {
            if (path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException(
                        "Permission path must start with '/': "
                                + (path == null ? "<null>" : repr(path)));
            }
            String normalized = path.replace("\\", "/");
            for (String part : normalized.split("/")) {
                if (part.isEmpty()) continue;
                if ("..".equals(part)) {
                    throw new IllegalArgumentException(
                            "Permission path must not contain '..': " + repr(path));
                }
                if ("~".equals(part)) {
                    throw new UnsupportedOperationException(
                            "Permission path must not contain '~': " + repr(path));
                }
            }
        }
    }

    private static String repr(String s) {
        return "'" + s + "'";
    }
}
