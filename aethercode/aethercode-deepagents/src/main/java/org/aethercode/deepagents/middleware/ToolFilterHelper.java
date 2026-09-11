package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.FilesystemToolNames;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.fs.backend.SandboxBackendProtocol;
import org.aethercode.deepagents.tools.Tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java-native port of
 * {@code deepagents.middleware.filesystem.FilesystemMiddleware._unsupported_tools_and_execution_state}
 * and the surrounding description-rewriting helpers.
 *
 * <p>The Python port's runtime {@code wrap_model_call} gate is
 * three concerns:</p>
 * <ol>
 *   <li>Drop capability-gated filesystem tools the resolved
 *       {@link BackendProtocol} can't serve
 *       ({@code execute} on non-sandbox backends,
 *       {@code delete} on backends that haven't overridden
 *       {@link BackendProtocol#delete(String)}).</li>
 *   <li>Swap the {@code grep} / {@code execute} tool descriptions
 *       so the model knows which search tools it can pair with
 *       shell execution.</li>
 *   <li>Append the filesystem system-prompt fragment
 *       (custom caller-supplied + route-host-path when
 *       {@code execute} is active) to the request's system
 *       message.</li>
 * </ol>
 *
 * <p>This class implements (1) fully. (2) and (3) are wired by
 * {@code FilesystemMiddleware.wrapModel_call} against the helpers
 * exposed here; the description templates themselves live with
 * the {@link FilesystemToolset} (see C4.36 for the full set of
 * {@code EXECUTE_TOOL_DESCRIPTION} variants).</p>
 */
public final class ToolFilterHelper {

    private ToolFilterHelper() {}

    /** Result of {@link #computeUnsupported(Set, BackendProtocol)}. */
    public record FilterResult(
            Set<String> unsupported,
            boolean executionActive,
            BackendProtocol backend) {
        public FilterResult {
            unsupported = Collections.unmodifiableSet(new LinkedHashSet<>(unsupported));
        }
    }

    /**
     * Whether {@code backend} can run shell commands. Mirrors the
     * Python port's
     * {@code supports_execution} helper. For
     * {@link org.aethercode.core.fs.backend.CompositeBackend} (when added)
     * the check will route to the default; for now, the simple
     * {@code instanceof} check covers all current backends.
     */
    public static boolean supportsExecution(BackendProtocol backend) {
        if (backend == null) return false;
        // Composite routing: check the default backend. Use reflection
        // to keep this helper free of a hard dependency on
        // CompositeBackend (which lives in the same package; the
        // reflection-free path is below if Composite is on the classpath).
        if ("org.aethercode.core.fs.backend.CompositeBackend".equals(
                backend.getClass().getName())) {
            try {
                Method defaultGetter = backend.getClass().getMethod("defaultBackend");
                Object defaultBackend = defaultGetter.invoke(backend);
                if (defaultBackend instanceof BackendProtocol def) {
                    return def instanceof SandboxBackendProtocol;
                }
            } catch (ReflectiveOperationException ignored) {
                // fall through to direct check
            }
        }
        return backend instanceof SandboxBackendProtocol;
    }

    /**
     * Whether {@code backend} has overridden
     * {@link BackendProtocol#delete(String)}. Mirrors the Python
     * port's {@code _supports_delete} (which inspects the class
     * to avoid invoking the throwing default).
     */
    public static boolean supportsDelete(BackendProtocol backend) {
        if (backend == null) return false;
        Class<?> cls = backend.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Method m = cls.getDeclaredMethod("delete", String.class);
                if (m.getDeclaringClass() != BackendProtocol.class) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // continue up the hierarchy
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    /**
     * Compute the set of filesystem tools the resolved
     * {@code backend} can't serve, and whether the {@code execute}
     * tool remains active.
     *
     * <p>Mirrors the Python port's
     * {@code _unsupported_tools_and_execution_state}: tools
     * excluded via the {@code tools=[...]} allowlist have already
     * been removed upstream; only backend-capability gating for
     * {@code execute} and {@code delete} is computed here.</p>
     */
    public static FilterResult computeUnsupported(
            Set<String> toolNames,
            BackendProtocol backend) {
        Set<String> unsupported = new LinkedHashSet<>();
        boolean executionActive = false;
        boolean hasExecute = toolNames.contains(FilesystemToolNames.EXECUTE);
        boolean hasDelete = toolNames.contains(FilesystemToolNames.DELETE);
        if (!hasExecute && !hasDelete) {
            return new FilterResult(unsupported, false, null);
        }
        if (hasExecute) {
            executionActive = supportsExecution(backend);
            if (!executionActive) {
                unsupported.add(FilesystemToolNames.EXECUTE);
            }
        }
        if (hasDelete && !supportsDelete(backend)) {
            unsupported.add(FilesystemToolNames.DELETE);
        }
        return new FilterResult(unsupported, executionActive, backend);
    }

    /**
     * Remove tools whose name is in {@code unsupported}, returning
     * a new list (preserving input order). Tools with a
     * {@code null} name are always retained &mdash; they are not
     * filesystem tools and the gate doesn't apply.
     */
    public static List<Tool> filterUnsupported(List<Tool> tools, Set<String> unsupported) {
        if (tools == null || tools.isEmpty()) return tools == null ? null : List.of();
        if (unsupported == null || unsupported.isEmpty()) return tools;
        List<Tool> result = new ArrayList<>(tools.size());
        boolean changed = false;
        for (Tool t : tools) {
            String name = t == null ? null : t.name();
            if (name != null && unsupported.contains(name)) {
                changed = true;
                continue;
            }
            result.add(t);
        }
        return changed ? Collections.unmodifiableList(result) : tools;
    }

    /**
     * Filter the supplied tools and return both the visible set and
     * the visible filesystem-tool names (used to drive the
     * {@code execute}-description rewrite).
     */
    public static FilteredTools filterAndVisibleFs(
            List<Tool> tools,
            BackendProtocol backend,
            Collection<String> filesystemToolNames) {
        if (tools == null) {
            return new FilteredTools(List.of(), Set.of(),
                    new FilterResult(Set.of(), false, null));
        }
        Set<String> toolNames = new LinkedHashSet<>();
        for (Tool t : tools) {
            if (t != null && t.name() != null) toolNames.add(t.name());
        }
        FilterResult r = computeUnsupported(toolNames, backend);
        List<Tool> filtered = filterUnsupported(tools, r.unsupported());
        Set<String> visibleFs = new LinkedHashSet<>();
        for (Tool t : filtered) {
            String name = t == null ? null : t.name();
            if (name != null && filesystemToolNames.contains(name)) {
                visibleFs.add(name);
            }
        }
        return new FilteredTools(filtered, visibleFs, r);
    }

    /** Bundled output of {@link #filterAndVisibleFs}. */
    public record FilteredTools(
            List<Tool> tools,
            Set<String> visibleFsToolNames,
            FilterResult filterResult) {
        public FilteredTools {
            tools = tools == null ? List.of() : Collections.unmodifiableList(tools);
            visibleFsToolNames = Collections.unmodifiableSet(new LinkedHashSet<>(visibleFsToolNames));
        }
    }

    /**
     * Default set of filesystem tool names used to populate
     * {@link #filterAndVisibleFs}'s {@code visibleFsToolNames}.
     * Mirrors the Python port's
     * {@code _DEFAULT_FS_TOOL_OPS} keys.
     */
    public static final Set<String> DEFAULT_FILESYSTEM_TOOL_NAMES = Set.of(
            FilesystemToolNames.LS,
            FilesystemToolNames.READ_FILE,
            FilesystemToolNames.GLOB,
            FilesystemToolNames.GREP,
            FilesystemToolNames.WRITE_FILE,
            FilesystemToolNames.EDIT_FILE,
            FilesystemToolNames.DELETE,
            FilesystemToolNames.EXECUTE);

    /**
     * Convenience overload of {@link #filterAndVisibleFs} that uses
     * {@link #DEFAULT_FILESYSTEM_TOOL_NAMES} as the set of
     * filesystem tool names.
     */
    public static FilteredTools filterAndVisibleFs(List<Tool> tools, BackendProtocol backend) {
        return filterAndVisibleFs(tools, backend, DEFAULT_FILESYSTEM_TOOL_NAMES);
    }

    /**
     * Build the {@code grep} tool description variant the model
     * should see. When {@code customDescription} is non-null, the
     * caller's override wins. Otherwise the variant depends on
     * whether {@code includeExecution} is true (the {@code execute}
     * tool is in the visible set) &mdash; the execute variant adds
     * a hint to use the shell's {@code rg} for genuine regex.
     *
     * <p>Mirrors the Python port's
     * {@code _grep_tool_description}.</p>
     */
    public static String grepToolDescription(
            String customDescription,
            boolean includeExecution,
            String defaultWithExecute,
            String defaultWithoutExecute) {
        if (customDescription != null && !customDescription.isEmpty()) {
            return customDescription;
        }
        return includeExecution ? defaultWithExecute : defaultWithoutExecute;
    }

    /**
     * Rewrite the {@code grep} tool's description when the
     * caller's current description matches one of the two default
     * variants. Returns the input list unchanged when no rewrite
     * is needed or when the caller supplied a custom description.
     */
    public static List<Tool> withFilteredGrepDescription(
            List<Tool> tools,
            boolean includeExecution,
            String customDescription,
            String defaultWithExecute,
            String defaultWithoutExecute) {
        if (tools == null || tools.isEmpty()) return tools;
        if (customDescription != null && !customDescription.isEmpty()) return tools;
        String target = grepToolDescription(null, includeExecution,
                defaultWithExecute, defaultWithoutExecute);
        if (target == null) return tools;
        Set<String> defaultDescriptions = new LinkedHashSet<>();
        if (defaultWithExecute != null) defaultDescriptions.add(defaultWithExecute);
        if (defaultWithoutExecute != null) defaultDescriptions.add(defaultWithoutExecute);
        return rewriteMatchingDescription(tools, FilesystemToolNames.GREP,
                defaultDescriptions, target);
    }

    /**
     * Build the {@code execute} tool description variant the model
     * should see. Mirrors the Python port's
     * {@code _execute_tool_description}: the variant depends on
     * which of {@code grep} / {@code glob} remain in the visible
     * tool set.
     */
    public static String executeToolDescription(
            String customDescription,
            Set<String> visibleSearchTools,
            String withGrepAndGlob,
            String withGrepOnly,
            String withGlobOnly,
            String withoutSearch) {
        if (customDescription != null && !customDescription.isEmpty()) {
            return customDescription;
        }
        boolean hasGrep = visibleSearchTools != null && visibleSearchTools.contains(FilesystemToolNames.GREP);
        boolean hasGlob = visibleSearchTools != null && visibleSearchTools.contains(FilesystemToolNames.GLOB);
        if (hasGrep && hasGlob) return withGrepAndGlob;
        if (hasGrep) return withGrepOnly;
        if (hasGlob) return withGlobOnly;
        return withoutSearch;
    }

    /**
     * Rewrite the {@code execute} tool's description when the
     * caller's current description matches one of the four default
     * variants. Returns the input list unchanged when no rewrite
     * is needed or when the caller supplied a custom description.
     */
    public static List<Tool> withFilteredExecuteDescription(
            List<Tool> tools,
            Set<String> visibleSearchTools,
            String customDescription,
            String withGrepAndGlob,
            String withGrepOnly,
            String withGlobOnly,
            String withoutSearch) {
        if (tools == null || tools.isEmpty()) return tools;
        if (customDescription != null && !customDescription.isEmpty()) return tools;
        String target = executeToolDescription(null, visibleSearchTools,
                withGrepAndGlob, withGrepOnly, withGlobOnly, withoutSearch);
        if (target == null) return tools;
        Set<String> defaultDescriptions = new LinkedHashSet<>();
        if (withGrepAndGlob != null) defaultDescriptions.add(withGrepAndGlob);
        if (withGrepOnly != null) defaultDescriptions.add(withGrepOnly);
        if (withGlobOnly != null) defaultDescriptions.add(withGlobOnly);
        if (withoutSearch != null) defaultDescriptions.add(withoutSearch);
        return rewriteMatchingDescription(tools, FilesystemToolNames.EXECUTE,
                defaultDescriptions, target);
    }

    /**
     * Copy the input tools, replacing the description of every
     * tool whose name matches {@code targetName} AND whose current
     * description is in {@code defaultDescriptions} but not already
     * equal to {@code newDescription}. Returns the input list
     * unchanged when no rewrite happened.
     */
    static List<Tool> rewriteMatchingDescription(
            List<Tool> tools,
            String targetName,
            Set<String> defaultDescriptions,
            String newDescription) {
        boolean changed = false;
        List<Tool> out = new ArrayList<>(tools.size());
        for (Tool t : tools) {
            if (t != null && targetName.equals(t.name())
                    && defaultDescriptions.contains(t.description())
                    && !newDescription.equals(t.description())) {
                out.add(t.withDescription(newDescription));
                changed = true;
            } else {
                out.add(t);
            }
        }
        return changed ? Collections.unmodifiableList(out) : tools;
    }

    /** Useful for tests: an empty filter result. */
    public static Map<String, Object> toMap(FilterResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unsupported", r.unsupported());
        m.put("executionActive", r.executionActive());
        m.put("backend", r.backend());
        return m;
    }
}
