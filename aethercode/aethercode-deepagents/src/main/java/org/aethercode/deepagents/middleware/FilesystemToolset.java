package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.FilesystemOperation;
import org.aethercode.core.middleware.FilesystemPathValidator;
import org.aethercode.core.middleware.FilesystemPermission;
import org.aethercode.core.middleware.FilesystemPermissionChecker;
import org.aethercode.core.middleware.FilesystemPermissionDeniedException;
import org.aethercode.core.middleware.FilesystemToolNames;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.fs.backend.EditResult;
import org.aethercode.core.fs.backend.FileDownloadResponse;
import org.aethercode.core.fs.backend.FileInfo;
import org.aethercode.core.fs.backend.GlobResult;
import org.aethercode.core.fs.backend.GrepMatch;
import org.aethercode.core.fs.backend.GrepResult;
import org.aethercode.core.fs.backend.LsResult;
import org.aethercode.core.fs.backend.ReadResult;
import org.aethercode.core.fs.backend.WriteResult;
import org.aethercode.deepagents.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The set of filesystem tools the {@link FilesystemMiddleware}
 * exposes to the model.
 *
 * <p>Java-native port of the toolset at the bottom of
 * {@code deepagents.middleware.filesystem.FilesystemMiddleware}.
 * Each tool is a thin {@link Tool} wrapper around the
 * {@link BackendProtocol} surface, gated by a permission
 * predicate that checks the call's path against
 * {@link FilesystemPermissionChecker}.</p>
 *
 * <p>The tools exposed are: {@code ls}, {@code read_file},
 * {@code write_file}, {@code edit_file}, {@code delete},
 * {@code glob}, {@code grep}, {@code execute}.</p>
 */
public class FilesystemToolset {
    private final BackendProtocol backend;
    private final List<FilesystemPermission> permissions;
    private final Map<String, Tool> tools;
    private final Set<String> enabledTools;
    private final java.util.Map<String, String> customToolDescriptions;
    /**
     * Default per-call cap on the number of grep matches; forwarded
     * to the backend's {@code grep} when the tool caller doesn't
     * supply a {@code max_count}. Mirrors the Python port's
     * {@code FilesystemMiddleware(grep_max_count=...)} default.
     */
    private final Integer defaultGrepMaxCount;

    /**
     * Default description for the {@code read_file} tool. Mirrors
     * the Python port's
     * {@code _READ_FILE_TOOL_DESCRIPTION_TEMPLATE} for the
     * text-only variant: the model learns that large tool
     * results are offloaded to a file path, and that the model
     * should page through large files with {@code offset} /
     * {@code limit}.
     */
    public static final String READ_FILE_TOOL_DESCRIPTION =
            "Reads a file from the filesystem. Assume any path the user provides is valid; "
                    + "reading a missing file returns an error.\n"
                    + "\n"
                    + "Usage:\n"
                    + "- By default, it reads up to 100 lines starting from the beginning of the file. "
                    + "Use `offset`/`limit` to page through large files instead of reading them whole.\n"
                    + "- Results are returned with line numbers starting at `offset` + 1 (1 by default), "
                    + "then two spaces, then the source line. Never include these line-number prefixes when editing.\n"
                    + "- Lines over 5,000 characters are split with continuation markers (e.g. 5.1, 5.2); "
                    + "`limit` counts source lines, so continuation rows do not consume the budget.\n"
                    + "- Speculatively batch multiple `read_file` calls in one response when several files may be useful.\n"
                    + "- An empty file returns a system-reminder warning in place of contents.\n"
                    + "- Large tool results may be offloaded to a file under the artifacts root "
                    + "(`/large_tool_results/` by default); the tool message gives the path. "
                    + "Read that path here, paging with `offset`/`limit`.\n"
                    + "- Images (`.png`, `.jpg`, etc.), audio, video, and PDFs return multimodal content blocks.\n"
                    + "- For images and PDFs, pagination via `offset`/`limit` is text-only - supply `file_path` only.\n"
                    + "- Always read a file before editing it.";

    /**
     * Default description for the {@code grep} tool. Mirrors
     * the Python port's
     * {@code _GREP_TOOL_DESCRIPTION_TEMPLATE} for the
     * text-only variant: literal (non-regex) search and the
     * offloaded-results directory hint.
     */
    public static final String GREP_TOOL_DESCRIPTION =
            "Search for a LITERAL text pattern across files (NOT regex).\n"
                    + "\n"
                    + "The pattern is matched verbatim: regex metacharacters are ordinary characters, "
                    + "not operators. To match any of several strings, run a separate grep for each; "
                    + "`grep(pattern=\"foo|bar\")` searches for the literal text \"foo|bar\", "
                    + "and `.*` or `\\\\.` match those characters literally.\n"
                    + "\n"
                    + "If you genuinely need regex, use the execute tool with `rg '<regex>'` instead.\n"
                    + "\n"
                    + "Returns matching files or content per `output_mode`. Offloaded large tool results live "
                    + "under the artifacts root (`/large_tool_results/` by default); grep that directory to "
                    + "search them when you do not know the exact path.";

    /**
     * Grep description variant used when the {@code execute} tool is
     * not in the visible set &mdash; the
     * "use {@code rg} instead" hint is dropped because there is no
     * execute to fall back to. Mirrors the Python port's
     * {@code _GREP_TOOL_DESCRIPTION_WITHOUT_EXECUTE}.
     */
    public static final String GREP_TOOL_DESCRIPTION_WITHOUT_EXECUTE =
            "Search for a LITERAL text pattern across files (NOT regex).\n"
                    + "\n"
                    + "The pattern is matched verbatim: regex metacharacters are ordinary characters, "
                    + "not operators. To match any of several strings, run a separate grep for each; "
                    + "`grep(pattern=\"foo|bar\")` searches for the literal text \"foo|bar\", "
                    + "and `.*` or `\\\\.` match those characters literally.\n"
                    + "\n"
                    + "Returns matching files or content per `output_mode`. Offloaded large tool results live "
                    + "under the artifacts root (`/large_tool_results/` by default); grep that directory to "
                    + "search them when you do not know the exact path.";

    // -----------------------------------------------------------------
    //  Execute description variants (rewritten by the
    //  FilesystemMiddleware when the visible search-tool set changes).
    //  Mirrors the Python port's _EXECUTE_TOOL_DESCRIPTION_TEMPLATE
    //  with the four search_guidance / bad_example combinations.
    // -----------------------------------------------------------------

    private static final String EXECUTE_DESCRIPTION_BASE =
            "Executes a shell command in an isolated sandbox and returns combined stdout/stderr with the exit code (truncated if very large).\n"
                    + "\n"
                    + "Usage:\n"
                    + "- Quote paths containing spaces (e.g. cd \"/path/with spaces\").\n"
                    + "- Chain commands with ';' or '&&' (use '&&' when a command depends on the previous); do not use newlines except inside quoted strings.\n"
                    + "- Use absolute paths and avoid `cd` so the working directory stays stable; use the optional timeout to override the default.\n"
                    + "- {search_guidance}Use read_file rather than cat/head/tail.{glob_bad_example}{grep_bad_example}\n"
                    + "\n"
                    + "Only available on backends implementing SandboxBackendProtocol; otherwise it returns an error.";

    /** Search guidance: avoid find + grep; use the dedicated tools. */
    private static final String EXECUTE_SEARCH_GUIDANCE_BOTH =
            "You MUST avoid using search commands like find and grep. Instead use the grep, glob tools to search. ";
    /** Search guidance: only grep is deduped, find is fine. */
    private static final String EXECUTE_SEARCH_GUIDANCE_GREP_ONLY =
            "You MUST avoid using shell grep for searches. Instead use the grep tool to search text. ";
    /** Search guidance: only glob is deduped, grep is fine. */
    private static final String EXECUTE_SEARCH_GUIDANCE_GLOB_ONLY =
            "You MUST avoid using shell find for searches. Instead use the glob tool to find files. ";

    private static final String EXECUTE_GLOB_BAD_EXAMPLE =
            "\n    - execute(command=\"find . -name '*.py'\")  # Use glob tool instead";
    private static final String EXECUTE_GREP_BAD_EXAMPLE =
            "\n    - execute(command=\"grep -r 'pattern' .\")  # Use grep tool instead";

    /** Execute description: both grep and glob are visible. */
    public static final String EXECUTE_TOOL_DESCRIPTION = EXECUTE_DESCRIPTION_BASE
            .replace("{search_guidance}", EXECUTE_SEARCH_GUIDANCE_BOTH)
            .replace("{glob_bad_example}", EXECUTE_GLOB_BAD_EXAMPLE)
            .replace("{grep_bad_example}", EXECUTE_GREP_BAD_EXAMPLE);
    /** Execute description: only grep is visible. */
    public static final String EXECUTE_TOOL_DESCRIPTION_WITH_GREP_ONLY = EXECUTE_DESCRIPTION_BASE
            .replace("{search_guidance}", EXECUTE_SEARCH_GUIDANCE_GREP_ONLY)
            .replace("{glob_bad_example}", "")
            .replace("{grep_bad_example}", EXECUTE_GREP_BAD_EXAMPLE);
    /** Execute description: only glob is visible. */
    public static final String EXECUTE_TOOL_DESCRIPTION_WITH_GLOB_ONLY = EXECUTE_DESCRIPTION_BASE
            .replace("{search_guidance}", EXECUTE_SEARCH_GUIDANCE_GLOB_ONLY)
            .replace("{glob_bad_example}", EXECUTE_GLOB_BAD_EXAMPLE)
            .replace("{grep_bad_example}", "");
    /** Execute description: neither grep nor glob is visible. */
    public static final String EXECUTE_TOOL_DESCRIPTION_WITHOUT_SEARCH = EXECUTE_DESCRIPTION_BASE
            .replace("{search_guidance}", "")
            .replace("{glob_bad_example}", "")
            .replace("{grep_bad_example}", "");

    public FilesystemToolset(BackendProtocol backend, List<FilesystemPermission> permissions) {
        this(backend, permissions, null, null, 1000);
    }

    public FilesystemToolset(BackendProtocol backend, List<FilesystemPermission> permissions,
                             Set<String> enabledTools) {
        this(backend, permissions, enabledTools, null, 1000);
    }

    public FilesystemToolset(BackendProtocol backend, List<FilesystemPermission> permissions,
                             Set<String> enabledTools,
                             java.util.Map<String, String> customToolDescriptions) {
        this(backend, permissions, enabledTools, customToolDescriptions, 1000);
    }

    /**
     * Constructor with tool allowlist, per-tool description
     * overrides, and the default grep match cap.
     *
     * <p>Mirrors the Python port's
     * {@code FilesystemMiddleware(tools=[...], custom_tool_descriptions={...}, grep_max_count=...)}.
     * The {@code customToolDescriptions} map keys are tool names; the
     * values replace the default descriptions for those tools. Names
     * not in the registry are silently dropped.</p>
     */
    public FilesystemToolset(BackendProtocol backend,
                             List<FilesystemPermission> permissions,
                             Set<String> enabledTools,
                             java.util.Map<String, String> customToolDescriptions,
                             Integer defaultGrepMaxCount) {
        this.backend = backend;
        this.permissions = permissions == null ? List.of() : permissions;
        this.enabledTools = enabledTools == null ? null : Set.copyOf(enabledTools);
        this.customToolDescriptions = customToolDescriptions == null
                ? java.util.Map.of()
                : java.util.Map.copyOf(customToolDescriptions);
        this.defaultGrepMaxCount = defaultGrepMaxCount == null ? 1000 : defaultGrepMaxCount;
        if (this.enabledTools != null && !this.enabledTools.contains(FilesystemToolNames.READ_FILE)) {
            throw new IllegalArgumentException(
                    "read_file must be included in tools; it is required by FilesystemMiddleware");
        }
        this.tools = new LinkedHashMap<>();
        if (isEnabled(FilesystemToolNames.LS))          registerTool(buildLsTool());
        if (isEnabled(FilesystemToolNames.READ_FILE))   registerTool(buildReadFileTool());
        if (isEnabled(FilesystemToolNames.WRITE_FILE))  registerTool(buildWriteFileTool());
        if (isEnabled(FilesystemToolNames.EDIT_FILE))   registerTool(buildEditFileTool());
        if (isEnabled(FilesystemToolNames.DELETE))      registerTool(buildDeleteTool());
        if (isEnabled(FilesystemToolNames.GLOB))        registerTool(buildGlobTool());
        if (isEnabled(FilesystemToolNames.GREP))        registerTool(buildGrepTool());
        if (backend instanceof org.aethercode.core.fs.backend.SandboxBackendProtocol
                && isEnabled(FilesystemToolNames.EXECUTE)) {
            registerTool(buildExecuteTool());
        }
    }

    /** True if {@code name} is in the allowlist (or no allowlist is set). */
    private boolean isEnabled(String name) {
        return enabledTools == null || enabledTools.contains(name);
    }

    /** The allowlist in effect (null = all tools enabled). */
    public Set<String> enabledTools() { return enabledTools; }

    public Set<String> toolNames() { return tools.keySet(); }
    public Map<String, Tool> tools() { return Map.copyOf(tools); }
    public Tool get(String name) { return tools.get(name); }
    public List<FilesystemPermission> permissions() { return permissions; }
    public BackendProtocol backend() { return backend; }

    private void registerTool(Tool t) { tools.put(t.name(), t); }

    // -----------------------------------------------------------------
    // Permission gating
    // -----------------------------------------------------------------

    private Predicate<String> allowed(FilesystemOperation op) {
        return path -> {
            if (path == null) return false;
            FilesystemPermissionChecker.Verdict v =
                    FilesystemPermissionChecker.check(permissions, op, path);
            return v != FilesystemPermissionChecker.Verdict.DENY;
        };
    }

    private void requireAllowed(FilesystemOperation op, String path) {
        // null path = "no path filter" (e.g. grep without `path`); let it through.
        if (path == null) return;
        if (!allowed(op).test(path)) {
            throw new FilesystemPermissionDeniedException(
                    "Permission denied for " + op + " on " + path);
        }
    }

    // -----------------------------------------------------------------
    // Tool builders
    // -----------------------------------------------------------------

    private Tool buildLsTool() {
        return FunctionalToolOf.of(FilesystemToolNames.LS,
                customDescription(FilesystemToolNames.LS, "List files in a directory (non-recursive)."),
                (args, ctx) -> {
                    String path = FilesystemPathValidator.validateAndNormalize(
                            stringArg(args, "path", "/"));
                    requireAllowed(FilesystemOperation.READ, path);
                    LsResult result = backend.ls(path);
                    return postFilterLs(result);
                });
    }

    private Tool buildReadFileTool() {
        return FunctionalToolOf.of(FilesystemToolNames.READ_FILE,
                customDescription(FilesystemToolNames.READ_FILE, READ_FILE_TOOL_DESCRIPTION),
                (args, ctx) -> {
                    String path = FilesystemPathValidator.validateAndNormalize(
                            stringArg(args, "file_path", null));
                    int offset = intArg(args, "offset", 0);
                    int limit = intArg(args, "limit", 2000);
                    requireAllowed(FilesystemOperation.READ, path);
                    return backend.read(path, offset, limit);
                });
    }

    private Tool buildWriteFileTool() {
        return FunctionalToolOf.of(FilesystemToolNames.WRITE_FILE,
                customDescription(FilesystemToolNames.WRITE_FILE, "Write content to a file, creating or overwriting it."),
                (args, ctx) -> {
                    String path = FilesystemPathValidator.validateAndNormalize(
                            stringArg(args, "file_path", null));
                    String content = stringArg(args, "content", "");
                    requireAllowed(FilesystemOperation.WRITE, path);
                    return backend.write(path, content);
                });
    }

    private Tool buildEditFileTool() {
        return FunctionalToolOf.of(FilesystemToolNames.EDIT_FILE,
                customDescription(FilesystemToolNames.EDIT_FILE, "Perform exact string replacements in an existing file."),
                (args, ctx) -> {
                    String path = FilesystemPathValidator.validateAndNormalize(
                            stringArg(args, "file_path", null));
                    String oldString = stringArg(args, "old_string", null);
                    String newString = stringArg(args, "new_string", null);
                    boolean replaceAll = boolArg(args, "replace_all", false);
                    requireAllowed(FilesystemOperation.WRITE, path);
                    return backend.edit(path, oldString, newString, replaceAll);
                });
    }

    private Tool buildDeleteTool() {
        return FunctionalToolOf.of(FilesystemToolNames.DELETE,
                customDescription(FilesystemToolNames.DELETE, "Delete a path, recursively removing anything nested under it."),
                (args, ctx) -> {
                    String path = FilesystemPathValidator.validateAndNormalize(
                            stringArg(args, "file_path", null));
                    // Recursive-delete permission gate: when the
                    // caller has any permission rules, a recursive
                    // delete (`hasDescendants=true`) collects every
                    // deny pattern that could match anything in the
                    // target subtree, regardless of rule order. For
                    // a confirmed plain file, the first matching rule
                    // wins (same as write_file). Mirrors the Python
                    // port's _find_delete_deny_patterns /
                    // _delete_target_may_have_descendants.
                    if (!permissions.isEmpty()) {
                        boolean hasDescendants = FilesystemPermissionChecker
                                .deleteTargetMayHaveDescendants(
                                        backend, path, true);
                        List<String> deny = FilesystemPermissionChecker
                                .findDeleteDenyPatterns(permissions, path, hasDescendants);
                        if (!deny.isEmpty()) {
                            String message = "Error: permission denied for write on "
                                    + path + "; the following deny rules protect "
                                    + "the delete target's subtree: " + deny;
                            throw new FilesystemPermissionDeniedException(message);
                        }
                    }
                    requireAllowed(FilesystemOperation.WRITE, path);
                    return backend.delete(path);
                });
    }

    private Tool buildGlobTool() {
        return FunctionalToolOf.of(FilesystemToolNames.GLOB,
                customDescription(FilesystemToolNames.GLOB, "Find files matching a glob pattern."),
                GLOB_SCHEMA,
                (args, ctx) -> {
                    String pattern = stringArg(args, "pattern", null);
                    String path = FilesystemPathValidator.validateAndNormalize(
                            stringArg(args, "path", null));
                    requireAllowed(FilesystemOperation.READ, path);
                    GlobResult result = backend.glob(pattern, path);
                    return postFilterGlob(result);
                });
    }

    /**
     * JSON schema for the {@code glob} tool. Mirrors the Python
     * port's Pydantic-derived schema: {@code path} is optional
     * (defaults to the backend's default root).
     *
     * <p>Built as a {@link java.util.LinkedHashMap} (rather than
     * {@code Map.of}) so that the {@code "default": null} entry
     * for {@code path} is preserved — {@code Map.of} rejects null
     * values. Insertion order is also preserved, which keeps the
     * schema stable across JVMs for testing.</p>
     */
    public static final java.util.Map<String, Object> GLOB_SCHEMA = buildGlobSchema();

    private static java.util.Map<String, Object> buildGlobSchema() {
        java.util.LinkedHashMap<String, Object> pattern = new java.util.LinkedHashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "Glob pattern to match file paths against.");

        java.util.LinkedHashMap<String, Object> pathAnyOfString = new java.util.LinkedHashMap<>();
        pathAnyOfString.put("type", "string");
        java.util.LinkedHashMap<String, Object> pathAnyOfNull = new java.util.LinkedHashMap<>();
        pathAnyOfNull.put("type", "null");

        java.util.LinkedHashMap<String, Object> path = new java.util.LinkedHashMap<>();
        path.put("anyOf", java.util.List.of(pathAnyOfString, pathAnyOfNull));
        path.put("default", null);
        path.put("description", "Directory to search under; null uses the backend's default root.");

        java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("pattern", pattern);
        properties.put("path", path);

        java.util.LinkedHashMap<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("pattern"));
        return java.util.Collections.unmodifiableMap(schema);
    }

    private Tool buildGrepTool() {
        return FunctionalToolOf.of(FilesystemToolNames.GREP,
                customDescription(FilesystemToolNames.GREP, GREP_TOOL_DESCRIPTION),
                (args, ctx) -> {
                    String pattern = stringArg(args, "pattern", null);
                    String path = FilesystemPathValidator.validateAndNormalize(
                            stringArg(args, "path", null));
                    String glob = stringArg(args, "glob", null);
                    Integer maxCount = intArgBoxed(args, "max_count");
                    if (maxCount != null && maxCount <= 0) {
                        // Per-call max_count must be positive — matches the
                        // Python GrepSchema validation.
                        throw new IllegalArgumentException(
                                "max_count must be greater than 0 (got " + maxCount + ")");
                    }
                    if (maxCount == null) maxCount = defaultGrepMaxCount;
                    requireAllowed(FilesystemOperation.READ, path);
                    GrepResult result = backend.grep(pattern, path, glob, maxCount);
                    return postFilterGrep(result);
                });
    }

    private Tool buildExecuteTool() {
        return FunctionalToolOf.of(FilesystemToolNames.EXECUTE,
                customDescription(FilesystemToolNames.EXECUTE, EXECUTE_TOOL_DESCRIPTION),
                (args, ctx) -> {
                    String command = stringArg(args, "command", null);
                    Integer timeout = intArgBoxed(args, "timeout");
                    // execute() doesn't take a path; we can't path-gate it
                    // the same way, so we just delegate.
                    return ((org.aethercode.core.fs.backend.SandboxBackendProtocol) backend)
                            .execute(command, timeout);
                });
    }

    /**
     * Look up a per-tool description override; fall back to
     * {@code defaultDescription} when the caller hasn't supplied one.
     * Mirrors the Python port's
     * {@code self._custom_tool_descriptions.get(name) or default}.
     */
    private String customDescription(String toolName, String defaultDescription) {
        if (customToolDescriptions == null || customToolDescriptions.isEmpty()) {
            return defaultDescription;
        }
        String override = customToolDescriptions.get(toolName);
        return override == null ? defaultDescription : override;
    }

    // -----------------------------------------------------------------
    // Argument coercion helpers
    // -----------------------------------------------------------------

    private static String stringArg(Map<String, Object> args, String name, String def) {
        if (args == null) return def;
        Object v = args.get(name);
        return v == null ? def : String.valueOf(v);
    }
    private static int intArg(Map<String, Object> args, String name, int def) {
        if (args == null) return def;
        Object v = args.get(name);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }
    private static Integer intArgBoxed(Map<String, Object> args, String name) {
        if (args == null) return null;
        Object v = args.get(name);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
    private static boolean boolArg(Map<String, Object> args, String name, boolean def) {
        if (args == null) return def;
        Object v = args.get(name);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    // -----------------------------------------------------------------
    //  Post-filter (permission-aware)
    // -----------------------------------------------------------------

    /**
     * Filter a {@link LsResult}'s entries through the permission rules,
     * removing any path that the rules deny for read. Mirrors the
     * Python port's {@code _filter_file_infos_by_permission} helper.
     */
    private LsResult postFilterLs(LsResult result) {
        if (result.error().isPresent() || result.entries().isEmpty()) return result;
        List<FileInfo> all = result.entries().get();
        List<FileInfo> kept = new ArrayList<>(all.size());
        for (FileInfo fi : all) {
            if (FilesystemPermissionChecker.check(permissions,
                    FilesystemOperation.READ, fi.path())
                    != FilesystemPermissionChecker.Verdict.DENY) {
                kept.add(fi);
            }
        }
        return LsResult.of(List.copyOf(kept));
    }

    /**
     * Filter a {@link GlobResult}'s matches through the permission rules.
     * Mirrors {@code _filter_file_infos_by_permission}.
     */
    private GlobResult postFilterGlob(GlobResult result) {
        if (result.error().isPresent() || result.matches().isEmpty()) return result;
        List<FileInfo> all = result.matches().get();
        List<FileInfo> kept = new ArrayList<>(all.size());
        for (FileInfo fi : all) {
            if (FilesystemPermissionChecker.check(permissions,
                    FilesystemOperation.READ, fi.path())
                    != FilesystemPermissionChecker.Verdict.DENY) {
                kept.add(fi);
            }
        }
        return new GlobResult(result.error(), Optional.of(List.copyOf(kept)),
                result.truncated());
    }

    /**
     * Filter a {@link GrepResult}'s matches through the permission rules.
     * Mirrors {@code _filter_grep_matches_by_permission}.
     */
    private GrepResult postFilterGrep(GrepResult result) {
        if (result.error().isPresent() || result.matches().isEmpty()) return result;
        List<GrepMatch> all = result.matches().get();
        List<GrepMatch> kept = new ArrayList<>(all.size());
        for (GrepMatch m : all) {
            if (FilesystemPermissionChecker.check(permissions,
                    FilesystemOperation.READ, m.path())
                    != FilesystemPermissionChecker.Verdict.DENY) {
                kept.add(m);
            }
        }
        return new GrepResult(result.error(), Optional.of(List.copyOf(kept)),
                result.truncated());
    }
}
