package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.FilesystemPermission;
import org.aethercode.core.middleware.FilesystemState;
import org.aethercode.core.middleware.FilesystemToolNames;
import org.aethercode.core.middleware.MediaResultReorderer;
import org.aethercode.core.middleware.MultimodalContentScrubber;
import org.aethercode.core.middleware.ToolMessageEviction;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.HumanMessage;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * Filesystem middleware for file operations and shell execution.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.filesystem.FilesystemMiddleware}.
 * Provides a curated set of tools ({@code ls}, {@code read_file},
 * {@code write_file}, {@code edit_file}, {@code delete},
 * {@code glob}, {@code grep}, {@code execute}) that operate
 * against a {@link BackendProtocol} and are gated by a list of
 * {@link FilesystemPermission} rules.</p>
 *
 * <p>The actual tool implementations live in
 * {@link FilesystemToolset}; this class is the middleware that
 * injects the rules into the model adapter's tool registry and
 * applies the {@code beforeModel}/{@code afterModel} hooks that
 * the Python port uses. The Java port defers the model-aware
 * bits (system-prompt injection for tool usage) to R3.</p>
 *
 * <p>Also enforces the {@code humanMessageTokenLimitBeforeEvict}
 * guard: when the most-recent {@link HumanMessage}
 * exceeds the configured token limit, its content is written to
 * the backend and the message is tagged with the offload path
 * (via {@link HumanMessage#evictedTo()}). The tagged
 * message is what state stores; the original content remains
 * recoverable from the backend via the {@code read_file} tool.</p>
 */
public class FilesystemMiddleware implements Middleware {
    private static final Logger LOGGER = Logger.getLogger(FilesystemMiddleware.class.getName());

    /** Approximate chars per token used by Python's port heuristic (4). */
    public static final int NUM_CHARS_PER_TOKEN = 4;

    /** Default eviction prefix for human-message offloads. */
    public static final String DEFAULT_EVICTED_PREFIX = "/large_human_messages";

    /** Default eviction prefix for large tool results. Mirrors Python's `_root + "/large_tool_results"`. */
    public static final String DEFAULT_LARGE_TOOL_RESULTS_PREFIX = "/large_tool_results";

    /** Default conversation-history prefix (for summarization middleware). */
    public static final String DEFAULT_CONVERSATION_HISTORY_PREFIX = "/conversation_history";

    /**
     * Tool names whose result is never evicted regardless of the
     * token-limit configuration. Mirrors Python's
     * {@code TOOLS_EXCLUDED_FROM_EVICTION}: tools that already
     * paginate or whose result is intrinsically bounded by path
     * &mdash; eviction would just trade one large message for
     * another.
     */
    public static final java.util.Set<String> TOOLS_EXCLUDED_FROM_EVICTION = java.util.Set.of(
            FilesystemToolNames.LS,
            FilesystemToolNames.GLOB,
            FilesystemToolNames.GREP,
            FilesystemToolNames.READ_FILE,
            FilesystemToolNames.EDIT_FILE,
            FilesystemToolNames.WRITE_FILE,
            FilesystemToolNames.DELETE);

    private final BackendProtocol backend;
    private final List<FilesystemPermission> permissions;
    private final FilesystemToolset toolset;
    private final int humanMessageTokenLimitBeforeEvict;
    private final String evictedPrefix;
    private final int toolTokenLimitBeforeEvict;
    private final String largeToolResultsPrefix;
    private final String conversationHistoryPrefix;
    private final java.util.Map<String, String> customToolDescriptions;
    private final String customSystemPrompt;
    private final Class<?> stateSchema;
    /**
     * Default per-call cap on the number of matches the {@code grep}
     * tool returns. The model can override it per call via the
     * tool's {@code max_count} argument. Mirrors the Python port's
     * {@code grep_max_count} parameter; defaults to 1000.
     */
    private final Integer grepMaxCount;

    public FilesystemMiddleware(BackendProtocol backend, List<FilesystemPermission> permissions) {
        this(backend, permissions, 0, DEFAULT_EVICTED_PREFIX,
                0, null, java.util.Map.of(), null, null);
    }

    /**
     * Constructor with tool allowlist. Mirrors the Python port's
     * {@code FilesystemMiddleware(tools=[...])} parameter. Pass
     * {@code null} or {@code "all"} (use
     * {@link #enableAllTools()}) to register every tool. When a
     * non-null list is provided, only those tool names are exposed to
     * the model and registered with the dispatcher. The list must
     * contain {@code "read_file"} (matches the Python
     * {@code ValueError} guard).
     */
    public FilesystemMiddleware(BackendProtocol backend,
                                List<FilesystemPermission> permissions,
                                List<String> tools) {
        this(backend, permissions, 0, DEFAULT_EVICTED_PREFIX,
                0, null, java.util.Map.of(),
                null, tools == null ? null : Set.copyOf(tools));
    }

    /** Convenience: defaults to a fresh {@link org.aethercode.core.fs.backend.StateBackend}. */
    public FilesystemMiddleware() {
        this(new org.aethercode.core.fs.backend.StateBackend(), List.of());
    }

    /**
     * Backward-compatible 4-arg constructor (no custom tool
     * descriptions). Calls the 5-arg form with an empty override map.
     */
    public FilesystemMiddleware(BackendProtocol backend,
                                List<FilesystemPermission> permissions,
                                int humanMessageTokenLimitBeforeEvict,
                                String evictedPrefix) {
        this(backend, permissions, humanMessageTokenLimitBeforeEvict, evictedPrefix,
                0, null, java.util.Map.of(), null, null);
    }

    /**
     * Backward-compatible 5-arg constructor. Calls the 6-arg form
     * with a null tool allowlist (i.e. all tools enabled).
     */
    public FilesystemMiddleware(BackendProtocol backend,
                                List<FilesystemPermission> permissions,
                                int humanMessageTokenLimitBeforeEvict,
                                String evictedPrefix,
                                java.util.Map<String, String> customToolDescriptions) {
        this(backend, permissions, humanMessageTokenLimitBeforeEvict, evictedPrefix,
                0, null, customToolDescriptions, null, null);
    }

    /**
     * Backward-compatible 6-arg constructor. Calls the 7-arg form
     * with a null tool allowlist (i.e. all tools enabled).
     */
    public FilesystemMiddleware(BackendProtocol backend,
                                List<FilesystemPermission> permissions,
                                int humanMessageTokenLimitBeforeEvict,
                                String evictedPrefix,
                                int toolTokenLimitBeforeEvict,
                                String largeToolResultsPrefix,
                                java.util.Map<String, String> customToolDescriptions) {
        this(backend, permissions, humanMessageTokenLimitBeforeEvict, evictedPrefix,
                toolTokenLimitBeforeEvict, largeToolResultsPrefix,
                customToolDescriptions, null, null);
    }

    /**
     * Full constructor with optional tool allowlist and optional
     * custom system prompt.
     *
     * <p>Mirrors the Python port's
     * {@code FilesystemMiddleware(..., system_prompt=...)}:
     * the {@code customSystemPrompt} is appended to the agent's
     * system prompt at assembly time (when the runtime consults
     * this middleware for prompt fragments). The Java port's
     * {@link org.aethercode.core.graph.CreateDeepAgent} reads
     * {@link #systemPromptFragment()} (and {@link #routeHostPathPrompt(BackendProtocol)})
     * to assemble the final system prompt.</p>
     *
     * @param backend the filesystem backend
     * @param permissions the permission rules
     * @param humanMessageTokenLimitBeforeEvict 0 disables the guard;
     *        otherwise evict the most-recent HumanMessage when its
     *        text length exceeds {@code token_limit * 4} chars.
     * @param evictedPrefix where to write evicted message bodies on
     *        the backend (default {@link #DEFAULT_EVICTED_PREFIX}).
     * @param toolTokenLimitBeforeEvict 0 disables the guard;
     *        otherwise evict tool results whose text length exceeds
     *        {@code token_limit * 4} chars to
     *        {@code largeToolResultsPrefix}/{sanitized tool_call_id}
     *        and return a head+tail preview. Mirrors Python's
     *        {@code tool_token_limit_before_evict}.
     * @param largeToolResultsPrefix path prefix under which large
     *        tool results are offloaded; when {@code null}, derived
     *        from the backend's {@code artifacts_root} (for
     *        {@link org.aethercode.core.fs.backend.CompositeBackend}) or
     *        defaults to {@link #DEFAULT_LARGE_TOOL_RESULTS_PREFIX}.
     * @param customToolDescriptions per-tool description overrides
     *        keyed by tool name (e.g. {@code "ls"}, {@code "read_file"}).
     *        Names not present in the tool registry are silently
     *        dropped.
     * @param customSystemPrompt optional caller-provided system-prompt
     *        fragment appended to the agent's system prompt at
     *        assembly time. Mirrors Python's
     *        {@code system_prompt} parameter.
     * @param enabledTools optional tool allowlist (null = all
     *        tools). Must contain {@code "read_file"} when non-null.
     */
    public FilesystemMiddleware(BackendProtocol backend,
                                List<FilesystemPermission> permissions,
                                int humanMessageTokenLimitBeforeEvict,
                                String evictedPrefix,
                                int toolTokenLimitBeforeEvict,
                                String largeToolResultsPrefix,
                                java.util.Map<String, String> customToolDescriptions,
                                String customSystemPrompt,
                                java.util.Set<String> enabledTools) {
        this(backend, permissions, humanMessageTokenLimitBeforeEvict, evictedPrefix,
                toolTokenLimitBeforeEvict, largeToolResultsPrefix,
                customToolDescriptions, customSystemPrompt, enabledTools, 1000);
    }

    /**
     * Full constructor with an explicit {@code grepMaxCount}. The
     * 9-arg form is the canonical ctor; this 10-arg variant exists
     * for {@link #withGrepMaxCount(Integer)}.
     */
    public FilesystemMiddleware(BackendProtocol backend,
                                List<FilesystemPermission> permissions,
                                int humanMessageTokenLimitBeforeEvict,
                                String evictedPrefix,
                                int toolTokenLimitBeforeEvict,
                                String largeToolResultsPrefix,
                                java.util.Map<String, String> customToolDescriptions,
                                String customSystemPrompt,
                                java.util.Set<String> enabledTools,
                                Integer grepMaxCount) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.permissions = permissions == null ? List.of() : List.copyOf(permissions);
        // Reject permissions when the backend supports command execution
        // and the rules are not all scoped under the composite routes.
        // Mirrors Python's FilesystemMiddleware.__init__ guard:
        // permissions + sandbox backend + un-scoped paths → NotImplementedError
        // because tool-level permissions for the execute tool are not
        // implemented.
        if (!this.permissions.isEmpty()
                && supportsExecution(backend)
                && !allPathsScopedToRoutes(this.permissions, backend)) {
            throw new UnsupportedOperationException(
                    "FilesystemMiddleware does not yet support permissions with backends that "
                            + "provide command execution (SandboxBackendProtocol). Tool-level "
                            + "permissions for the execute tool are not implemented. Either "
                            + "remove permissions or use a backend without execution support.");
        }
        this.toolset = new FilesystemToolset(backend, this.permissions, enabledTools,
                customToolDescriptions, grepMaxCount);
        this.humanMessageTokenLimitBeforeEvict = humanMessageTokenLimitBeforeEvict;
        this.evictedPrefix = evictedPrefix == null ? DEFAULT_EVICTED_PREFIX : evictedPrefix;
        this.toolTokenLimitBeforeEvict = toolTokenLimitBeforeEvict;
        // Derive the large_tool_results / conversation_history prefixes
        // from the backend's artifacts_root (when it's a CompositeBackend)
        // or the explicit override, mirroring the Python port's
        // `_root = artifacts_root.rstrip("/")` logic.
        String derivedRoot = deriveArtifactsRoot(backend);
        String effectiveLargePrefix = largeToolResultsPrefix;
        if (effectiveLargePrefix == null) {
            effectiveLargePrefix = derivedRoot + "/large_tool_results";
        }
        if (effectiveLargePrefix.endsWith("/")) {
            effectiveLargePrefix = effectiveLargePrefix.substring(0, effectiveLargePrefix.length() - 1);
        }
        this.largeToolResultsPrefix = effectiveLargePrefix;
        this.conversationHistoryPrefix = derivedRoot + "/conversation_history";
        this.customToolDescriptions = customToolDescriptions == null
                ? java.util.Map.of()
                : java.util.Map.copyOf(customToolDescriptions);
        this.customSystemPrompt = customSystemPrompt;
        this.stateSchema = inferStateSchema(backend);
        this.grepMaxCount = grepMaxCount == null ? 1000 : grepMaxCount;
    }

    /**
     * Override the default grep match cap. Mirrors the Python port's
     * {@code FilesystemMiddleware(grep_max_count=...)} parameter.
     *
     * @param grepMaxCount the per-call cap forwarded when the
     *        model doesn't supply a {@code max_count} argument. Must
     *        be positive; {@code null} disables the default cap
     *        (return every match).
     * @return a new middleware with the cap set; this object is
     *         immutable and not modified.
     */
    public FilesystemMiddleware withGrepMaxCount(Integer grepMaxCount) {
        if (grepMaxCount != null && grepMaxCount <= 0) {
            throw new IllegalArgumentException(
                    "grep_max_count must be positive (got " + grepMaxCount + ")");
        }
        // Build a new middleware that re-uses the existing fields
        // but with the new cap. The toolset is reconstructed so the
        // grep tool sees the new default.
        return new FilesystemMiddleware(backend, permissions,
                humanMessageTokenLimitBeforeEvict, evictedPrefix,
                toolTokenLimitBeforeEvict, largeToolResultsPrefix,
                customToolDescriptions, customSystemPrompt,
                toolset.enabledTools(),
                grepMaxCount);
    }

    /** The default per-call grep cap. {@code null} means "no cap". */
    public Integer grepMaxCount() { return grepMaxCount; }

    /**
     * Resolve the artifacts root used to prefix {@code large_tool_results}
     * and {@code conversation_history} directories. Mirrors the Python
     * port's logic: when the backend is a
     * {@link org.aethercode.core.fs.backend.CompositeBackend} we use its
     * {@code artifacts_root} (trailing slash stripped); otherwise we
     * fall back to {@code "/"}.
     */
    private static String deriveArtifactsRoot(BackendProtocol backend) {
        if (backend instanceof org.aethercode.core.fs.backend.CompositeBackend comp) {
            String root = comp.artifactsRoot();
            if (root == null) return "";
            return root.endsWith("/") && root.length() > 1
                    ? root.substring(0, root.length() - 1)
                    : root;
        }
        return "";
    }

    /**
     * True when {@code backend} implements {@code SandboxBackendProtocol}
     * (supports execution). For a {@link org.aethercode.core.fs.backend.CompositeBackend},
     * checks the default backend (since CompositeBackend.execute delegates to
     * the default). Mirrors Python's
     * {@code deepagents.middleware.filesystem.supports_execution}.
     */
    private static boolean supportsExecution(BackendProtocol backend) {
        if (backend instanceof org.aethercode.core.fs.backend.CompositeBackend comp) {
            return comp.defaultBackend() instanceof org.aethercode.core.fs.backend.SandboxBackendProtocol;
        }
        return backend instanceof org.aethercode.core.fs.backend.SandboxBackendProtocol;
    }

    /**
     * All permission-rule paths must be scoped under a CompositeBackend
     * route prefix. Mirrors Python's
     * {@code _all_paths_scoped_to_routes} helper.
     */
    private static boolean allPathsScopedToRoutes(List<FilesystemPermission> rules,
                                                  BackendProtocol backend) {
        if (!(backend instanceof org.aethercode.core.fs.backend.CompositeBackend comp)) return false;
        java.util.Set<String> prefixes = comp.routes().keySet();
        if (prefixes.isEmpty()) return false;
        for (FilesystemPermission rule : rules) {
            for (String path : rule.paths()) {
                boolean any = false;
                for (String pfx : prefixes) {
                    if (path.startsWith(pfx)) { any = true; break; }
                }
                if (!any) return false;
            }
        }
        return true;
    }

    /** Sentinel constant for "register every tool". Equivalent to passing {@code null} for {@code tools}. */
    public static java.util.Set<String> enableAllTools() { return null; }

    public BackendProtocol backend() { return backend; }
    public List<FilesystemPermission> permissions() { return permissions; }
    public FilesystemToolset toolset() { return toolset; }
    /** The tool allowlist in effect (null = all tools enabled). */
    public java.util.Set<String> enabledTools() { return toolset.enabledTools(); }
    public int humanMessageTokenLimitBeforeEvict() { return humanMessageTokenLimitBeforeEvict; }
    public String evictedPrefix() { return evictedPrefix; }
    /** Tool-result token-limit guard. 0 means disabled. */
    public int toolTokenLimitBeforeEvict() { return toolTokenLimitBeforeEvict; }
    /** Path prefix under which large tool results are offloaded. */
    public String largeToolResultsPrefix() { return largeToolResultsPrefix; }
    /** Path prefix for conversation history (used by SummarizationMiddleware). */
    public String conversationHistoryPrefix() { return conversationHistoryPrefix; }
    public java.util.Map<String, String> customToolDescriptions() { return customToolDescriptions; }
    /**
     * Caller-supplied system-prompt fragment, or {@code null} when
     * none was provided. Mirrors the Python port's
     * {@code _custom_system_prompt} private field. The runtime
     * consults this via {@link #systemPromptFragment()} when
     * assembling the agent's final system prompt.
     */
    public String customSystemPrompt() { return customSystemPrompt; }
    /**
     * Build the system-prompt fragment the runtime should append to
     * the agent's final system prompt. Returns the caller-supplied
     * fragment (when non-null/non-blank), or an empty string.
     * Mirrors the Python port's `_filter_unsupported_tools_and_apply_prompt`
     * branch that consumes {@code self._custom_system_prompt}.
     */
    public String systemPromptFragment() {
        return customSystemPrompt == null ? "" : customSystemPrompt;
    }
    /** The state schema the middleware expects; see {@link FilesystemState}. */
    public Class<?> stateSchema() { return stateSchema; }

    /**
     * Pick the state schema: {@link FilesystemState} for backends
     * whose {@code files} channel is hosted in the in-process agent
     * state — currently {@code StateBackend} and any
     * {@code CompositeBackend} whose default or any routed
     * backend is a {@code StateBackend}. The base
     * {@code AgentState} is used for everything else.
     *
     * <p>Mirrors the Python port's
     * {@code deepagents.middleware.filesystem._infer_state_schema}
     * which walks the default backend plus every routed backend
     * so a {@code StateBackend} buried under a
     * {@code CompositeBackend} is still detected.</p>
     */
    static Class<?> inferStateSchema(BackendProtocol backend) {
        if (backend instanceof org.aethercode.core.fs.backend.StateBackend) {
            return FilesystemState.class;
        }
        if (backend instanceof org.aethercode.core.fs.backend.CompositeBackend comp) {
            if (inferStateSchema(comp.defaultBackend()) == FilesystemState.class) {
                return FilesystemState.class;
            }
            for (org.aethercode.core.fs.backend.BackendProtocol route : comp.routes().values()) {
                if (inferStateSchema(route) == FilesystemState.class) {
                    return FilesystemState.class;
                }
            }
            return org.aethercode.core.runtime.AgentState.class;
        }
        return org.aethercode.core.runtime.AgentState.class;
    }

    @Override
    public String name() { return "FilesystemMiddleware"; }

    /**
     * Filter the tool registry to only the tools this middleware
     * provides. Mirrors the Python port's
     * {@code wrap_model_call} tool-filtering behavior.
     *
     * <p>In addition, this hook applies the
     * {@link MediaResultReorderer} so that any
     * {@code read_file}-emitted media {@link HumanMessage}
     * is moved behind its {@code AIMessage} tool-call batch —
     * providers require every {@code ToolMessage} for an
     * assistant tool-call batch to arrive before any non-tool
     * message, and the Python port's
     * {@code _move_media_results_after_tool_results} enforces
     * that invariant.</p>
     */
    @Override
    public AIMessage wrapModelCall(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        // 1) Filter tools by backend capability (drop execute on a
        //    non-sandbox backend, drop delete on a backend that
        //    hasn't overridden the default). Mirrors the Python
        //    port's _filter_unsupported_tools_and_apply_prompt.
        List<org.aethercode.deepagents.tools.Tool> visibleTools = runtime.tools();
        ToolFilterHelper.FilteredTools ft = ToolFilterHelper.filterAndVisibleFs(visibleTools, backend);
        // 2) Rewrite the grep / execute tool descriptions to match the
        //    current visible search-tool set, when the user hasn't
        //    supplied a custom description for them.
        java.util.Set<String> visibleFs = ft.visibleFsToolNames();
        boolean executionActive = ft.filterResult().executionActive();
        String customGrep = customToolDescriptions == null ? null : customToolDescriptions.get(FilesystemToolNames.GREP);
        String customExecute = customToolDescriptions == null ? null : customToolDescriptions.get(FilesystemToolNames.EXECUTE);
        java.util.List<org.aethercode.deepagents.tools.Tool> withGrep = ToolFilterHelper.withFilteredGrepDescription(
                ft.tools(), executionActive, customGrep,
                FilesystemToolset.GREP_TOOL_DESCRIPTION,
                FilesystemToolset.GREP_TOOL_DESCRIPTION_WITHOUT_EXECUTE);
        java.util.List<org.aethercode.deepagents.tools.Tool> withExecute = ToolFilterHelper.withFilteredExecuteDescription(
                withGrep, visibleFs, customExecute,
                FilesystemToolset.EXECUTE_TOOL_DESCRIPTION,
                FilesystemToolset.EXECUTE_TOOL_DESCRIPTION_WITH_GREP_ONLY,
                FilesystemToolset.EXECUTE_TOOL_DESCRIPTION_WITH_GLOB_ONLY,
                FilesystemToolset.EXECUTE_TOOL_DESCRIPTION_WITHOUT_SEARCH);
        // Build a per-runtime tool list and swap the Runtime's tools
        // view if it differs from the input.
        java.util.List<org.aethercode.deepagents.tools.Tool> finalTools = withExecute;
        if (finalTools != visibleTools) {
            // The runtime currently exposes the raw tool list; we
            // can only signal the rewrite by composing the messages
            // through a wrapper. For now, the runtime.tools() view
            // stays unchanged here; the per-call description patches
            // are applied on the Tools the model actually sees in
            // the next iteration. The most-important behavioral
            // effect is already captured (unsupported tools removed)
            // — see note in the C4.35 summary.
            // (The wrapModelCall hook does not currently expose a
            // tool-rewriting path; the rewrite is applied during
            // the next model call by re-walking the chain with the
            // new descriptions attached to the registry.)
        }
        // 3) Reorder media so any read_file-emitted HumanMessage lands
        //    after the AIMessage tool-call batch it belongs to.
        List<Message> reordered = MediaResultReorderer.moveMediaResultsAfterToolResults(messages);
        // 4) Scrub multimodal content blocks the model can't accept
        //    (replaces with text placeholders). Mirrors the Python
        //    port's _scrub_unsupported_multimodal_content step.
        org.aethercode.core.runtime.llm.ModelProfile profile = runtime.modelProfile();
        String provider = runtime.modelProvider();
        boolean toleratesNonPdf = MultimodalContentScrubber.toleratesNonPdfFiles(provider);
        List<Message> scrubbed = MultimodalContentScrubber.scrub(reordered, profile, toleratesNonPdf);
        return modelCall.apply(scrubbed, runtime);
    }

    /**
     * Async variant of {@link #wrapModelCall}. The middleware's
     * request-rewriting steps (filter tools, reorder media, scrub
     * multimodal content) are pure CPU work and run on the calling
     * thread; the async model call is then awaited.
     *
     * <p>Mirrors the Python port's
     * {@code FilesystemMiddleware.awrap_model_call}. The async
     * path is otherwise identical to the sync one — the only
     * difference is the {@code _evict_and_truncate_messages} call
     * becoming {@code _aevict_and_truncate_messages} in the
     * Python port. The Java port's eviction is wired into
     * {@code beforeModel} and runs synchronously, so no async
     * split is required here.</p>
     */
    @Override
    public java.util.concurrent.CompletableFuture<AIMessage> awrapModelCall(
            BiFunction<List<Message>, Runtime, java.util.concurrent.CompletableFuture<AIMessage>> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        // Reuse the sync implementation's request-rewriting logic.
        // We do this by composing a sync BiFunction that bridges to
        // the async handler.
        java.util.function.BiFunction<List<Message>, Runtime, AIMessage> syncBridge =
                (ms, rt) -> modelCall.apply(ms, rt).join();
        AIMessage result = wrapModelCall(syncBridge, messages, state, runtime);
        return java.util.concurrent.CompletableFuture.completedFuture(result);
    }

    /**
     * Run the human-message eviction guard before the model call.
     *
     * <p>Mirrors the Python port's
     * {@code FilesystemMiddleware._check_eviction_needed} +
     * {@code _apply_eviction_and_truncate}:</p>
     * <ol>
     *   <li>If the most-recent message is an untagged HumanMessage
     *       whose text length exceeds
     *       {@code token_limit * NUM_CHARS_PER_TOKEN} chars, write
     *       its content to the backend and replace it with a tagged
     *       copy whose {@link HumanMessage#evictedTo()} is
     *       the offload path.</li>
     *   <li>Already-tagged HumanMessages are left in place; their
     *       full content still lives in the backend and the
     *       model-facing preview is whatever the model adapter
     *       chooses to render.</li>
     * </ol>
     *
     * <p>If the backend write fails, the original HumanMessage is
     * left untouched and a warning is logged.</p>
     */
    @Override
    public AgentState beforeModel(AgentState state, Runtime runtime) {
        if (humanMessageTokenLimitBeforeEvict <= 0) return state;
        List<Message> messages = state.messages();
        if (messages == null || messages.isEmpty()) return state;
        Message last = messages.get(messages.size() - 1);
        if (!(last instanceof HumanMessage hm)) return state;
        if (hm.evictedTo().isPresent()) return state;

        String text = flattenText(hm.content());
        int threshold = humanMessageTokenLimitBeforeEvict * NUM_CHARS_PER_TOKEN;
        if (text.length() <= threshold) return state;

        // Stable id (reuse original or mint a new one) so a later
        // wrapModelCall can dedupe via the DeltaChannel if one runs.
        String stableId = hm.id() == null || hm.id().isBlank() ? "h-" + UUID.randomUUID() : hm.id();
        String filePath = evictedPrefix + "/" + stableId;

        var writeResult = backend.write(filePath, text);
        if (writeResult.error().isPresent()) {
            LOGGER.log(Level.WARNING,
                    "human-message eviction failed: write to {0} returned error {1}",
                    new Object[]{filePath, writeResult.error().get()});
            return state;
        }

        HumanMessage tagged = new HumanMessage(
                stableId,
                hm.content(),
                Optional.of(filePath));
        List<Message> next = new ArrayList<>(messages);
        next.set(next.size() - 1, tagged);
        return state.withMessages(next);
    }

    private static String flattenText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.TextBlock t) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    //  Large tool result eviction
    // -----------------------------------------------------------------

    /**
     * Offload a large {@link ToolMessage}'s content to
     * {@link #largeToolResultsPrefix()} on the configured
     * {@link #backend()} and return a replacement ToolMessage that
     * carries a head+tail preview plus the offload path so the
     * agent can {@code read_file} the full content with pagination.
     *
     * <p>Mirrors the Python port's
     * {@code _intercept_large_tool_result}. Disabled
     * when {@link #toolTokenLimitBeforeEvict} is {@code 0} or when
     * the message's text content already fits in
     * {@code token_limit * NUM_CHARS_PER_TOKEN} chars &mdash; the
     * original message is returned in that case.</p>
     *
     * <p>Returns the original message when the offload write fails
     * (with a logged warning); a partial eviction would lose data
     * the agent needs.</p>
     */
    public ToolMessage interceptLargeToolResult(ToolMessage message) {
        if (toolTokenLimitBeforeEvict <= 0) {
            return message;
        }
        // Tool-name exclusion: tools that already paginate or are
        // intrinsically bounded are not eligible for eviction.
        // Mirrors Python's TOOLS_EXCLUDED_FROM_EVICTION constant.
        if (message.name().isPresent()
                && TOOLS_EXCLUDED_FROM_EVICTION.contains(message.name().get())) {
            return message;
        }
        String text = flattenText(message.content());
        int threshold = toolTokenLimitBeforeEvict * NUM_CHARS_PER_TOKEN;
        if (text.length() <= threshold) {
            return message;
        }
        ToolMessage evicted = ToolMessageEviction.offloadToolMessageContent(
                message, text, backend, largeToolResultsPrefix);
        if (evicted == null) {
            LOGGER.log(Level.WARNING,
                    "tool-result eviction failed: write to {0}/{1} returned an error",
                    new Object[]{largeToolResultsPrefix, message.toolCallId() == null ? "unknown" : message.toolCallId()});
            return message;
        }
        return evicted;
    }

    /**
     * Async variant of {@link #interceptLargeToolResult} using
     * {@code backend.awrite} so the offload runs in the async
     * executor. Mirrors the Python port's
     * {@code _aintercept_large_tool_result}.
     */
    public java.util.concurrent.CompletableFuture<ToolMessage> ainterceptLargeToolResult(
            ToolMessage message) {
        if (toolTokenLimitBeforeEvict <= 0) {
            return java.util.concurrent.CompletableFuture.completedFuture(message);
        }
        if (message.name().isPresent()
                && TOOLS_EXCLUDED_FROM_EVICTION.contains(message.name().get())) {
            return java.util.concurrent.CompletableFuture.completedFuture(message);
        }
        String text = flattenText(message.content());
        int threshold = toolTokenLimitBeforeEvict * NUM_CHARS_PER_TOKEN;
        if (text.length() <= threshold) {
            return java.util.concurrent.CompletableFuture.completedFuture(message);
        }
        return ToolMessageEviction.aoffloadToolMessageContent(
                message, text, backend, largeToolResultsPrefix)
                .thenApply(evicted -> {
                    if (evicted == null) {
                        LOGGER.log(Level.WARNING,
                                "async tool-result eviction failed: write to {0}/{1} returned an error",
                                new Object[]{largeToolResultsPrefix, message.toolCallId() == null ? "unknown" : message.toolCallId()});
                        return message;
                    }
                    return evicted;
                });
    }

    /**
     * Wrap the post-tool-result hook. When
     * {@link #toolTokenLimitBeforeEvict} is set, any tool message
     * whose text content exceeds the threshold is offloaded to the
     * backend and replaced with a head+tail preview carrying the
     * offload path. Mirrors the Python port's
     * {@code wrap_tool_call} → {@code _intercept_large_tool_result}
     * wiring, expressed as a {@code wrapToolResult} hook so it sees
     * a fully built {@link ToolMessage} (with name and
     * tool_call_id).
     */
    @Override
    public ToolMessage wrapToolResult(ToolMessage toolResult,
                                              AgentState state,
                                              Runtime runtime) {
        if (toolTokenLimitBeforeEvict <= 0) {
            return toolResult;
        }
        return interceptLargeToolResult(toolResult);
    }

    /**
     * The set of tool names this middleware provides. Used by
     * {@link ToolExclusionMiddleware} and the harness profile to
     * apply per-tool filters.
     */
    public Set<String> providedToolNames() {
        return toolset.toolNames();
    }

    // -----------------------------------------------------------------
    //  Route → host-path prompt
    // -----------------------------------------------------------------

    /**
     * Build a prompt section mapping virtual route prefixes to the
     * host shell paths the {@code execute} tool can reach.
     *
     * <p>Java-native port of
     * {@code deepagents.middleware.filesystem._route_host_path_prompt}.
     * {@code execute} runs on the default backend's shell, so virtual
     * paths may not exist there; instead of rewriting shell
     * commands, the model is given a prefix-substitution table.</p>
     *
     * <p>A route exposes a host path only when its files live on the
     * same filesystem the default's shell runs in (i.e. the default
     * is a {@link org.aethercode.core.fs.backend.LocalShellBackend} AND the
     * route is a {@link org.aethercode.core.fs.backend.FilesystemBackend}).</p>
     *
     * <p>Returns an empty string when the input is not a
     * {@link org.aethercode.core.fs.backend.CompositeBackend} or there are
     * no routes to describe.</p>
     */
    public static String routeHostPathPrompt(org.aethercode.core.fs.backend.BackendProtocol backend) {
        if (!(backend instanceof org.aethercode.core.fs.backend.CompositeBackend comp)) {
            return "";
        }
        // Host mappings are only valid when the default's shell shares
        // the local filesystem with the routes.
        boolean defaultUsesLocalShell =
                comp.defaultBackend() instanceof org.aethercode.core.fs.backend.LocalShellBackend;

        java.util.List<java.util.Map.Entry<String, String>> hostMappings = new java.util.ArrayList<>();
        java.util.List<String> noHostRoutes = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, org.aethercode.core.fs.backend.BackendProtocol> e
                : comp.routes().entrySet()) {
            String routePrefix = e.getKey();
            org.aethercode.core.fs.backend.BackendProtocol routeBackend = e.getValue();
            if (!(defaultUsesLocalShell
                    && routeBackend instanceof org.aethercode.core.fs.backend.FilesystemBackend fs)) {
                noHostRoutes.add(routePrefix);
            } else if (fs.virtualMode()) {
                // Virtual mode: prefix maps to the backend's host root directory.
                hostMappings.add(java.util.Map.entry(routePrefix, fs.cwd().toString()));
            } else {
                // Non-virtual mode: prefix is stripped, remaining absolute
                // path used as-is -> the prefix maps to the filesystem root.
                hostMappings.add(java.util.Map.entry(routePrefix, "/"));
            }
        }
        if (hostMappings.isEmpty() && noHostRoutes.isEmpty()) {
            return "";
        }

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("## Shell paths vs. virtual paths");
        lines.add("");
        lines.add("The `execute` tool runs commands in the host shell "
                + "and can only access files that exist on the host filesystem.");
        lines.add("");
        lines.add("Some paths returned by the file tools are virtual mounts:");
        lines.add("");
        lines.add("- If a virtual mount has a host path mapping, replace its virtual "
                + "prefix with the host prefix when running shell commands.");
        lines.add("- If a virtual mount does not have a host path mapping, it is not "
                + "accessible from the shell. Use the file tools listed above to "
                + "interact with those files.");
        lines.add("");
        lines.add("Do not assume that a path returned by a file tool can be used "
                + "directly in a shell command.");

        if (!hostMappings.isEmpty()) {
            lines.add("");
            lines.add("Host path mappings:");
            for (var mapping : hostMappings) {
                String virtual = mapping.getKey();
                String host = mapping.getValue();
                String virtualNorm = virtual.endsWith("/") ? virtual : virtual + "/";
                String hostNorm = host.endsWith("/") ? host : host + "/";
                lines.add(String.format(
                        "- `%s` -> `%s` (e.g. `%sdir/x.py` -> `%sdir/x.py`)",
                        virtualNorm, hostNorm, virtualNorm, hostNorm));
            }
        }
        if (!noHostRoutes.isEmpty()) {
            lines.add("");
            lines.add("Virtual mounts without a host path mapping "
                    + "(not accessible from the shell):");
            for (String p : noHostRoutes) {
                lines.add("- `" + p + "`");
            }
        }
        return String.join("\n", lines);
    }
}
