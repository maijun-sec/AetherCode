package org.aethercode.core.middleware;

import org.aethercode.core.fs.backend.BackendUtils;
import org.aethercode.core.fs.backend.GlobMatcher;
import org.aethercode.core.fs.backend.LsResult;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static helpers for evaluating {@link FilesystemPermission} rules
 * against a (path, operation) pair.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.filesystem._check_fs_permission}
 * function and its mode-resolution rules. The port mirrors the
 * Python semantics:</p>
 *
 * <ul>
 *   <li>The first rule that matches a path wins; rules are evaluated
 *       in declaration order.</li>
 *   <li>For an {@code ALLOW} rule the call proceeds; for
 *       {@code DENY} the call is rejected; for {@code INTERRUPT} the
 *       call surfaces a {@code HumanInTheLoop} hook.</li>
 *   <li>If no rule matches, the call is {@code ALLOW} (the default
 *       is permissive &mdash; same as the Python port).</li>
 * </ul>
 */
public final class FilesystemPermissionChecker {
    private FilesystemPermissionChecker() {}

    /** The verdict of a permission check. */
    public enum Verdict { ALLOW, DENY, INTERRUPT }

    /**
     * Decide whether {@code path} is permitted under {@code rules} for
     * the supplied {@code operation}. Returns the first matching
     * rule's mode, or {@link Verdict#ALLOW} if no rule matches.
     */
    public static Verdict check(List<FilesystemPermission> rules, FilesystemOperation operation, String path) {
        if (rules == null || rules.isEmpty()) return Verdict.ALLOW;
        for (FilesystemPermission rule : rules) {
            if (!rule.operations().contains(operation)) continue;
            if (matchesAnyPattern(rule.paths(), path)) {
                return switch (rule.mode()) {
                    case ALLOW -> Verdict.ALLOW;
                    case DENY -> Verdict.DENY;
                    case INTERRUPT -> Verdict.INTERRUPT;
                };
            }
        }
        return Verdict.ALLOW;
    }

    private static boolean matchesAnyPattern(List<String> patterns, String path) {
        for (String pattern : patterns) {
            if (matchesPattern(pattern, path)) return true;
        }
        return false;
    }

    /**
     * Filter a list of paths, removing those denied by a rule for the
     * given operation. Interrupt-mode rules are not considered here
     * (they are handled at the HumanInTheLoop stage). Mirrors the
     * Python port's {@code _filter_paths_by_permission}.
     */
    public static List<String> filterPaths(List<FilesystemPermission> rules,
                                           FilesystemOperation operation,
                                           List<String> paths) {
        if (paths == null || paths.isEmpty()) return List.of();
        if (rules == null || rules.isEmpty()) return List.copyOf(paths);
        java.util.List<String> kept = new java.util.ArrayList<>(paths.size());
        for (String p : paths) {
            Verdict v = check(rules, operation, p);
            // Keep the path unless it is explicitly DENY.
            if (v != Verdict.DENY) kept.add(p);
        }
        return List.copyOf(kept);
    }

    /**
     * Whether {@code path} matches {@code pattern}. The pattern is
     * treated as a path-segment glob; exact match and subtree match
     * are both considered matches.
     *
     * <p>Patterns and paths are absolute (start with {@code /});
     * relative paths do not match. This matches the Python port's
     * requirement that {@code _check_fs_permission} operates on
     * absolute paths, so a sandbox backend that returns relative
     * match paths does not silently bypass deny rules.</p>
     */
    public static boolean matchesPattern(String pattern, String path) {
        if (pattern == null || path == null) return false;
        if (!pattern.startsWith("/") || !path.startsWith("/")) return false;
        // Exact match.
        if (pattern.equals(path)) return true;
        // Subtree match: `pattern` is a prefix of `path` followed by
        // a path separator.
        String p = pattern.endsWith("/") ? pattern : pattern + "/";
        if (path.startsWith(p)) return true;
        // Glob match (in case the pattern contains wildcards).
        try {
            return GlobMatcher.compile(pattern).matches(path);
        } catch (RuntimeException exc) {
            return false;
        }
    }

    // -----------------------------------------------------------------
    //  Recursive-delete permission gating
    //
    //  Mirrors the Python port's _wildcard_delete_overlap +
    //  _find_delete_deny_patterns + _find_delete_deny_patterns_for_leaf
    //  + _delete_target_may_have_descendants. A recursive delete removes
    //  `target` AND all its descendants, so a sibling deny pattern that
    //  could match a descendant must still block the delete.
    // -----------------------------------------------------------------

    /**
     * Whether a wildcard {@code pattern} could match anything inside
     * the subtree rooted at {@code target}.
     *
     * <p>Mirrors the Python port's
     * {@code _wildcard_delete_overlap}. A deny on {@code /work/*} when
     * deleting {@code /work/app/child} blocks the delete (the glob
     * could match {@code /work/app}, mutating its contents); a deny
     * on {@code /work/*.log} when deleting {@code /work/notes.txt}
     * does not block (the glob can never match anything inside
     * {@code /work/notes.txt}).</p>
     *
     * @param pattern the original glob pattern (e.g. {@code /work/*.log})
     * @param anchor the longest wildcard-free prefix of {@code pattern}
     *               (see {@link BackendUtils#globAnchor(String)})
     * @param target the absolute path being recursively deleted
     */
    public static boolean wildcardDeleteOverlap(String pattern, String anchor, String target) {
        if (pattern == null || target == null) return false;
        // Root anchor ("/**/x"): pattern can match anywhere, block all.
        if ("/".equals(anchor)) return true;
        // Target directly matches the glob: block.
        try {
            if (GlobMatcher.compile(pattern).matches(target)) return true;
        } catch (RuntimeException exc) {
            // fall through to anchor check
        }
        // Anchor is inside the delete subtree: recursive delete would
        // remove matching descendants — block.
        if (isPathRelative(anchor, target)) return true;
        // Target is below the anchor: safe to allow ONLY when the
        // pattern suffix is a single, non-** component AND no
        // ancestor of the target matches the glob. Patterns with
        // directory wildcards ("/work/*/secrets") or ** segments
        // could match descendants of the target, so fail closed.
        if (!isPathRelative(target, anchor)) return false;
        List<String> anchorParts = pathParts(anchor);
        List<String> patternParts = pathParts(pattern);
        if (anchorParts.size() > patternParts.size()) return true;  // malformed pattern
        List<String> suffix = patternParts.subList(anchorParts.size(), patternParts.size());
        if (suffix.size() != 1 || suffix.get(0).contains("**")) return true;
        // Check whether any ancestor of the target (between anchor
        // and target) matches the glob.
        List<String> targetParts = pathParts(target);
        GlobMatcher matcher;
        try {
            matcher = GlobMatcher.compile(pattern);
        } catch (RuntimeException exc) {
            return true;
        }
        for (int depth = anchorParts.size(); depth < targetParts.size(); depth++) {
            String ancestor = "/" + String.join("/", targetParts.subList(0, depth));
            if (matcher.matches(ancestor)) return true;
        }
        return false;
    }

    /**
     * Resolve delete permission for a confirmed plain file: first
     * matching rule wins. Mirrors {@code _find_delete_deny_patterns_for_leaf}.
     */
    public static List<String> findDeleteDenyPatternsForLeaf(
            List<FilesystemPermission> rules, String target) {
        if (rules == null || rules.isEmpty()) return List.of();
        for (FilesystemPermission rule : rules) {
            if (!rule.operations().contains(FilesystemOperation.WRITE)) continue;
            List<String> matched = new ArrayList<>();
            for (String pattern : rule.paths()) {
                if (matchesPattern(pattern, target)) matched.add(pattern);
            }
            if (matched.isEmpty()) continue;
            if (rule.mode() == FilesystemPermission.Mode.DENY) return matched;
            return List.of();
        }
        return List.of();
    }

    /**
     * Return deny-write patterns that block deleting {@code target}.
     * Mirrors the Python port's
     * {@code _find_delete_deny_patterns}.
     *
     * <p>When {@code hasDescendants} is {@code true} the check is
     * permissive on rule order: a deny anywhere that could match
     * {@code target} or any descendant blocks the operation. When
     * {@code false} (confirmed plain file), the first matching rule
     * wins (same as the read/write/edit gate).</p>
     */
    public static List<String> findDeleteDenyPatterns(
            List<FilesystemPermission> rules,
            String target,
            boolean hasDescendants) {
        if (!hasDescendants) {
            return findDeleteDenyPatternsForLeaf(rules, target);
        }
        if (rules == null || rules.isEmpty()) return List.of();
        List<String> denying = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (FilesystemPermission rule : rules) {
            if (rule.mode() != FilesystemPermission.Mode.DENY) continue;
            if (!rule.operations().contains(FilesystemOperation.WRITE)) continue;
            for (String pattern : rule.paths()) {
                if (!seen.add(pattern)) continue;
                String anchor = BackendUtils.globAnchor(pattern);
                boolean overlaps;
                if (hasWildcards(pattern)) {
                    overlaps = wildcardDeleteOverlap(pattern, anchor, target);
                } else {
                    // Literal pattern: subtree-overlap check.
                    overlaps = BackendUtils.pathsOverlap(target, anchor);
                }
                if (overlaps) denying.add(pattern);
            }
        }
        return denying;
    }

    /**
     * Whether {@code target} may have entries nested under it. Falls
     * back to the conservative recursive permission check when no
     * permission rules are configured or the backend doesn't
     * implement {@code ls}. Mirrors the Python port's
     * {@code _delete_target_may_have_descendants}.
     */
    public static boolean deleteTargetMayHaveDescendants(
            org.aethercode.core.fs.backend.BackendProtocol backend,
            String target,
            boolean permissionsConfigured) {
        if (!permissionsConfigured) return false;
        if (backend == null) return true;
        LsResult lsResult;
        try {
            lsResult = backend.ls(target);
        } catch (RuntimeException exc) {
            // UnsupportedOperationException / NotImplementedError
            return true;
        }
        if (lsResult.error().isPresent()) {
            String err = lsResult.error().get();
            return !err.contains("not_a_directory");
        }
        if (lsResult.entries().isPresent() && !lsResult.entries().get().isEmpty()) return true;
        // Ambiguous empty result — fall back to parent listing.
        try {
            String parent = parentPath(target);
            LsResult parentResult = backend.ls(parent);
            return leafFromParentListing(parentResult, target);
        } catch (RuntimeException exc) {
            return true;
        }
    }

    /**
     * Resolve the ambiguous "empty {@code ls(target)}, no error" case.
     * Mirrors the Python port's {@code _leaf_from_parent_listing}.
     */
    static boolean leafFromParentListing(LsResult parentListing, String target) {
        if (parentListing == null || parentListing.error().isPresent()) return true;
        List<org.aethercode.core.fs.backend.FileInfo> entries = parentListing.entries().orElse(null);
        if (entries == null) return true;
        String targetNorm = stripTrailingSlash(target);
        boolean anyMatch = false;
        for (var entry : entries) {
            if (entry == null) continue;
            String entryPath = entry.path();
            if (entryPath == null) continue;
            if (stripTrailingSlash(entryPath).equals(targetNorm)) {
                anyMatch = true;
                if (entry.isDir()) return true;
            }
        }
        return !anyMatch;  // not present in parent listing → conservative yes
    }

    // -----------------------------------------------------------------
    //  Path helpers (mirrors Python's pathlib.PurePosixPath.parts /
    //  is_relative_to)
    // -----------------------------------------------------------------

    static List<String> pathParts(String path) {
        if (path == null) return List.of();
        String posix = path.replace('\\', '/');
        if (posix.equals("/")) return List.of();
        String[] segs = posix.split("/");
        List<String> out = new ArrayList<>(segs.length);
        for (String s : segs) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static boolean isPathRelative(String child, String parent) {
        if (child == null || parent == null) return false;
        List<String> c = pathParts(child);
        List<String> p = pathParts(parent);
        if (c.size() < p.size()) return false;
        for (int i = 0; i < p.size(); i++) {
            if (!c.get(i).equals(p.get(i))) return false;
        }
        return true;
    }

    static String parentPath(String path) {
        if (path == null || path.equals("/")) return "/";
        String posix = path.replace('\\', '/');
        int last = posix.lastIndexOf('/');
        if (last < 0) return "/";
        if (last == 0) return "/";
        return posix.substring(0, last);
    }

    static String stripTrailingSlash(String path) {
        if (path == null) return null;
        if (path.length() > 1 && path.endsWith("/")) return path.substring(0, path.length() - 1);
        return path;
    }

    static boolean hasWildcards(String pattern) {
        if (pattern == null) return false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{' || c == ']') return true;
        }
        return false;
    }
}
