package org.aethercode.core.middleware;

import org.aethercode.core.fs.backend.BackendUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Glue between {@link FilesystemPermission} rules and a hypothetical
 * {@code HumanInTheLoopMiddleware} interrupt config.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware._fs_interrupt}. The
 * {@code FilesystemMiddleware} itself doesn't know about HITL &mdash;
 * it only enforces deny rules and filters denied results. The
 * graph-assembly code in {@code deepagents.graph} calls
 * {@link #buildInterruptOnFromPermissions} to turn the filesystem
 * permissions into an {@code interrupt_on} mapping for HITL, using a
 * {@code when} predicate that decides per call whether the access
 * intersects an interrupt-mode rule.</p>
 *
 * <p>The Java port uses a {@link Predicate}&lt;{@link ToolCall}&gt;
 * for the {@code when} hook. Callers wire it up to whatever HITL
 * middleware they use.</p>
 */
public final class FilesystemInterruptGlue {
    private FilesystemInterruptGlue() {}

    /** Tool-call argument shape, normalized to a map. */
    public record ToolCall(String name, Map<String, Object> args) {
        public ToolCall {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    /** Interrupt-on config emitted by {@link #buildInterruptOnFromPermissions}. */
    public record InterruptOnConfig(
            List<String> allowedDecisions,
            Predicate<ToolCall> when) {
        public InterruptOnConfig {
            allowedDecisions = allowedDecisions == null ? List.of() : List.copyOf(allowedDecisions);
        }
    }

    /**
     * Scope of a filesystem tool's path argument. Mirrors the Python
     * port's {@code ToolScope} literal type.
     */
    public enum ToolScope { EXACT, BULK }

    /**
     * Map of filesystem tool name &rarr; (operation, path-arg name,
     * scope, pattern-arg name). The pattern-arg name is set only for
     * {@code glob}, whose {@code pattern} argument can itself redirect
     * the search root (an absolute pattern ignores the call's
     * {@code path}).
     */
    public record ToolPathArg(
            FilesystemOperation operation,
            String pathArgName,
            ToolScope scope,
            String patternArgName) {}

    private static final Map<String, ToolPathArg> FS_TOOL_PATH_ARGS = new LinkedHashMap<>();
    static {
        FS_TOOL_PATH_ARGS.put("ls",          new ToolPathArg(FilesystemOperation.READ,  "path",      ToolScope.BULK,  null));
        FS_TOOL_PATH_ARGS.put("read_file",   new ToolPathArg(FilesystemOperation.READ,  "file_path", ToolScope.EXACT, null));
        FS_TOOL_PATH_ARGS.put("write_file",  new ToolPathArg(FilesystemOperation.WRITE, "file_path", ToolScope.EXACT, null));
        FS_TOOL_PATH_ARGS.put("edit_file",   new ToolPathArg(FilesystemOperation.WRITE, "file_path", ToolScope.EXACT, null));
        FS_TOOL_PATH_ARGS.put("delete",      new ToolPathArg(FilesystemOperation.WRITE, "file_path", ToolScope.BULK,  null));
        FS_TOOL_PATH_ARGS.put("glob",        new ToolPathArg(FilesystemOperation.READ,  "path",      ToolScope.BULK,  "pattern"));
        FS_TOOL_PATH_ARGS.put("grep",        new ToolPathArg(FilesystemOperation.READ,  "path",      ToolScope.BULK,  null));
    }

    /** Read-only view of the {@link #FS_TOOL_PATH_ARGS} registry. */
    public static Map<String, ToolPathArg> fsToolPathArgs() {
        return FS_TOOL_PATH_ARGS;
    }

    // -----------------------------------------------------------------
    // when-predicate factories
    // -----------------------------------------------------------------

    /**
     * Build a {@code when} predicate that fires on interrupt-mode
     * rule matches. The behavior depends on the tool's
     * {@link ToolScope}.
     */
    public static Predicate<ToolCall> makeWhenPredicate(
            List<FilesystemPermission> rules,
            FilesystemOperation operation,
            String pathArgName,
            ToolScope scope,
            String patternArgName) {
        if (scope == ToolScope.EXACT) {
            return makeExactWhenPredicate(rules, operation, pathArgName);
        }
        return makeBulkWhenPredicate(rules, operation, pathArgName, patternArgName);
    }

    private static Predicate<ToolCall> makeExactWhenPredicate(
            List<FilesystemPermission> rules,
            FilesystemOperation operation,
            String pathArgName) {
        return req -> {
            if (req == null || req.args() == null) return false;
            Object raw = req.args().get(pathArgName);
            if (!(raw instanceof String s)) return false;
            String normalized;
            try {
                normalized = BackendUtils.validatePath(s, java.util.List.of());
            } catch (RuntimeException exc) {
                return false;
            }
            return FilesystemPermissionChecker.check(rules, operation, normalized) ==
                    FilesystemPermissionChecker.Verdict.INTERRUPT;
        };
    }

    private static Predicate<ToolCall> makeBulkWhenPredicate(
            List<FilesystemPermission> rules,
            FilesystemOperation operation,
            String pathArgName,
            String patternArgName) {
        // Precompute interrupt-mode rule anchors for this op.
        List<String> interruptAnchors = new java.util.ArrayList<>();
        for (FilesystemPermission rule : rules) {
            if (rule.mode() != FilesystemPermission.Mode.INTERRUPT) continue;
            if (!rule.operations().contains(operation)) continue;
            for (String pattern : rule.paths()) {
                interruptAnchors.add(BackendUtils.globAnchor(pattern));
            }
        }
        return req -> {
            if (req == null || req.args() == null) return false;
            if (interruptAnchors.isEmpty()) return false;
            Object rawPath = req.args().get(pathArgName);
            if (rawPath == null) {
                // Pathless bulk call can't be localized -> fire.
                return true;
            }
            if (!(rawPath instanceof String s)) return false;
            String normalized;
            try {
                normalized = BackendUtils.validatePath(s, java.util.List.of());
            } catch (RuntimeException exc) {
                return false;
            }
            if ("/.".equals(normalized)) normalized = "/";
            if (anyOverlap(normalized, interruptAnchors)) return true;
            if (patternArgName != null) {
                Object rawPattern = req.args().get(patternArgName);
                if (rawPattern instanceof String p && bulkPatternFires(p, interruptAnchors)) {
                    return true;
                }
            }
            return false;
        };
    }

    // -----------------------------------------------------------------
    // Pattern-fires
    // -----------------------------------------------------------------

    /**
     * Whether a glob {@code pattern} reaches an interrupt-mode
     * subtree regardless of {@code path}. An absolute pattern is
     * matched from its own root &mdash; the backend's
     * {@code chdir(path)} is ignored &mdash; so we gate on the
     * pattern's anchor. A relative pattern containing {@code ..} can
     * climb out of {@code path}; we cannot localize where it lands,
     * so we treat it as firing. Absoluteness comes from the raw
     * pattern, not {@code _glob_anchor}: the anchor of a
     * leading-wildcard relative pattern ({@code *.txt}) collapses to
     * {@code /}, which would otherwise look absolute.
     */
    public static boolean bulkPatternFires(String rawPattern, List<String> interruptAnchors) {
        if (rawPattern == null) return false;
        String posix = toPosixPath(rawPattern);
        if (posix.startsWith("/")) {
            return anyOverlap(BackendUtils.globAnchor(rawPattern), interruptAnchors);
        }
        return posix.contains("/../") || posix.startsWith("../") || posix.equals("..") || posix.endsWith("/..");
    }

    private static String toPosixPath(String s) {
        return s == null ? "" : s.replace('\\', '/');
    }

    private static boolean anyOverlap(String path, List<String> anchors) {
        for (String anchor : anchors) {
            if (BackendUtils.pathsOverlap(path, anchor)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    // Top-level build
    // -----------------------------------------------------------------

    /** The default allowed-decisions set, matching the Python port. */
    public static final List<String> ALLOWED_DECISIONS = List.of("approve", "edit", "reject", "respond");

    /**
     * Generate {@code interrupt_on} configs from interrupt-mode
     * permissions. Returns an entry for each filesystem tool whose
     * operation could be triggered by at least one interrupt-mode
     * rule.
     */
    public static Map<String, InterruptOnConfig> buildInterruptOnFromPermissions(
            List<FilesystemPermission> rules) {
        if (rules == null || !anyInterruptMode(rules)) return Map.of();
        Map<String, InterruptOnConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, ToolPathArg> e : FS_TOOL_PATH_ARGS.entrySet()) {
            String toolName = e.getKey();
            ToolPathArg tpa = e.getValue();
            if (!anyInterruptModeFor(rules, tpa.operation())) continue;
            result.put(toolName, new InterruptOnConfig(
                    ALLOWED_DECISIONS,
                    makeWhenPredicate(rules, tpa.operation(), tpa.pathArgName(),
                            tpa.scope(), tpa.patternArgName())));
        }
        return result;
    }

    private static boolean anyInterruptMode(List<FilesystemPermission> rules) {
        for (FilesystemPermission r : rules) {
            if (r.mode() == FilesystemPermission.Mode.INTERRUPT) return true;
        }
        return false;
    }

    private static boolean anyInterruptModeFor(List<FilesystemPermission> rules,
                                               FilesystemOperation op) {
        for (FilesystemPermission r : rules) {
            if (r.mode() == FilesystemPermission.Mode.INTERRUPT && r.operations().contains(op)) {
                return true;
            }
        }
        return false;
    }
}
