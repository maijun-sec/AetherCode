package org.aethercode.core.middleware;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Path validation for filesystem tools.
 *
 * <p>Java-native port of
 * {@code deepagents.backends.utils.validate_path}. Mirrors the
 * Python port's three rules:</p>
 *
 * <ol>
 *   <li>Reject paths whose segments include {@code ..} (path
 *       traversal) or whose first character is {@code ~} (tilde).</li>
 *   <li>Reject Windows absolute paths ({@code C:}, {@code D:}, etc.).</li>
 *   <li>Normalize backslashes to forward slashes and ensure the
 *       result starts with {@code /}.</li>
 * </ol>
 *
 * <p>Throwing methods raise {@link FilesystemPathTraversalException}
 * with the user-facing message the Python port formats
 * ({@code "Error: Path traversal not allowed: <path>"} and
 * {@code "Error: Windows absolute paths are not supported: <path>. ..."}).</p>
 */
public final class FilesystemPathValidator {
    private FilesystemPathValidator() {}

    /** Matches a Windows drive prefix like {@code C:} or {@code D:/}. */
    private static final Pattern WINDOWS_DRIVE_RE = Pattern.compile("^[a-zA-Z]:");

    /**
     * Normalize a virtual-path argument into the form the model
     * expects: forward slashes, leading {@code /}, no {@code .}/{@code ..}
     * segments, no leading tilde, no Windows drive prefix.
     *
     * @param path the raw path the model supplied
     * @return the normalized path
     * @throws FilesystemPathTraversalException on any of the rules above
     */
    public static String validateAndNormalize(String path) {
        return validateAndNormalize(path, null);
    }

    /**
     * Variant with an optional list of allowed prefixes. The
     * normalized path must start with one of them.
     *
     * @param path the raw path
     * @param allowedPrefixes optional allow-list (may be null/empty for "no constraint")
     * @return the normalized path
     * @throws FilesystemPathTraversalException on rule violation
     */
    public static String validateAndNormalize(String path, List<String> allowedPrefixes) {
        if (path == null) {
            // Optional path argument (e.g. grep without a path filter):
            // skip validation and let the backend's own default apply.
            return null;
        }
        if (path.isEmpty()) {
            throw new FilesystemPathTraversalException("Path traversal not allowed: (empty)");
        }

        // Reject tilde prefix.
        if (path.startsWith("~")) {
            throw new FilesystemPathTraversalException(
                    "Error: Path traversal not allowed: " + path);
        }

        // Reject Windows absolute paths.
        if (WINDOWS_DRIVE_RE.matcher(path).find()) {
            throw new FilesystemPathTraversalException(
                    "Error: Windows absolute paths are not supported: " + path
                            + ". Please use virtual paths starting with / (e.g., /workspace/file.txt)");
        }

        // Reject `..` as a path segment (not as a substring of a filename).
        String[] segments = path.split("[/\\\\]");
        for (String seg : segments) {
            if ("..".equals(seg)) {
                throw new FilesystemPathTraversalException(
                        "Error: Path traversal not allowed: " + path);
            }
        }

        // Normalize: forward slashes, collapse repeated slashes, ensure
        // a leading `/`.
        String normalized = path.replace('\\', '/').replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        // Defense in depth: re-check the normalized form.
        for (String seg : normalized.split("/")) {
            if ("..".equals(seg)) {
                throw new FilesystemPathTraversalException(
                        "Error: Path traversal detected after normalization: " + path + " -> " + normalized);
            }
        }

        // Optional prefix allow-list.
        if (allowedPrefixes != null && !allowedPrefixes.isEmpty()) {
            boolean ok = false;
            for (String p : allowedPrefixes) {
                if (normalized.startsWith(p)) { ok = true; break; }
            }
            if (!ok) {
                throw new FilesystemPathTraversalException(
                        "Error: Path must start with one of " + allowedPrefixes + ": " + path);
            }
        }
        return normalized;
    }
}
