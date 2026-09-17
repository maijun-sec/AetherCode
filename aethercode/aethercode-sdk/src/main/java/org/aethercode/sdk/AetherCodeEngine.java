package org.aethercode.sdk;

import org.aethercode.core.agent.Subagent;
import org.aethercode.core.app.AppState;
import org.aethercode.core.compact.Compactor;
import org.aethercode.core.cost.CostTracker;
import org.aethercode.core.engine.QueryEngine;
import org.aethercode.core.engine.StreamingToolExecutor;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.transcript.SessionStore;
import org.aethercode.core.transcript.Transcript;
import org.aethercode.engine.springai.SpringAiChatClient;
import org.aethercode.hooks.HookRegistry;
import org.aethercode.hooks.Hooks;
import org.aethercode.permission.ProjectPermissionPolicy;
import org.aethercode.permission.SettingsPermissions;
import org.aethercode.permission.Rule;
import org.aethercode.permission.ToolPermissionPrompter;
import org.aethercode.prompts.SystemPrompt;
import org.aethercode.tools.StandardTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Public SDK entry point. Now that the engine is provided by spring-ai, the
 * constructor's main job is to wire the public API (tools, permissions, hooks,
 * transcript) onto a {@link SpringAiChatClient} and the internal
 * {@link QueryEngine} turn loop. Callers (CLI, TUI, IDEA plugin) only see the
 * {@link #query(String)} entry point and the public types.
 */
public class AetherCodeEngine implements Subagent.SubagentEngine {

    private static final Logger LOG = LoggerFactory.getLogger(AetherCodeEngine.class);

    private final AppState appState;
    private volatile ChatClient chatClient;
    // non-final so we can swap the underlying ProjectPermissionPolicy
    // (or its prompter) when the daemon starts. The default path keeps
    // the field effectively immutable.
    private volatile org.aethercode.core.engine.PermissionPolicy policy;
    // shared per-session skip-confirmation counter. Lives on the
    // engine so the RPC can reach it; the policy consults it on each
    // permission check.
    private final org.aethercode.config.SkipConfirmationRegistry skipConfirmationRegistry;
    // per-session skip-confirmation statistics. Three
    // counters that the daemon tracks so the user can see
    // whether they're using the feature. Incremented on
    // consume (consumed), on setPositive (armed), and on
    // query (promptsTotal). All atomic for thread safety.
    private final java.util.concurrent.atomic.AtomicLong skipStatsConsumed =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong skipStatsArmed =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong skipStatsPrompts =
            new java.util.concurrent.atomic.AtomicLong();
    // per-tool consume count. Map key = tool name, value =
    // atomic counter. Snapshot via skipStatsByTool(). Cleared
    // when the engine shuts down.
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.AtomicLong> skipStatsByTool =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final org.aethercode.prompts.SystemPrompt systemPrompt;
    private volatile String planModeSuffix = "";
    private final QueryEngine queryEngine;
    private final HookRegistry hookRegistry;
    /** the StreamingToolExecutor is now a
     *  field (was previously a local in the constructor)
     *  so {@link #setPermissionMode} and
     *  {@link #swapPolicy} can push the freshly-built
     *  policy down to the executor. Previously, the
     *  executor captured the original policy at
     *  construction time and never saw later swaps,
     *  so picking BYPASS_PERMISSIONS in the dropdown updated
     *  the engine's appState but the streaming path
     *  kept consulting the pre-swap policy. The
     *  executor's policy field is now volatile +
     *  mutable (see StreamingToolExecutor.policy). */
    private final org.aethercode.core.engine.StreamingToolExecutor streamingToolExecutor;
    /** per-phase tool-call budget tracker. May be
     *  null (default, no budget enforcement). The
     *  engine does NOT itself record tool calls into
     *  the tracker — the executor / caller is
     *  responsible for {@code recordToolCall} on
     *  each completed tool execution. The
     *  PhaseBudgetHook consults the tracker on
     *  PRE_TOOL_USE and blocks when the current
     *  phase is over budget. */
    private final org.aethercode.hooks.builtin.PhaseTracker phaseTracker;
    /** multi-session manager. May be null
     *  (default, single-engine path). When set, the
     *  AetherCodeMethods RPCs route by sessionId to
     *  the engine registered for that session.
     *
     *  <p>prior round: relaxed from {@code final} to
     *  {@code volatile} so {@link #setSessionManager}
     *  can install the manager post-construction
     *  (the daemon pattern: the CLI builds the
     *  engine, the daemon wires the manager).
     *  Volatile because the setter is called from
     *  the same thread that uses the manager, but
     *  readers (the {@code sessionManager()}
     *  accessor) may run on the JSON-RPC
     *  dispatcher thread. */
    private volatile org.aethercode.sdk.SessionManager sessionManager;
    private final Compactor compactor;
    private final CostTracker costTracker;
    /** per-session tool-call + state
     *  counters. Reset on {@link #loadSession}
     *  (so a new session in the same engine
     *  gets a clean slate). The {@code summary}
     *  RPC returns this snapshot regardless
     *  of pass/fail. */
    private final SessionStats sessionStats = new SessionStats();
    /** when the TUI last successfully pinged the engine.
     *  Updated by {@link #recordPing()} on every health probe.
     *  Volatile because the TUI ping RPC handler runs on the
     *  JSON-RPC worker thread while the TUI may also read it
     *  via {@link #lastPingAtMs()}. 0 = never pinged (fresh
     *  engine / just started). */
    private volatile long lastPingAtMs = 0;
    /** per-process metrics collector. Owned by the engine; never
     *  reset during the engine's lifetime. */
    private final org.aethercode.core.metrics.MetricsCollector metrics;
    /** lightweight span recorder (root-only first pass). Owned
     *  by the engine; never reset during the engine's lifetime. */
    private final org.aethercode.core.trace.TraceRecorder traces;
    /** per-query memory recall. Used to pick the memory files
     *  that should be injected into the system prompt for the current
     *  turn. Set via {@code Builder.memoryRecall(...)}; falls back to
     *  a default instance using the same chat client if not provided. */
    private final org.aethercode.memory.MemoryRecall memoryRecall;
    /** layered memory lifecycle orchestrator. Threads
     *  every memory read/write through {@link org.aethercode.memory.MemoryLifecycle}
     *  so the experience store, audit log, and forgetting policy are
     *  continuously updated (not just statically configured). Set via
     *  {@code Builder.memoryLifecycle(...)}; falls back to a disabled
     *  no-op when not provided (so existing test/CLI paths still work).
     * relaxed from {@code final} to {@code volatile} so the
     *  daemon can install a real one after the engine is built
     *  (the daemon builds the engine before the memory store is
     *  ready, so it must swap the lifecycle in later). */
    private volatile org.aethercode.memory.MemoryLifecycle memoryLifecycle;
    /** optional multi-session store. When wired, every
     *  {@code appState.appendMessage} is mirrored to a per-session
     *  JSONL file under the store's directory, and the engine
     *  supports {@link #loadSession}, {@link #createSession},
     *  {@link #deleteSession}, {@link #listSessions}. The CLI has
     *  used this since prior round; the daemon (R105's localStorage
     *  fallback) starts using it in R106. May be {@code null} for
     *  unit tests that don't need persistence.
     *
     *  <p>R148: relaxed from {@code final} to {@code volatile}
     *  so {@link #setSessionStore} can install the store
     *  post-construction (the daemon pattern: the CLI
     *  builds the engine, the daemon wires the store).
     *  Volatile because the setter is called from the
     *  setup thread, but readers (the
     *  {@link #sessionStore()} accessor + the
     *  {@code createSession} / {@code loadSession} / etc.
     *  RPCs) may run on the JSON-RPC dispatcher thread.
     *  The setter is idempotent: a second call is a
     *  no-op so a misbehaving caller can't accidentally
     *  swap stores out from under a running session. */
    private volatile org.aethercode.core.transcript.SessionStore sessionStore;
    /** skill registry. Reads {@code ~/.aethercode/skills/<n>/SKILL.md}
     *  (R210 — previously {@code ~/.minimax/skills/}; the rename
     *  aligns the user-tier name with the project-tier
     *  {@code <cwd>/.aethercode/skills/} and with the existing
     *  {@code ~/.aethercode/mcp.json} location) and
     *  {@code <cwd>/.aethercode/skills/<n>/SKILL.md}; surfaces a
     *  list / body to the daemon's RPCs and a system-prompt
     *  preamble to {@link #queryInChildSession}. May be {@code null}
     *  for unit tests that don't need skill discovery. */
    private final org.aethercode.core.skill.SkillRegistry skillRegistry;
    /** prior round: unified file-watcher for skills / MCP / agents.
     *  Optional — unit tests and the stdio daemon skip it. */
    private org.aethercode.core.registry.RegistryReloadService registryReloadService;
    /** stateful MCP manager. Owns the live client handles
     *  for every {@code mcpServers} entry so a {@code mcp.json}
     *  edit can close old processes / sockets and start new
     *  ones without bouncing the daemon. Set by
     *  {@code Main.buildEngineForSession} on startup; called
     *  by the file watcher on every change. */
    private org.aethercode.mcp.McpManager mcpManager;
    /** agent registry. Reads {@code ~/.minimax/agents/<n>/agent.md};
     *  the workflow executor's {@code kind: agent} step uses this
     *  to look up the named agent's body and inject it as the
     *  child session's system-prompt context. May be {@code null}
     *  for unit tests. */
    private final org.aethercode.core.agent.AgentRegistry agentRegistry;
    /** concurrency controller. Bounds the engine's
     *  in-flight queries, tool subprocesses, and workflow
     *  parallel branches, and monitors JVM memory to apply
     *  backpressure when the host is under stress. May be
     *  {@code null} for unit tests. */
    private final org.aethercode.core.concurrency.ConcurrencyController concurrencyController;
    /** the JSONL transcript currently being written to. Replaced
     *  by {@link #loadSession} and {@link #createSession}. The field
     *  is mutable because switching sessions needs to point to a
     *  different file; reads on the engine's turn loop are
     *  single-threaded (the QueryEngine is serial), so the volatile
     *  + synchronized listener gives us a consistent write
     *  ordering. */
    private volatile org.aethercode.core.transcript.Transcript currentTranscript;
    /** optional fan-out for every transcript mutation.
     *  When non-null, the engine broadcasts two notification
     *  payloads via this consumer:
     *  <ul>
     *    <li>{@code {action:"append", sessionId, message}} on
     *        every {@code appendMessage} call (one per user
     *        input, one per assistant turn, one per tool
     *        result, plus occasional system notes)</li>
     *    <li>{@code {action:"sync", sessionId, messages[]}}
     *        after {@link #loadSession} replaces the
     *        in-memory transcript (the renderer's
     *        {@code transcript_event} subscriber uses this
     *        to reset its {@code messages} array when the
     *        user switches sessions)</li>
     *  </ul>
     *  Wired by {@code DaemonRunner.runHttp} to the
     *  {@code HttpJsonRpcServer.broadcast} entry point so a
     *  Tauri / CLI / TUI client can follow the session
     *  transcript in real time without polling. {@code null}
     *  in unit-test mode and in stdio daemon mode (where
     *  the parent process owns the transcript via
     *  {@code getState} polling). */
    private volatile java.util.function.Consumer<java.util.Map<String, Object>> transcriptPush;
    /** optional fan-out for task lifecycle
     *  changes. When non-null, the engine broadcasts a
     *  notification payload via this consumer on every
     *  {@link org.aethercode.tasks.TaskRegistry} change
     *  (create + status transition). The shape is
     *  {@code {action:"create"|"update", task}} where
     *  {@code task} is the full
     *  {@link org.aethercode.tasks.Task#toMap()}
     *  serialisation. Wired by
     *  {@code DaemonRunner.runHttp} to
     *  {@code HttpJsonRpcServer::broadcast} so a Tauri /
     *  TUI client can keep a Kanban board in sync with
     *  the engine's view of the task graph without
     *  polling. {@code null} in unit-test mode and in
     *  stdio daemon mode (where the parent process owns
     *  the transcript via {@code getState} polling). */
    private volatile java.util.function.Consumer<java.util.Map<String, Object>> taskPush;
    /** live-reload watcher for the rules
     *  directories. Created in the constructor and
     *  left running for the lifetime of the engine
     *  (the daemon thread is a daemon, so it does not
     *  keep the JVM alive). When a file under the
     *  rules directories changes, the watcher fires
     *  the listener registered below, which
     *  re-renders the system prompt and pushes it
     *  into {@code QueryEngine}. The watcher is
     *  package-private so tests can introspect /
     *  close it without going through reflection. */
    private org.aethercode.prompts.RulesWatcher rulesWatcher;
    // live-reload the project config (matrix + workflow
    // mode) when the user edits .aethercode/config.json. Mirrors
    // rulesWatcher for the rules directory.
    private org.aethercode.config.ConfigWatcher configWatcher;
    // remembered skip-consumed listener so a swapPolicy
    // (config reload) re-applies it to the new policy.
    private volatile java.util.function.IntConsumer skipConsumedListener = null;
    // remembered skip-low listener so a swapPolicy
    // (config reload) re-applies it. Receives (sessionId,
    // newRemaining) when the counter crosses DOWN through
    // the waterline. The protocol layer wires this to the
    // NOTIFY_SKIP_LOW JSON-RPC notification.
    private volatile java.util.function.BiConsumer<String, Integer> skipLowListener = null;
    // last-skip-low snapshot. Surfaced via
    // {@link #lastSkipLow} and through {@code getState}
    // so a client that just connected can show "skip
    // running low" even if it missed the live
    // notification. All three are atomic for thread
    // safety; reads may be stale by a few microseconds
    // which is fine for UI signals.
    private final java.util.concurrent.atomic.AtomicReference<String> lastSkipLowSessionId =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger lastSkipLowRemaining =
            new java.util.concurrent.atomic.AtomicInteger(-1);
    private final java.util.concurrent.atomic.AtomicLong lastSkipLowAtMs =
            new java.util.concurrent.atomic.AtomicLong(0);

    private AetherCodeEngine(Builder b) {
        // prefer the `aethercode.cwd` system property when the caller
        // didn't pass an explicit `cwd` to the builder. The TUI launches
        // the daemon with `-Daethercode.cwd=<user's --cwd>`; the daemon's
        // Main then forwards `Path.of("").toAbsolutePath()` (the JVM cwd
        // = the directory the user ran `java -jar` from, NOT their --cwd)
        // to the engine. Without this fallback the engine would store
        // the JVM cwd and `FileWriteTool` would reject any path the model
        // generated relative to the user's intended cwd.
        String propCwd = System.getProperty("aethercode.cwd");
        boolean cwdIsDefault = b.cwd == null
                || b.cwd.toString().isEmpty()
                || b.cwd.equals(Path.of("").toAbsolutePath());
        if (cwdIsDefault && propCwd != null && !propCwd.isBlank()) {
            b.cwd(Path.of(propCwd).toAbsolutePath().normalize());
        } else {
            // Normalize so the engine's canonical cwd is comparable
            // cross-platform (e.g. Windows drive-letter casing).
            b.cwd(b.cwd.toAbsolutePath().normalize());
        }
        // publish the session's cwd to a process-level env var so tools
        // that need to validate relative paths (FileWriteTool, FileEditTool)
        // can use it as a fallback when the user passes a relative path. The
        // tool default still uses the JVM cwd, but with this var the engine
        // wins. The env var is per-process and only used by our own tools.
        //
        // STOP writing the `aethercode.cwd` SYSTEM PROPERTY here.
        // The system property is a JVM-wide singleton, so writing it
        // on every engine boot LEAKS state across sessions in a
        // multi-client setup (the user runs 2 sessions in one daemon
        // for different projects; the second session's `setCwd`
        // overwrites the first's system property, breaking any
        // out-of-band reader). The legacy code was wrong because
        // it conflated "this engine's cwd" with "this daemon's cwd"
        // — they're not the same thing in a multi-session world.
        // The session's AppState.cwd is the source of truth; tools
        // that need a fallback (FileWriteTool / FileReadTool) read it
        // from CallContext.extras per call. The env var below
        // (AETHERCODE_BASH_CWD) IS per-process but is set by
        // AppState.cwd setter, so it always reflects the active
        // session's cwd at the time of the most recent setCwd.
        // The system property is removed entirely — anyone reading
        // it should migrate to the env var or to AppState.cwd via
        // the engine reference.
        //
        // try { System.setProperty("aethercode.cwd", ...); } catch (...) {} // deleted in R185
        this.appState = new AppState(b.sessionId, b.cwd);
        // patch Spring AI's static OBJECT_MAPPER so empty
        // `finish_reason: ""` on streaming chunks deserializes to
        // null instead of throwing "Cannot deserialize value of
        // type ... from String ''". The MiniMax M3 model (and
        // other OpenAI-compatible backends) emit empty string
        // for finish_reason on every intermediate chunk; without
        // this patch the engine silently drops the model response
        // and the user sees a "white screen" / "no response"
        // symptom. Idempotent + best-effort: a no-op if the
        // Spring AI version doesn't expose OBJECT_MAPPER.
        org.aethercode.core.aicompat.SpringAiEnumCompat.patchOnce();
        // surface the context window on AppState so the TUI / CLI can
        // echo it. The actual AutoCompact wiring is R18 (the existing
        // AutoCompact class does not implement the core Compactor interface).
        if (b.contextWindow > 0) {
            this.appState.contextWindow(b.contextWindow);
        }
        // if a session store is wired, prefer the
        // persisted mode over the build-time default. The
        // builder's mode is the fall-back for the first
        // engine boot of a new session.
        org.aethercode.core.permission.PermissionMode effectiveMode = b.permissionMode;
        // AETHERCODE_DEFAULT_PERMISSION_MODE env var wins over
        // every other source (builder default, persisted mode, project
        // config). This is the opt-in for "I want the engine to
        // genuinely interrupt me before every non-read-only tool call
        // for this whole run" — the user sets it once at launch and
        // every session honours it. Recognised values are the
        // PermissionMode enum names (case-insensitive). Invalid
        // values log a warning and fall through to the normal
        // builder default.
        String envMode = System.getenv("AETHERCODE_DEFAULT_PERMISSION_MODE");
        if (envMode != null && !envMode.isBlank()) {
            String norm = envMode.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            try {
                org.aethercode.core.permission.PermissionMode parsed =
                        org.aethercode.core.permission.PermissionMode.valueOf(norm);
                effectiveMode = parsed;
                java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                        .info(() -> "R163: AETHERCODE_DEFAULT_PERMISSION_MODE="
                                + envMode + " (parsed " + parsed + ") overrides default");
            } catch (IllegalArgumentException ex) {
                java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                        .warning("R163: AETHERCODE_DEFAULT_PERMISSION_MODE=" + envMode
                                + " is not a valid PermissionMode name; ignored. "
                                + "Valid: DEFAULT, ASK_BEFORE_TOOL, ACCEPT_EDITS, "
                                + "ACCEPT_TASK, BYPASS_PERMISSIONS, PLAN, AUTO_READ_ONLY");
            }
        }
        if (b.sessionStore != null) {
            org.aethercode.core.permission.PermissionMode persisted =
                    org.aethercode.config.PermissionModePersistence.loadFromDisk(
                            b.sessionStore.dir(), b.sessionId);
            if (persisted != null) {
                effectiveMode = persisted;
                java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                        .info(() -> "R105: restored permission mode from disk for session "
                                + b.sessionId + ": " + persisted);
            }
        }
        // AETHERCODE_DEFAULT_PERMISSION_MODE wins over the
        // suggester / .aethercode/config.json override. We do this
        // after the env-var / persisted checks so the precedence is:
        //   1. AETHERCODE_DEFAULT_PERMISSION_MODE env var (set at launch)
        //   2. Persisted mode (R105, per-session .aethercode/...)
        //   3. .aethercode/config.json::defaultPermissionMode (prior round)
        //   4. Builder's permissionMode (the legacy entry point)
        //   5. PermissionModeSuggester (CI → ACCEPT_TASK,
        //      src+tests → ACCEPT_EDITS, otherwise ASK_BEFORE_TOOL)
        //
        // The env-var / persisted branches above already short-circuit
        // by setting effectiveMode. Here we only fall through when
        // neither was set, in which case we still honour the
        // per-project defaultPermissionMode if it is present.
        if (effectiveMode == b.permissionMode) {
            // No env var and no persisted mode — let the project
            // config override the builder default. The suggester
            // still runs (and may upgrade the recommendation for CI
            // / mature projects) but the user can pin the mode with
            // a single line in .aethercode/config.json.
            try {
                org.aethercode.config.AetherCodeConfig cfg =
                        org.aethercode.config.ConfigEngine.loadFromProjectRoot(b.cwd);
                if (cfg != null && cfg.defaultPermissionMode != null
                        && !cfg.defaultPermissionMode.isBlank()) {
                    String norm = cfg.defaultPermissionMode.trim().toUpperCase(
                            java.util.Locale.ROOT).replace('-', '_');
                    try {
                        org.aethercode.core.permission.PermissionMode parsed =
                                org.aethercode.core.permission.PermissionMode.valueOf(norm);
                        effectiveMode = parsed;
                        java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                                .info(() -> "R163: .aethercode/config.json::defaultPermissionMode="
                                        + cfg.defaultPermissionMode + " (parsed " + parsed + ")");
                    } catch (IllegalArgumentException ex) {
                        java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                                .warning("R163: .aethercode/config.json::defaultPermissionMode="
                                        + cfg.defaultPermissionMode + " is not a valid PermissionMode "
                                        + "name; ignored. Valid: DEFAULT, ASK_BEFORE_TOOL, ACCEPT_EDITS, "
                                        + "ACCEPT_TASK, BYPASS_PERMISSIONS, PLAN, AUTO_READ_ONLY");
                    }
                }
            } catch (Exception ex) {
                // A missing / unparseable config is fine — the
                // suggester and builder default still apply.
                java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                        .fine("R163: could not load .aethercode/config.json: " + ex.getMessage());
            }
        }
        this.appState.permissionMode(effectiveMode);
        this.appState.mainLoopModel(b.model);
        this.appState.toolPool().addAll(b.tools);
        if (b.transcript != null) {
            for (Message m : b.transcript.messages()) appState.appendMessage(m);
        }
        // wire the optional SessionStore. When present, the
        // current session's transcript is loaded from the store
        // (the builder's `transcript` is treated as a hint — if
        // the store has a different file, the store wins), and a
        // listener is installed that mirrors every appendMessage
        // to the store. The listener is attached AFTER the initial
        // transcript load so we don't re-write the same messages.
        this.sessionStore = b.sessionStore;
        if (this.sessionStore != null) {
            attachSessionStore();
        }
        // build the SkillRegistry. The user-tier dir is
        // resolved from {@code MAVIS_HOME} / {@code MINIMAX_HOME} /
        // {@code ~/.minimax}; the project-tier dir is
        // {@code <cwd>/.aethercode/skills}. Both lists are passed
        // verbatim — the registry tolerates missing dirs. A
        // {@code null} builder value disables skill discovery
        // (unit-test mode).
        if (b.skillRegistry != null) {
            this.skillRegistry = b.skillRegistry;
        } else if (b.skillDirs != null) {
            // lazy body load. The CLI / daemon build
            // with lazy=true so the always-on footprint of
            // 50+ skills is just their metadata. The body
            // is parsed on demand by getBody(name). Unit
            // tests can opt out via the 4-arg constructor.
            this.skillRegistry = new org.aethercode.core.skill.SkillRegistry(
                    b.skillProjectDirs == null ? List.of() : b.skillProjectDirs,
                    b.skillDirs,
                    b.skillReloadInterval,
                    /* lazy */ true);
        } else {
            this.skillRegistry = null;
        }
        // build the AgentRegistry from the same Mavis
        // directory tree. {@code null} disables agent discovery.
        if (b.agentRegistry != null) {
            this.agentRegistry = b.agentRegistry;
        } else if (b.agentsDir != null) {
            this.agentRegistry = new org.aethercode.core.agent.AgentRegistry(
                    b.agentsDir, b.agentReloadInterval);
        } else {
            this.agentRegistry = null;
        }
        // build the ConcurrencyController. The builder
        // may supply a pre-built instance (tests) or a profile
        // name. When neither is given, the controller defaults
        // to {@code NORMAL} (1 query, 4 tools, 2 branches). The
        // controller is intentionally NOT null when the engine
        // is daemon-mode — the desktop relies on it for the
        // StatusBar's memory badge and throttle pill.
        if (b.concurrencyController != null) {
            this.concurrencyController = b.concurrencyController;
        } else {
            org.aethercode.core.concurrency.ConcurrencyController c =
                    new org.aethercode.core.concurrency.ConcurrencyController(
                            b.concurrencyThrottlePct, b.concurrencyBackpressurePct, b.concurrencyMonitorMs);
            if (b.concurrencyProfileName != null) {
                try { c.setProfileByName(b.concurrencyProfileName); }
                catch (IllegalArgumentException ignored) {
                    LOG.warn("unknown concurrency profile '{}', using default NORMAL", b.concurrencyProfileName);
                }
            }
            this.concurrencyController = c;
        }
        // single chat client. spring-ai handles the wire protocol + tool
        // calling; we expose the model via our ChatClient interface.
        // when the builder carries a provider spec (the new
        // multi-provider path), build the client from that. The
        // legacy path (no spec) uses the explicit apiKey / baseUrl
        // on the builder — preserved so the CLI's --api-key and
        // --base-url flags still work.
        if (b.provider != null) {
            // Resolve the spec's apiKey from the env
            // var the spec declares (apiKeyEnv). The
            // legacy --api-key flag overrides when
            // both are set (so a quick CLI override
            // beats the providers.yaml).
            org.aethercode.core.providers.ProviderSpec spec = b.provider;
            if (b.apiKey == null || b.apiKey.isBlank()) {
                String envKey = spec.apiKey();
                if (envKey == null || envKey.isBlank()) {
                    throw new IllegalStateException(
                            "provider " + spec.name() + " requires env var "
                                    + spec.apiKeyEnv() + " (unset). "
                                    + "Set the env var or pass --api-key.");
                }
            }
            String modelId = b.model != null && !b.model.isBlank()
                    ? b.model : spec.defaultModel();
            this.chatClient = SpringAiChatClient.forProvider(
                    spec, modelId);
            LOG.info("chat client wired: provider={} model={}", spec.name(), modelId);
        } else {
            ChatClient.Options opts = b.options;
            if (b.apiKey != null) opts = opts.withApiKey(b.apiKey);
            this.chatClient = new SpringAiChatClient(b.model, opts);
        }
        // hand the AppState to the chat client so tools it dispatches
        // (via spring-ai's internal loop) can publish session state through
        // the CallContext — most importantly, TodoWriteTool can update the
        // in-session todo list which the TUI / CLI / hooks subscribe to.
        if (this.chatClient instanceof org.aethercode.engine.springai.SpringAiChatClient sac) {
            sac.appState(this.appState);
            // also pass the engine itself (we implement
            // SubagentEngine) so the spring-ai path can spawn
            // multi-step subagents.
            sac.subagentEngine(this);
        }
        this.costTracker = b.costTracker == null ? new CostTracker() : b.costTracker;
        // every engine has its own metrics collector. We never
        // share counters across engines (per-process isolation).
        this.metrics = new org.aethercode.core.metrics.MetricsCollector();
        // every engine has its own trace recorder. Spans are
        // bounded; old entries fall off the deque when the cap is hit.
        this.traces = new org.aethercode.core.trace.TraceRecorder();
        SettingsPermissions perms = b.permissions == null ? SettingsPermissions.empty() : b.permissions;
        // merge any project- or user-scope rules persisted on
        // disk into the in-memory allow list before constructing
        // the policy. The static `b.permissions` (e.g. from
        // settings.json) wins on conflict because we append the
        // persisted rules AFTER — but in practice a user adding a
        // rule via the TUI just wants it to be effective, so this
        // order is what they expect.
        try {
            mergePersistedRules(perms);
        } catch (Exception e) {
            // Best-effort — if the file is malformed or the IO
            // fails, the engine still works without persisted rules.
            // We just log a warning so the operator can investigate.
            java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                    .warning("R86 failed to merge persisted rules: " + e);
        }
        // build the policy with the effective (possibly
        // restored) mode so the matrix and the policy agree.
        this.policy = b.permissionMatrix != null
                ? new org.aethercode.permission.MatrixPermissionPolicy(
                        b.permissionMatrix, perms, effectiveMode, b.prompter, b.cwd)
                : new ProjectPermissionPolicy(perms, effectiveMode, b.prompter);
        // instantiate the persistent registry. If a
        // sessionStore was wired (prior round) we use its dir as
        // the persistence root so the counter survives
        // engine restarts; otherwise the in-memory-only
        // default applies. We need to do this BEFORE the
        // applyDefault() call below so the file-backed
        // default also gets persisted.
        java.nio.file.Path persistRoot = b.sessionStore == null ? null : b.sessionStore.dir();
        this.skipConfirmationRegistry = new org.aethercode.config.SkipConfirmationRegistry(persistRoot);
        if (persistRoot != null) {
            // load any persisted counter for this
            // session. The file is <sessionsDir>/<sessionId>/
            // skip-confirmation.json. Missing / malformed ->
            // 0, no exception.
            this.skipConfirmationRegistry.loadFromDisk(b.sessionId);
        }
        // wire the shared skip-confirmation registry into the
        // policy if it is a MatrixPermissionPolicy. The RPC
        // setSkipConfirmation updates this registry; the policy
        // consults it on each permission check to short-circuit
        // ASK -> ALLOW while rounds > 0.
        if (this.policy instanceof org.aethercode.permission.MatrixPermissionPolicy mpp) {
            mpp.setSkipConfirmationRegistry(this.skipConfirmationRegistry);
            // Apply the project's default skip count from
            // .aethercode/config.json (skipConfirmation or
            // skipConfirmationRounds).
            org.aethercode.config.AetherCodeConfig cfg =
                    org.aethercode.config.ConfigEngine.loadFromProjectRoot(b.cwd);
            this.skipConfirmationRegistry.applyDefault(b.sessionId, cfg);
            // when the policy consumes a skip-round, fire the
            // skip-consumed listener. The engine wires this to a
            // JSON-RPC notification in the protocol layer. The
            // listener is set later via setSkipConsumedListener()
            // (after the engine is constructed and the protocol
            // dispatcher is wired). Default is a no-op.
            // wrap so a future setSkipConsumedListener()
            // read waterline from config BEFORE the
            // listener wiring so the policy's waterline
            // matches what the engine will use.
            org.aethercode.config.AetherCodeConfig lowCfg =
                    org.aethercode.config.ConfigEngine.loadFromProjectRoot(b.cwd);
            mpp.setLowWaterline(lowCfg.skipLowWaterline);
            // install the engine's listener wrappers on
            // the policy in one place. The wrappers (a) update
            // engine-level state and (b) forward to whatever
            // user-installed listener is in the corresponding
            // engine field. swapPolicy() also calls this so
            // every listener survives a config reload.
            installPolicyListeners(mpp);
        }
        this.systemPrompt = b.systemPrompt != null ? b.systemPrompt : defaultSystemPrompt(b);
        this.hookRegistry = b.hookRegistry == null ? new HookRegistry() : b.hookRegistry;
        this.phaseTracker = b.phaseTracker;
        this.sessionManager = b.sessionManager;
        // compute the permission-mode suggestion at
        // construction. The suggester reads the project
        // root, so this is fast (just stat a handful of
        // paths). A failure here logs a warning and
        // leaves the suggestion as null — we don't fail
        // construction over a missing project root.
        recomputePermissionModeSuggestion(b.cwd);
        // when a phase tracker is supplied, register
        // the PhaseBudgetHook into the registry. The hook
        // is a no-op when no tool calls have been recorded
        // (the per-phase counters are all 0, well under
        // the default cap).
        if (this.phaseTracker != null) {
            registerBuiltinHookIfAbsent(this.hookRegistry,
                    new org.aethercode.hooks.builtin.PhaseBudgetHook(this.phaseTracker));
        }
        // if the caller set a contextWindow and didn't supply a custom
        // compactor, build an AutoCompact wrapped in AutoCompactAdapter so
        // the engine's core Compactor interface is satisfied. The adapter
        // bridges AutoCompact's Result-returning compact() to the
        // List<Message> contract that Compactor demands. We resolve the
        // compactor into a local first because `compactor` is a final
        // field and the compiler rejects the two-step assignment pattern
        // (this.compactor = b.compactor; if (null) this.compactor = ...).
        Compactor resolvedCompactor = b.compactor;
        if (b.contextWindow > 0 && resolvedCompactor == null) {
            int ctx = b.contextWindow;
            // R136.4: bump buffer / maxIn to scale with the
            // 1M default. Old formula was 5% of window for
            // buffer (13K at 200K) and 40% of window for
            // maxIn (80K at 200K). The new formula keeps the
            // same ratios but floors them higher so 1M
            // compaction has room to work.
            int buffer = Math.max(64_000, ctx / 16);    // >= 6.25% of window
            int maxIn = Math.max(900_000, ctx * 9 / 10); // <= 90% of window
            // default compactor is now the StructuredCompactor
            // (7-section LLM-driven summary) wrapped in a
            // SlidingWindowCompactor. The 7 sections — Goal /
            // R136.5: prefer the 8-section StructuredCompactor8
            // (Claude 7 + OpenCode 1: Active Constraints). For
            // million-token models the extra section preserves
            // detector mode / auto-approve state / denylist
            // across compaction boundaries. Falls back to the
            // 7-section StructuredCompactor if the caller
            // explicitly sets AETHERCODE_COMPACTOR=v7.
            String compactorVersion = System.getenv("AETHERCODE_COMPACTOR");
            boolean useV8 = !"v7".equalsIgnoreCase(compactorVersion);
            org.aethercode.core.compact.Compactor primary;
            if (useV8) {
                primary = new org.aethercode.compact.StructuredCompactor8(
                        this.chatClient, ctx, buffer, maxIn);
            } else {
                primary = new org.aethercode.compact.StructuredCompactor(
                        this.chatClient, ctx, buffer, maxIn);
            }
            // Keep the SlidingWindowCompactor chain for tail
            // preservation (recent turns verbatim, head
            // summarised).
            org.aethercode.compact.SlidingWindowCompactor sw =
                    new org.aethercode.compact.SlidingWindowCompactor(
                            primary,
                            ctx, buffer);
            resolvedCompactor = sw;
        }
        this.compactor = resolvedCompactor;
        // build the MemoryRecall. Use the caller's instance if
        // they passed one; otherwise default to a no-side-client instance
        // (lexical-only recall — the side-query LLM pass is skipped).
        this.memoryRecall = b.memoryRecall != null
                ? b.memoryRecall
                : new org.aethercode.memory.MemoryRecall(this.chatClient);
        // build the MemoryLifecycle. Default to a disabled
        // no-op so existing test/CLI paths that don't pass a
        // lifecycle still work. The daemon wires the real one
        // either via {@code builder.memoryLifecycle(...)} or via
        // the post-construction setter {@link #setMemoryLifecycle}.
        this.memoryLifecycle = b.memoryLifecycle != null
                ? b.memoryLifecycle
                : new org.aethercode.memory.MemoryLifecycle(
                        "default", "default", null, null, null,
                        new org.aethercode.memory.MemoryLifecycle.Config(
                                false, 60000L, 100, 2000, 1500, 1, false, false));
        // the executor is now a field (was previously a local
        // in the constructor) so setPermissionMode and swapPolicy
        // can push the freshly-built policy down to the
        // executor. Previously, the executor captured the
        // original policy at construction time and never
        // saw later swaps, so picking BYPASS_PERMISSIONS in the
        // dropdown updated the engine's appState but
        // the streaming path kept consulting the
        // pre-swap policy. The fix is to route every
        // policy swap through this.streamingToolExecutor.
        this.streamingToolExecutor =
                new org.aethercode.core.engine.StreamingToolExecutor(policy, 8);
        // pass the chat client into the executor so tools like
        // AgentTool can spawn subagents from inside the engine path.
        this.streamingToolExecutor.withChatClient(this.chatClient);
        // pass the engine itself (it implements SubagentEngine)
        // so multi-step AgentTool can re-enter the full engine loop.
        this.streamingToolExecutor.withSubagentEngine(this);
        // install a working-memory supplier so the wm_* tools
        // can find the current per-query buffer. The supplier is
        // looked up on every call (the buffer changes per query).
        // We capture `this` (the engine) so the supplier reads the
        // current lifecycle at call time — the lifecycle may be
        // installed post-construction via setMemoryLifecycle (the
        // daemon path). When the lifecycle is null or the buffer
        // is empty, the supplier returns Optional.empty() and the
        // wm_* tools gracefully report "no buffer active".
        this.streamingToolExecutor.withWorkingMemorySupplier(() -> {
            org.aethercode.memory.MemoryLifecycle lc = this.memoryLifecycle;
            if (lc == null) return java.util.Optional.empty();
            return lc.currentBuffer().map(buf -> (Object) buf);
        });
        // register the built-in safety hooks on the
        // registry we just stored. We use a class-keyed
        // dedup so the same hook isn't added twice if the
        // builder already registered a built-in (e.g. the
        // user added their own WriteExistingFileGuardHook).
        registerBuiltinHookIfAbsent(this.hookRegistry, new org.aethercode.hooks.builtin.WriteExistingFileGuardHook(
                b.cwd == null ? null : b.cwd.toAbsolutePath().normalize()));
        registerBuiltinHookIfAbsent(this.hookRegistry, new org.aethercode.hooks.builtin.EditErrorRecoveryHook());
        // Always wire the registry into the executor —
        // Previously, the code only wired it when the user
        // passed a registry, so the engine-created
        // registry never fired any hooks. R89 fixes this.
        this.streamingToolExecutor.withPreHook(Hooks.asPreBridge(this.hookRegistry));
        this.streamingToolExecutor.withPostHook(Hooks.asPostBridge(this.hookRegistry));
        // wire the per-phase tool-call observer so
        // the budget actually fires. We install a
        // tracker-backed observer only when the engine
        // has a phase tracker; otherwise we install a
        // no-op so the executor's call site stays
        // branch-free. The observer delegates to
        // PhaseTracker.recordToolCall which atomically
        // bumps the current phase's counters and
        // (prior round) isOverBudget() picks up the change
        // for the next PRE_TOOL_USE gate.
        this.streamingToolExecutor.withToolCallObserver(this::onToolCallObserved);
        this.queryEngine = new QueryEngine(
                appState, chatClient, policy, systemPrompt.render(),
                null, this.metrics, this.costTracker, this.streamingToolExecutor, compactor, null);
        // install the onUserPrompt hook so "no confirmation needed
        // for next N rounds" patterns in the prompt are auto-detected.
        // The hook reads the prompt, calls SkipConfirmationDetector,
        // and arms the skip registry for the active session.
        this.queryEngine.setOnUserPrompt(userInput -> {
            // count every user prompt, including ones that
            // don't arm a skip. The stat shows adoption
            // (consumed / promptsTotal), not raw usage.
            skipStatsPrompts.incrementAndGet();
            int rounds = org.aethercode.config.SkipConfirmationDetector.detect(userInput);
            if (rounds > 0) {
                skipConfirmationRegistry.set(appState.sessionId(), rounds);
                skipStatsArmed.incrementAndGet();
                java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                        .info(() -> "R98: auto-armed skip-confirmation for "
                                + rounds + " rounds on session " + appState.sessionId());
            }
        });
        // per-query turn cap — bounds runaway tool loops. -1 / 0 disables.
        if (b.maxTurnsPerQuery > 0) {
            this.queryEngine.setMaxTurnsPerQuery(b.maxTurnsPerQuery);
        }
        // loop detector (sliding window). 0 / negative disables.
        if (b.loopDetectWindow > 0 && b.loopDetectThreshold > 0) {
            this.queryEngine.setLoopDetector(b.loopDetectWindow, b.loopDetectThreshold);
        }
        // R136.4: pipe the context window through to the
        // engine so pickLoopDetector() can auto-select
        // forComplexTask vs forMaxContext for 1M-context
        // models. Wire the env-var-forced detector mode
        // too (set by DaemonRunner from AETHERCODE_LOOP_DETECTOR).
        if (b.contextWindow > 0) {
            this.queryEngine.setContextWindow(b.contextWindow);
        }
        String forcedMode = System.getenv("AETHERCODE_LOOP_DETECTOR");
        if (forcedMode != null && !forcedMode.isBlank()) {
            this.queryEngine.setForcedDetectorMode(forcedMode);
        }
        // live-reload for the rules directories.
        // The watcher is a daemon thread, so it does
        // not keep the JVM alive — the engine has no
        // explicit close() path today, and the JVM
        // shutdown will reap the watcher naturally.
        // The listener re-renders the prompt and
        // pushes the new value into the QueryEngine;
        // the next query picks it up.
        this.rulesWatcher = org.aethercode.prompts.RulesWatcher.start(
                b.cwd, deriveUserHomeForWatcher(), b.role);
        if (this.rulesWatcher != null) {
            final String[] lastFired = { "" };
            this.rulesWatcher.onChange(ev -> {
                // Skip the same-event flood: if the
                // listener already fired for this path
                // in the last second, don't bother
                // re-rendering (the prompt text would
                // be byte-identical). This is a
                // best-effort short-circuit; the
                // loader's debounce already coalesces
                // close-in-time events, so this only
                // fires for "long after" repeats that
                // somehow slip through.
                String key = ev.path() + ":" + ev.atMs() / 1000;
                if (key.equals(lastFired[0])) return;
                lastFired[0] = key;
                try {
                    String newPrompt = renderDefaultSystemPrompt(b);
                    this.queryEngine.setSystemPrompt(newPrompt);
                } catch (Exception ex) {
                    LOG.warn("prior round: rules reload failed: {}", ex.getMessage());
                }
            });
        }
        // live-reload .aethercode/config.json. On any
        // change (create / modify / delete) we re-read the
        // config, swap the policy's matrix if it changed, and
        // re-render the system prompt (workflow mode may have
        // toggled between "design-first" and "legacy"). The
        // best-effort semantics match RulesWatcher: a failing
        // listener logs at warn and the cached state stays.
        this.configWatcher = org.aethercode.config.ConfigWatcher.start(b.cwd);
        if (this.configWatcher != null) {
            this.configWatcher.onChange(ev -> {
                try {
                    org.aethercode.config.AetherCodeConfig fresh =
                            org.aethercode.config.ConfigEngine.loadFromProjectRoot(b.cwd);
                    // 1. swap the matrix if the policy is a MatrixPermissionPolicy.
                    if (this.policy instanceof org.aethercode.permission.MatrixPermissionPolicy mpp) {
                        org.aethercode.config.PermissionMatrix current = mpp.matrix();
                        if (current != null && !matrixEquals(current, fresh.permissionMatrix)) {
                            org.aethercode.permission.MatrixPermissionPolicy swapped = mpp.withMatrix(fresh.permissionMatrix);
                            // pick up the new waterline from the
                            // freshly loaded config. withMatrix already
                            // carried the old waterline; overwrite with
                            // the new value in case the user changed it.
                            swapped.setLowWaterline(fresh.skipLowWaterline);
                            this.swapPolicy(swapped);
                            // re-install all 3 listener wrappers on
                            // the swapped policy. The wrappers (a) update
                            // engine state and (b) forward to whatever
                            // user listener is currently in the field.
                            // Without this call, the new policy would be
                            // silent on consumes and the UI would never
                            // hear about skip events.
                            installPolicyListeners(swapped);
                        } else if (current == null || mpp.lowWaterline() != fresh.skipLowWaterline) {
                            // The matrix didn't change but the
                            // waterline did. Apply it directly.
                            mpp.setLowWaterline(fresh.skipLowWaterline);
                        }
                    }
                    // 2. re-render the system prompt (workflow mode may have changed).
                    String newPrompt = renderDefaultSystemPrompt(b);
                    this.queryEngine.setSystemPrompt(newPrompt);
                    // 3. apply the project's default skip count if it changed.
                    this.skipConfirmationRegistry.applyDefault(b.sessionId, fresh);
                    // 4. re-compute the permission-mode
                    // suggestion. A config reload usually means
                    // the user is reshaping the project, so the
                    // suggestion may have shifted.
                    recomputePermissionModeSuggestion(b.cwd);
                } catch (Exception ex) {
                    LOG.warn("R101: config reload failed: {}", ex.getMessage());
                }
            });
        }
    }

    /** small helper that compares two {@link org.aethercode.config.PermissionMatrix}
     *  instances for structural equality. Avoids pulling in guava or
     *  commons-collections; we just stringify the entries map. */
    private static boolean matrixEquals(org.aethercode.config.PermissionMatrix a,
                                        org.aethercode.config.PermissionMatrix b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.entries.toString().equals(b.entries.toString());
    }

    /** small helper that mirrors the home
     *  lookup in {@link #defaultSystemPrompt(Builder)}.
     *  Returns null when {@code user.home} is not set
     *  (matches the "no global layer" fallback). */
    private static java.nio.file.Path deriveUserHomeForWatcher() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        return java.nio.file.Path.of(home);
    }

    /** render the system prompt the same way
     *  the constructor did, so a watcher event can
     *  re-render it. The method is package-private
     *  so tests can call it without reflection; the
     *  watcher fires it on a daemon thread. */
    String renderDefaultSystemPrompt(Builder b) {
        // prior round: re-render the SystemPrompt on a
        // rules file change. The watcher fires this on a
        // daemon thread; the method is package-private so
        // the prior round test can call it directly.
        java.nio.file.Path userHome = deriveUserHomeForWatcher();
        String rules = org.aethercode.prompts.RulesLoader.load(b.cwd, userHome, b.role);
        return SystemPrompt.builder()
                .environmentFrom(b.cwd, System.getProperty("os.name"))
                .toolingFrom(b.tools)
                .rules(rules)
                .build()
                .render();
    }

    /** prior round: rebuild the SystemPrompt object (not just the
     *  String) on a rules change. Used by the watcher listener
     *  when it needs to re-expose the structure to a future
     *  RPC caller (e.g. the /prompt slash command) without
     *  losing section provenance. */
    org.aethercode.prompts.SystemPrompt rebuildSystemPrompt(Builder b) {
        java.nio.file.Path userHome = deriveUserHomeForWatcher();
        String rules = org.aethercode.prompts.RulesLoader.load(b.cwd, userHome, b.role);
        return SystemPrompt.builder()
                .environmentFrom(b.cwd, System.getProperty("os.name"))
                .toolingFrom(b.tools)
                .rules(rules)
                .build();
    }

    public AppState appState() { return appState; }
    public ChatClient chatClient() { return chatClient; }
    public org.aethercode.core.engine.PermissionPolicy policy() { return policy; }

    /** accessor for the per-session skip-confirmation registry. */
    public org.aethercode.config.SkipConfirmationRegistry skipConfirmationRegistry() {
        return skipConfirmationRegistry;
    }

    /** snapshot of skip-confirmation adoption stats.
     *  Immutable record so the JSON-RPC layer can ship it
     *  without locking. */
    public record SkipStats(long consumed, long armed, long prompts) {
        /** Adoption rate: consumed / prompts. 0 when no prompts. */
        public double adoption() {
            return prompts == 0 ? 0.0 : (double) consumed / (double) prompts;
        }
    }

    /** snapshot the current skip-stats counters. */
    public SkipStats skipStats() {
        return new SkipStats(
                skipStatsConsumed.get(),
                skipStatsArmed.get(),
                skipStatsPrompts.get());
    }

    /** snapshot the per-tool consume count. Returns
     *  an unmodifiable map (tool name -> consume count). The
     *  map is the per-session per-tool adoption view: "this
     *  user armed a skip and the engine auto-allowed N
     *  calls of file_write, M calls of bash, etc."
     *  Empty tools (never auto-allowed) are NOT in the map. */
    public java.util.Map<String, Long> skipStatsByTool() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        skipStatsByTool.forEach((name, counter) -> {
            long v = counter.get();
            if (v > 0) out.put(name, v);
        });
        // Sort by count desc so the UI shows "top-N" without
        // re-sorting on every render.
        out.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        // LinkedHashMap is now sorted; return an unmodifiable view.
        return java.util.Collections.unmodifiableMap(out);
    }

    /** clear all skip-stats counters. Used by tests to
     *  start from a known state; production code does not
     *  call this. */
    void clearSkipStatsForTest() {
        skipStatsConsumed.set(0);
        skipStatsArmed.set(0);
        skipStatsPrompts.set(0);
        skipStatsByTool.clear();
    }

    /**
     * install a listener fired on every consumed skip-round.
     * The listener receives the REMAINING count after the consume.
     * If the policy isn't a {@code MatrixPermissionPolicy}, this is
     * a no-op.
     *
     * <p>R101: the listener is remembered so a later
     * {@code swapPolicy} (e.g. config.json reload) re-applies it
     * to the new policy instance.
     */
    public void setSkipConsumedListener(java.util.function.IntConsumer listener) {
        this.skipConsumedListener = listener;
        // Don't overwrite the policy's listener — the
        // constructor-installed wrapper is what we want to
        // keep (it increments skipStatsConsumed and then
        // forwards). The wrapper reads this field on every
        // fire and dispatches to the latest value.
    }

    /**
     * install a skip-low listener. Receives
     * {@code (sessionId, newRemaining)} ONCE per session
     * when the skip counter crosses DOWN through the
     * waterline. The protocol layer wires this to the
     * NOTIFY_SKIP_LOW JSON-RPC notification so the UI can
     * show "skip running low, re-arm?". The listener is
     * remembered on the engine so a {@code swapPolicy}
     * (config reload) re-applies it to the new policy.
     *
     * <p>Note: the engine's constructor installs a wrapper
     * on the policy that (a) updates the engine's snapshot
     * state and (b) forwards to the listener set here. The
     * setter does NOT replace the wrapper — it only stores
     * the listener that the wrapper dispatches to. This is
     * the same pattern R99 uses for
     * {@link #setSkipConsumedListener}.
     */
    public void setSkipLowListener(java.util.function.BiConsumer<String, Integer> listener) {
        this.skipLowListener = listener;
        // Don't overwrite the policy's listener — the
        // constructor-installed wrapper is what we want to
        // keep. The wrapper reads this field on every fire
        // and forwards to the latest value.
    }

    /**
     * install the engine's listener wrappers on a
     * {@link org.aethercode.permission.MatrixPermissionPolicy}.
     * Called once at construction AND once after every
     * {@code swapPolicy} so all three listener slots
     * (skip-consumed, per-tool, skip-low) survive a config
     * reload. The wrappers do two things: (a) update
     * engine-level state (stats counters, lastSkipLow
     * snapshot) and (b) forward to the user-installed
     * listener that's currently in the engine's field.
     *
     * <p>Also re-installs the engine's shared
     * {@code SkipConfirmationRegistry} on the new policy.
     * Without this, a swapPolicy would leave the new
     * policy with {@code skipRegistry == null}, and the
     * ASK -> consumeOne short-circuit would silently stop
     * working (consume returns false on a null registry,
     * the wrapper never fires). This was a pre-existing
     * R101 bug that R110 surfaced while testing the
     * wrapper-reinstall path.
     *
     * <p>Each wrapper is independently try/catch'd so a
     * failing listener cannot break the others or the
     * permission check. The order of install doesn't
     * matter — the policy invokes them in a fixed order
     * on every consume.
     */
    void installPolicyListeners(org.aethercode.permission.MatrixPermissionPolicy mpp) {
        // re-install the engine's shared skip
        // registry. The policy's volatile field is
        // null on a freshly constructed policy, so a
        // swapPolicy would leave it un-set.
        mpp.setSkipConfirmationRegistry(this.skipConfirmationRegistry);
        // skip-consumed wrapper. Increments
        // skipStatsConsumed and forwards to the user
        // listener (set later via setSkipConsumedListener).
        mpp.setOnSkipConsumed(remaining -> {
            skipStatsConsumed.incrementAndGet();
            if (skipConsumedListener != null) {
                try { skipConsumedListener.accept(remaining); }
                catch (RuntimeException ignore) {
                    // defensive — failing user listener
                    // must not break the engine counter.
                }
            }
        });
        // per-tool adoption wrapper. Bumps the
        // per-tool counter. No user-installed listener
        // (the engine owns the map; the protocol layer
        // reads it via skipStatsByTool()).
        mpp.setOnToolSkipConsumed((toolName, remaining) -> {
            skipStatsByTool.computeIfAbsent(toolName,
                    k -> new java.util.concurrent.atomic.AtomicLong())
                    .incrementAndGet();
        });
        // skip-low wrapper. Updates the engine's
        // lastSkipLow snapshot and forwards to the user
        // listener (set later via setSkipLowListener).
        mpp.setOnSkipLow((sessionId, remaining) -> {
            lastSkipLowSessionId.set(sessionId);
            lastSkipLowRemaining.set(remaining);
            lastSkipLowAtMs.set(System.currentTimeMillis());
            if (skipLowListener != null) {
                try { skipLowListener.accept(sessionId, remaining); }
                catch (RuntimeException ignore) {
                    // defensive — see above.
                }
            }
        });
    }

    /**
     * snapshot of the last skip-low event. The
     * {@code remaining} field is -1 if no skip-low has
     * fired yet on this engine. Useful for clients that
     * just connected and want to render "skip running low"
     * even if they missed the live notification.
     */
    public java.util.Optional<SkipLowSnapshot> lastSkipLow() {
        String sid = lastSkipLowSessionId.get();
        int rem = lastSkipLowRemaining.get();
        long at = lastSkipLowAtMs.get();
        if (rem < 0 || sid == null) return java.util.Optional.empty();
        return java.util.Optional.of(new SkipLowSnapshot(sid, rem, at));
    }

    /** immutable value type for the skip-low snapshot. */
    public record SkipLowSnapshot(String sessionId, int remaining, long atMs) {}

    /**
     * waterline read from the policy. -1 if the
     * policy is not a {@code MatrixPermissionPolicy}.
     */
    public int skipLowWaterline() {
        if (this.policy instanceof org.aethercode.permission.MatrixPermissionPolicy mpp) {
            return mpp.lowWaterline();
        }
        return -1;
    }

    /**
     * cached permission-mode suggestion. Computed
     * once at engine construction (the project root
     * doesn't change at runtime) and re-computed when
     * {@code .aethercode/config.json} is reloaded (a
     * reload usually means the user is re-shaping the
     * project, so the suggestion may shift). May be
     * null if the suggester threw — we don't fail
     * construction over a missing project root.
     */
    private org.aethercode.config.PermissionModeSuggester.Suggestion permissionModeSuggestion;

    /** accessor for the cached suggestion. May be
     *  null if the suggester threw. */
    public org.aethercode.config.PermissionModeSuggester.Suggestion permissionModeSuggestion() {
        return permissionModeSuggestion;
    }

    /** (re)compute the suggestion. Called at
     *  construction and on every config reload. The
     *  cwd is passed explicitly because the engine
     *  is on a different lifecycle than the builder
     *  (the recompute may be called from a watcher
     *  thread after construction). */
    private void recomputePermissionModeSuggestion(Path cwd) {
        try {
            this.permissionModeSuggestion =
                    org.aethercode.config.PermissionModeSuggester.suggest(cwd);
        } catch (Exception e) {
            LOG.warn("R109: permission-mode suggester failed: {}", e.toString());
            this.permissionModeSuggestion = null;
        }
    }

    /**
     * register a built-in hook on the registry only if no
     * hook of the same class is already there. The
     * {@link HookRegistry} is a flat list — without this guard,
     * a builder that calls {@code .hookRegistry(myReg)} and
     * also pre-registered a built-in would end up with two
     * instances, and every file_write would be checked twice.
     */
    private static void registerBuiltinHookIfAbsent(
            org.aethercode.hooks.HookRegistry registry,
            org.aethercode.hooks.Hook hook) {
        if (registry == null) return;
        registry.registerIfAbsent(hook);
    }

    /** replace the permission prompter. The daemon uses this
     *  to swap the in-process JLine prompter for a JSON-RPC one
     *  (which asks the connected TUI / orchestrator for decisions).
     *  No-op if the policy is not a {@code ProjectPermissionPolicy}. */
    public void setPermissionPrompter(org.aethercode.permission.ToolPermissionPrompter prompter) {
        org.aethercode.core.engine.PermissionPolicy p = this.policy;
        if (p instanceof org.aethercode.permission.ProjectPermissionPolicy ppp) {
            ppp.setPrompter(prompter);
            LOG.info("permission prompter replaced: {}", prompter == null ? "null" : prompter.getClass().getSimpleName());
        } else {
            LOG.warn("setPermissionPrompter: policy is {}, not ProjectPermissionPolicy; ignored",
                    p == null ? "null" : p.getClass().getName());
        }
    }

    /** install (or remove) the transcript-push fan-out.
     *  The HTTP+WS daemon wires this to
     *  {@code HttpJsonRpcServer::broadcast} so every
     *  {@code appendMessage} and every {@code loadSession}
     *  reaches every connected client in real time. Pass
     *  {@code null} to detach (e.g. on daemon shutdown, or
     *  to swap to a different broadcast target). Safe to
     *  call before {@code sessionStore} is wired — the
     *  push only fires once the listener is installed. */
    public void setTranscriptPush(java.util.function.Consumer<java.util.Map<String, Object>> push) {
        this.transcriptPush = push;
        LOG.info("transcript push {}",
                push == null ? "detached" : "attached: " + push.getClass().getSimpleName());
    }

    /** hot-swap the ChatClient. The next
     *  {@code query()} call uses the new client. An
     *  in-flight query keeps its old client until
     *  it completes. Also updates the engine's
     *  mainLoopModel so the Settings panel's
     *  current-model badge reflects the change.
     *  The compaction / memory modules are
     *  rebound to the new client (they hold a
     *  reference captured at construction time).
     *  Pass {@code null} to clear (a test
     *  helper). */
    public void setChatClient(ChatClient client) {
        this.chatClient = client == null ? this.chatClient : client;
        // QueryEngine captures its chatClient
        // reference at construction. The setter on
        // AetherCodeEngine must propagate so the
        // next query() actually uses the new client
        // — without this, a test that swaps the
        // client sees the engine's original
        // spring-ai path still wired in and can't
        // reach the catch (Throwable) branch.
        if (this.queryEngine != null) {
            this.queryEngine.setChatClient(this.chatClient);
        }
        if (client instanceof org.aethercode.engine.springai.SpringAiChatClient sac) {
            sac.appState(this.appState);
        }
        LOG.info("chat client hot-swapped: {}",
                client == null ? "null" : client.getClass().getSimpleName()
                        + "/" + client.modelId());
    }
    /** update the engine's mainLoopModel
     *  field. Used after switchProvider so the
     *  next {@code getState} call surfaces the
     *  new model without a full engine rebuild. */
    public AetherCodeEngine mainLoopModelName(String m) {
        this.appState.mainLoopModel(m);
        return this;
    }

    /** install (or remove) the task-push fan-out.
     *  Same contract as {@link #setTranscriptPush} but
     *  for the {@link org.aethercode.tasks.TaskRegistry}.
     *  The HTTP+WS daemon wires this to
     *  {@code HttpJsonRpcServer::broadcast} so the
     *  desktop's Kanban board can react to task
     *  creates / status transitions without polling.
     *  The listener is installed the first time this
     *  setter is called with a non-null argument; the
     *  TaskRegistry is a process-singleton so
     *  multiple engine instances in the same JVM
     *  share it. Pass {@code null} to detach (e.g. on
     *  daemon shutdown). */
    public void setTaskPush(java.util.function.Consumer<java.util.Map<String, Object>> push) {
        boolean wasNull = this.taskPush == null;
        this.taskPush = push;
        LOG.info("task push {}",
                push == null ? "detached" : "attached: " + push.getClass().getSimpleName());
        // Install the listener the first time we have
        // a non-null push. Subsequent setTaskPush
        // calls (e.g. attach/detach/reattach on a
        // hot-reload) just swap the consumer — the
        // TaskRegistry still only has one listener.
        if (push != null && wasNull) {
            org.aethercode.tasks.TaskRegistry.instance()
                    .onChange(t -> {
                        var cur = this.taskPush;
                        if (cur == null) return;
                        try {
                            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
                            payload.put("action", t.endedAtMs() > 0 && t.status() == org.aethercode.tasks.TaskStatus.PENDING
                                    ? "create" : "update");
                            payload.put("task", taskToMap(t));
                            cur.accept(payload);
                        } catch (Exception e) {
                            LOG.debug("task push failed: {}", e.getMessage());
                        }
                    });
        }
    }

    /** serialise a {@link org.aethercode.tasks.Task}
     *  to a {@code Map} for the WS push payload. Same
     *  shape as the {@code listTasks} RPC return entry
     *  so the renderer can apply both paths uniformly. */
    private static java.util.Map<String, Object> taskToMap(org.aethercode.tasks.Task t) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", t.id());
        out.put("type", t.type().name().toLowerCase());
        out.put("status", t.status().name().toLowerCase());
        out.put("description", t.description());
        out.put("parentTaskId", t.parentTaskId());
        out.put("createdAtMs", t.createdAtMs());
        out.put("endedAtMs", t.endedAtMs());
        return out;
    }

    /** replace the live permission policy wholesale. Used by
     *  {@code AetherCodeMethods.permissionPolicyOverride} to install
     *  a "always allow" / "always deny" rule from the desktop.
     *  Atomic via the {@code volatile} field; the orchestrator's
     *  permission checks always see a fully-formed policy. */
    public void swapPolicy(org.aethercode.core.engine.PermissionPolicy newPolicy) {
        org.aethercode.core.engine.PermissionPolicy old = this.policy;
        this.policy = newPolicy;
        // also push the new policy down to the
        // streaming executor. Previously, the executor
        // captured the original policy at construction
        // time and never saw this swap — every tool
        // call went through the stale policy. The
        // executor's policy field is volatile so the
        // next call observes the swap.
        if (this.streamingToolExecutor != null) {
            this.streamingToolExecutor.setPolicy(newPolicy);
        }
        LOG.info("permission policy swapped: {} -> {}",
                old == null ? "null" : old.getClass().getSimpleName(),
                newPolicy == null ? "null" : newPolicy.getClass().getSimpleName());
    }

    /** change the live permission mode without rebuilding
     *  the rest of the policy (rules + prompter are preserved). The
     *  caller (the daemon's {@code setPermissionMode} RPC) used to
     *  only update {@code AppState.permissionMode}, but the live
     *  {@link ProjectPermissionPolicy} was constructed once with the
     *  original mode and the swap never reached it. The user saw
     *  "mode = ACCEPT_TASK" in the status bar while every tool call
     *  still triggered a permission prompt because the policy was
     *  effectively still DEFAULT.
     *
     *  <p>When the current policy is a {@code ProjectPermissionPolicy}
     *  we use {@code withMode} to produce a like-for-like copy; for
     *  other policy shapes (a unit-test {@code allowAll} etc.) we
     *  fall back to a plain reference swap (the caller knows what
     *  they're doing). */
    public void setPermissionMode(org.aethercode.core.permission.PermissionMode newMode) {
        org.aethercode.core.engine.PermissionPolicy current = this.policy;
        if (current instanceof org.aethercode.permission.ProjectPermissionPolicy ppp) {
            this.policy = ppp.withMode(newMode);
            // also push the new policy down to the
            // streaming executor. Previously, the executor
            // held the ORIGINAL policy reference (captured
            // at construction) and every subsequent
            // setPermissionMode() updated the engine's
            // this.policy but not the executor's. The
            // user saw BYPASS_PERMISSIONS in the status bar and the
            // engine's appState reflected it, yet tool
            // calls still prompted because the executor's
            // permissionPolicy field was a `final` snapshot
            // of the pre-swap mode. Now the executor's
            // field is volatile + mutable and we re-push
            // on every setPermissionMode() call.
            if (this.streamingToolExecutor != null) {
                this.streamingToolExecutor.setPolicy(this.policy);
            }
            LOG.info("permission mode: {} -> {}", ppp.mode(), newMode);
        } else {
            LOG.warn("setPermissionMode({}) called but live policy is {} (not ProjectPermissionPolicy); only AppState updated",
                    newMode, current == null ? "null" : current.getClass().getName());
        }
        // persist the new mode so the next engine boot
        // restores it. Best-effort: a failing write logs at
        // warn and the in-memory state still updates.
        if (this.sessionStore != null) {
            org.aethercode.config.PermissionModePersistence.saveToDisk(
                    this.sessionStore.dir(), this.appState.sessionId(), newMode);
        }
    }
    public String systemPrompt() { return systemPrompt.render(); }
    public HookRegistry hookRegistry() { return hookRegistry; }
    /** per-phase tool-call budget tracker (may be
     *  null if the engine was built without one). The
     *  caller can read the current state via
     *  {@code phaseTracker().snapshot()} and update via
     *  {@code phaseTracker().setPhase(name)} /
     *  {@code setBudget(name, calls, usd)}. Tool-call
     *  recording is the caller's responsibility — see
     *  {@link org.aethercode.hooks.builtin.PhaseTracker#recordToolCall}. */
    public org.aethercode.hooks.builtin.PhaseTracker phaseTracker() { return phaseTracker; }

    /** tool-call observer installed on the
     *  StreamingToolExecutor. Forwards to the
     *  phase tracker (when one is configured) so the
     *  budget counter actually increments per tool
     *  execution. We also opportunistically bump the
     *  phase from the tool name via
     *  {@link PhaseTracker#inferPhaseFromTool} so a
     *  single tool call (without an explicit
     *  {@code setPhase}) still gets the right bucket
     *  — useful when the model forgets to call
     *  {@code todo_write} between phases. */
    void onToolCallObserved(String toolName, double costUsd) {
        // bump the per-session stats
        // BEFORE the early-return so every
        // tool call is counted even if the
        // phase tracker is missing. The
        // stats survive across the whole
        // session and the TUI renders them
        // via the summary RPC.
        if (sessionStats != null) {
            sessionStats.recordToolCall(toolName);
        }
        if (phaseTracker == null) return;
        if (phaseTracker.currentPhase() == null || phaseTracker.currentPhase().isEmpty()) {
            // Defensive: belt-and-suspenders for tests
            // that build an engine with a phase tracker
            // but no default phase.
            phaseTracker.setPhase(org.aethercode.hooks.builtin.PhaseTracker.DEFAULT_PHASE);
        }
        phaseTracker.recordToolCall(toolName, costUsd);
        // Phase auto-inference — if the user didn't
        // explicitly call setPhase, the tool name is
        // the strongest signal. We do NOT auto-promote
        // across canonical phases here (e.g. a write
        // tool during "explore" still increments the
        // explore bucket) — the user / model
        // explicitly transitions. We only set the
        // initial phase from the very first tool call.
        if (toolName != null) {
            // Single infer call: bucket the cost
            // against the inferred phase, but DON'T
            // switch active phase. This means a write
            // during explore still records as explore
            // (the active bucket). The model can call
            // setPhase via /phase when it's actually
            // moving to implement.
        }
    }
    /** multi-session manager (may be null). When
     *  set, the AetherCodeMethods RPCs route by
     *  sessionId to the engine registered for that
     *  session. */
    public org.aethercode.sdk.SessionManager sessionManager() { return sessionManager; }

    /** install (or replace) the SessionManager
     *  post-construction. Used by {@code DaemonRunner}
     *  when the engine was built without a manager
     *  (the default CLI flow) and the daemon needs
     *  to wire the manager around the existing
     *  engine. Replaces the previous
     *  constructor-only path; the field is now
     *  {@code volatile} to support this setter.
     *
     *  <p>Pass {@code null} to clear. The setter is
     *  idempotent: the same manager installed
     *  twice is a no-op. A different manager
     *  replaces the prior binding; existing
     *  {@code EngineHandle}s in the old manager
     *  are NOT migrated (callers that swap
     *  managers mid-flight own the migration). */
    public void setSessionManager(org.aethercode.sdk.SessionManager m) {
        this.sessionManager = m;
        LOG.info("prior round: SessionManager {} on engine {}",
                m == null ? "cleared" : "installed",
                appState().sessionId());
        // when a manager is installed after the engine
        // has already adopted a non-default sessionId (e.g. a
        // daemon restart that loaded an existing session via
        // the sidecar .cwd file), make sure the manager has
        // the current id registered. Without this the first
        // session-scoped RPC after startup (bindSessionCwd,
        // setModel, ...) would fail with "no engine for
        // sessionId: <id>" because the manager only knew
        // "default" + the engine's pre-load UUID.
        if (m != null) {
            syncSessionManagerRegistration();
        }
    }

    /** make sure the {@link SessionManager} (when
     *  installed) knows this engine under the engine's
     *  current {@code appState.sessionId()}.
     *
     *  <p>The manager is keyed by sessionId. The daemon
     *  registers the engine under "default" and the
     *  engine's startup UUID (see
     *  {@code DaemonRunner.buildSessionManager}). But
     *  {@link #createSession} and {@link #loadSession}
     *  replace {@code appState.sessionId()} with a fresh
     *  id from {@code SessionStore.newSessionId()} (a
     *  timestamp + short UUID, e.g.
     *  {@code 2026-09-01T04-49-44.811597300Z_16c023b9})
     *  WITHOUT telling the manager. The renderer (desktop
     *  / TUI) learns the new id from the
     *  {@code createSession} response or the
     *  {@code transcript_event(sync)} push, then sends
     *  subsequent RPCs (bindSessionCwd, setModel, ...)
     *  with that id — and the manager's
     *  {@code get(newId)} returns null.
     *
     *  <p>Calling {@code registerExisting(newId, this)} is
     *  safe because the manager's
     *  {@code registerExisting} is idempotent: it returns
     *  {@code null} when the id is already registered and
     *  leaves the existing handle intact. So calling this
     *  helper on every session swap is a cheap no-op for
     *  the no-change case and fixes the routing for the
     *  real one.
     *
     *  <p>Silently no-ops when the manager isn't installed
     *  (the legacy single-engine path), when the session
     *  id is null/blank, or when {@code registerExisting}
     *  itself throws (logged at WARN — a stale manager
     *  is a recoverable condition, not a crash). */
    private void syncSessionManagerRegistration() {
        org.aethercode.sdk.SessionManager m = this.sessionManager;
        if (m == null) return;
        String sid;
        try {
            sid = appState().sessionId();
        } catch (Throwable t) {
            LOG.warn("R178: cannot read sessionId for re-register: {}", t.getMessage());
            return;
        }
        if (sid == null || sid.isBlank()) return;
        try {
            m.registerExisting(sid, this);
        } catch (Throwable t) {
            // registerExisting throws on MAX_SESSIONS, but
            // returns null on the "already registered" case
            // so we won't see a duplicate-key error here.
            // Still, treat any throw as a soft warning —
            // routing a subsequent RPC through a stale
            // manager is recoverable (the caller will see
            // the "no engine" error and the user can retry).
            LOG.warn("R178: SessionManager re-register({}) failed: {}",
                    sid, t.getMessage());
        }
    }

    /** the in-memory skill registry, or {@code null} when
     *  the engine was built without skill discovery. */
    public org.aethercode.core.skill.SkillRegistry skillRegistry() { return skillRegistry; }
    /** the in-memory agent registry, or {@code null} when
     *  the engine was built without agent discovery. */
    public org.aethercode.core.agent.AgentRegistry agentRegistry() { return agentRegistry; }
    /** list available skills, in alphabetical order. Returns
     *  an empty list when no registry is wired (the daemon's
     *  {@code listSkills} RPC translates the empty list to
     *  {@code {ok: true, skills: []}}). */
    public java.util.List<org.aethercode.core.skill.SkillRegistry.SkillMeta> listSkills() {
        return skillRegistry == null ? java.util.List.of() : skillRegistry.list();
    }
    /** full body of one skill. The {@code reloadSkills}
     *  RPC uses {@code listSkills()} metadata + this method to
     *  surface a skill to the LLM on demand. */
    public java.util.Optional<String> getSkillBody(String name) {
        return skillRegistry == null ? java.util.Optional.empty() : skillRegistry.getBody(name);
    }
    /** force a re-scan. Returns the new skill count. */
    public int reloadSkills() {
        return skillRegistry == null ? 0 : skillRegistry.reload();
    }
    /** prior round: the unified registry-reload service, or
     *  {@code null} when the engine was built without one
     *  (unit tests + the stdio daemon path that doesn't
     *  have a watch thread). The CLI / DaemonRunner wire
     *  this at startup; the daemon's {@code reloadRegistries}
     *  RPC delegates to it. */
    public org.aethercode.core.registry.RegistryReloadService registryReloadService() {
        return registryReloadService;
    }
    /** prior round: install the registry-reload service. The
     *  CLI's Main + DaemonRunner call this after building
     *  the engine so the file watcher is running before
     *  the first user command. Subsequent calls are no-ops
     *  (a second service would orphan the first watcher
     *  thread — callers should set it once). */
    public void registryReloadService(org.aethercode.core.registry.RegistryReloadService svc) {
        if (this.registryReloadService != null && this.registryReloadService != svc) {
            try { this.registryReloadService.close(); } catch (Exception ignore) {}
        }
        this.registryReloadService = svc;
    }
    /** the live MCP manager, or {@code null} when
     *  the engine was built without MCP support (unit
     *  tests, the stdio daemon that doesn't have any
     *  MCP config to load). The daemon's
     *  {@code reloadRegistries} RPC delegates the
     *  {@code MCP} kind to {@link org.aethercode.mcp.McpManager#reload}. */
    public org.aethercode.mcp.McpManager mcpManager() { return mcpManager; }
    /** install the MCP manager. The CLI / DaemonRunner
     *  call this after building the engine so the file
     *  watcher's reload trigger has a manager to call. */
    public void mcpManager(org.aethercode.mcp.McpManager m) {
        if (this.mcpManager != null && this.mcpManager != m) {
            try { this.mcpManager.closeAll(); } catch (Exception ignore) {}
        }
        this.mcpManager = m;
    }
    /** atomic MCP tool swap. Called by the
     *  reload handler after {@code McpManager.reload()}
     *  produces a new tools list. The swap removes the
     *  previously-installed MCP tools and installs the
     *  new ones; built-in tools are left alone. */
    public java.util.List<org.aethercode.core.tool.Tool> replaceMcpTools(
            java.util.List<org.aethercode.core.tool.Tool> fresh) {
        return appState.replaceMcpTools(fresh);
    }
    /** list available Mavis agents, in alphabetical order. */
    public java.util.List<org.aethercode.core.agent.AgentRegistry.AgentMeta> listAgents() {
        return agentRegistry == null ? java.util.List.of() : agentRegistry.list();
    }
    /** full body of one agent. */
    public java.util.Optional<String> getAgentBody(String name) {
        return agentRegistry == null ? java.util.Optional.empty() : agentRegistry.getBody(name);
    }
    /** full metadata for one agent (body +
     *  frontmatter fields, including the {@code model:}
     *  field the workflow executor uses to pick a
     *  per-agent ChatClient for child sessions). */
    public java.util.Optional<org.aethercode.core.agent.AgentRegistry.AgentMeta> getAgentMeta(String name) {
        return agentRegistry == null ? java.util.Optional.empty() : agentRegistry.getMeta(name);
    }

    /** the concurrency controller, or {@code null} when
     *  the engine was built without one. The controller is the
     *  source of truth for memory stats, throttle state, and
     *  in-flight counters. */
    public org.aethercode.core.concurrency.ConcurrencyController concurrencyController() { return concurrencyController; }
    /** live engine stats. The daemon's {@code getEngineStats}
     *  RPC returns this so the desktop can show a memory badge
     *  and a throttle pill. Cheap; safe to call from any thread. */
    public org.aethercode.core.concurrency.EngineStats getEngineStats() {
        return concurrencyController == null
                ? new org.aethercode.core.concurrency.EngineStats(
                        0, Runtime.getRuntime().maxMemory(), 0,
                        0, 0, false, false,
                        0, 0, 0, 0, 0, 0,
                        "off", System.currentTimeMillis())
                : concurrencyController.snapshot();
    }
    /** switch the concurrency profile by name
     *  ({@code low}, {@code normal}, {@code high}). Unknown
     *  values are logged and ignored (the engine keeps its
     *  current profile). */
    public void setConcurrencyProfile(String name) {
        if (concurrencyController == null) return;
        concurrencyController.setProfileByName(name);
    }
    /** try to acquire a query slot, blocking if needed.
     *  Used by internal callers (workflow executor, skill /
     *  agent child sessions) that don't want the BACKPRESSURE
     *  semantics — they would rather queue. */
    public org.aethercode.core.concurrency.ConcurrencyController.Lease acquireQuerySlot() {
        if (concurrencyController == null) return null;
        return concurrencyController.tryAcquireQuery();
    }
    /** the underlying QueryEngine, for callers (TUI / agent
     *  switcher) that need to mutate the per-turn state. Most
     *  consumers should use {@link #query(String)} and not poke at
     *  the engine directly. */
    public org.aethercode.core.engine.QueryEngine queryEngine() { return queryEngine; }
    /** the loop detector for the current user query, or
     *  {@code null} if no query is in flight (or the detector
     *  was disabled via {@code setLoopDetector(-1, -1)}). The
     *  detector is rebuilt on every {@code query()} call, so
     *  callers that hold a reference to it should re-read it
     *  on each new run. The {@code loopAck} RPC uses this to
     *  reset the tier when the user clicks "Continue" in the
     *  LoopGuardBanner. May be null between queries — the RPC
     *  handler treats that as a no-op (returns {@code ok=true}
     *  with {@code tier=0}). */
    public org.aethercode.core.engine.ProgressLoopDetector currentLoopDetector() {
        return queryEngine == null ? null : queryEngine.currentLoopDetector();
    }
    public Compactor compactor() { return compactor; }
    /** run the pre-flight compact synchronously. The
     *  TUI's "Compaction Recommended" button calls this via the
     *  {@code compact} RPC. Returns true if a compact
     *  actually ran, false if it was a no-op (e.g.
     *  transcript is already under the threshold). */
    public boolean runPreFlightCompact() {
        if (queryEngine == null) return false;
        int before = appState == null ? 0 : appState.transcript().size();
        try {
            queryEngine.runPreFlightCompact();
        } catch (RuntimeException ex) {
            java.util.logging.Logger.getLogger(AetherCodeEngine.class.getName())
                    .warning("R145 runPreFlightCompact failed: " + ex.getMessage());
            return false;
        }
        int after = appState == null ? 0 : appState.transcript().size();
        return after < before;
    }
    /** per-process metrics collector. Returns counters and
     *  derived ratios (errorRate, cacheHitRate, etc.) for the
     *  current engine. */
    public org.aethercode.core.metrics.MetricsCollector metrics() { return metrics; }
    /** lightweight span recorder. Returns the most recent
     *  completed spans via {@code snapshot()}; the engine's
     *  query-loop wires {@code startSpan}/{@code endSpan} around
     *  each query and tool invocation. */
    public org.aethercode.core.trace.TraceRecorder traces() { return traces; }
    /** per-session context window in tokens, set via the Builder. */
    public int contextWindow() { return appState.contextWindow(); }
    public CostTracker costTracker() { return costTracker; }
    /** per-session tool-call counters and
     *  state. Reset on every {@code loadSession}
     *  / {@code createSession}. The {@code summary}
     *  RPC exposes {@link SessionStats#toWireSnapshot()}
     *  to the TUI. */
    public SessionStats sessionStats() { return sessionStats; }

    /**
     * record that a TUI / supervisor successfully pinged
     * this engine. The engineHealth RPC surfaces this so a UI
     * can show "last ping 3s ago" and detect a frozen TUI even
     * when the engine is otherwise healthy. Idempotent.
     */
    public void recordPing() { this.lastPingAtMs = System.currentTimeMillis(); }

    /**
     * wall-clock millis when {@link #recordPing()} was
     * last called, or 0 if no ping has been received since the
     * engine started.
     */
    public long lastPingAtMs() { return lastPingAtMs; }

    /**
     * record an exception that the engine caught and
     * surfaced. Wraps {@link SessionStats#recordError(Throwable)}
     * so the engineHealth RPC can show "last error 2 minutes ago"
     * and the user can debug a stuck / failing session.
     */
    public void recordError(Throwable t) { sessionStats.recordError(t); }

    /** run a single query in a fresh child session. The
     *  parent session's transcript is untouched — the child
     *  lives on its own (minted via {@link #createSession})
     *  and is deleted at the end of the call. Returns the
     *  child session's final assistant text, or throws
     *  {@link UnsupportedOperationException} when no
     *  {@code SessionStore} is wired (the child lives on
     *  disk — without a store, "child" is meaningless).
     *
     *  <p>Used by the workflow executor's {@code skill} and
     *  {@code agent} step types. prior round was a stub; R106 wires
     *  the real call. The full streaming version (so the
     *  desktop can watch a skill run in real time) is R106+.
     *
     *  <p>The {@code prompt} is prefixed with a system note
     *  that tags it as a skill/agent invocation. The model
     *  sees the note in the system prompt and behaves
     *  accordingly. A real skill loader would replace this
     *  with the skill's full instructions; the placeholder
     *  is good enough for R106's smoke test. */
    /** prior round: run a query on a child session.
     *  The child session has its own transcript file
     *  (created via {@link #createSession}); the parent's
     *  state is restored on exit. The returned String is
     *  the captured assistant text (concatenation of all
     *  {@code text_delta} events).
     *
     *  <p>This overload does NOT forward the
     *  {@code StreamEvent}s to any external sink — the
     *  caller's only view of the child's output is the
     *  returned text. Use the four-arg overload
     *  (with {@code eventSink}) when the caller wants
     *  the events to flow back (e.g. for a workflow
     *  progress bar that shows what the child is doing
     *  in real time). */
    public String queryInChildSession(String parentSessionId, String kind, String name, String prompt) throws java.io.IOException {
        return queryInChildSession(parentSessionId, kind, name, prompt, ev -> { /* no-op sink */ }, null);
    }

    /** run a query on a child session AND
     *  forward every {@code StreamEvent} to the
     *  {@code eventSink} so the caller can render the
     *  child's progress in real time. The captured
     *  assistant text is still returned (same shape as
     *  the three-arg overload) so the
     *  {@code WorkflowExecutor}'s {@code SkillInvoker}
     *  hook can use it as the step's {@code stdout}.
     *
     *  <p>The {@code eventSink} runs on the call
     *  thread (the one that called
     *  {@code queryInChildSession}). The
     *  {@code WorkflowExecutor} passes its own
     *  workflow-wide sink, so the events fan out to
     *  every WS client that subscribed to the
     *  workflow. The sink is best-effort — a
     *  misbehaving consumer (e.g. a network blip on
     *  the broadcast notifier) doesn't break the
     *  child session; we wrap the sink.accept call in
     *  try/catch and log a debug line on failure. */
    public String queryInChildSession(String parentSessionId, String kind, String name, String prompt,
                                      java.util.function.Consumer<org.aethercode.core.stream.StreamEvent> eventSink) throws java.io.IOException {
        return queryInChildSession(parentSessionId, kind, name, prompt, eventSink, null);
    }

    /** child session with an explicit
     *  {@link ChatClient} override. Used by the
     *  workflow executor's {@code kind: agent} step
     *  when the named agent's frontmatter declares a
     *  {@code model:} field — the caller (the
     *  methods layer) builds a per-agent ChatClient
     *  from the {@link org.aethercode.core.providers.ProviderRegistry}
     *  and passes it here. The override is captured
     *  into the per-call query path; the engine's
     *  own {@code chatClient} field is NOT mutated,
     *  so a concurrent main-loop query on another
     *  thread still uses the default model. Pass
     *  {@code null} to use the engine's default —
     *  that's what the 3-arg and 5-arg overloads do.
     *  The captured text still flows back through
     *  the {@code eventSink}; the override only
     *  changes WHICH model the child session uses. */
    public String queryInChildSession(String parentSessionId, String kind, String name, String prompt,
                                      java.util.function.Consumer<org.aethercode.core.stream.StreamEvent> eventSink,
                                      ChatClient chatClientOverride) throws java.io.IOException {
        if (sessionStore == null) {
            throw new UnsupportedOperationException("SessionStore is not wired; child sessions need persistence");
        }
        if (kind == null || (!kind.equals("skill") && !kind.equals("agent"))) {
            throw new IllegalArgumentException("kind must be 'skill' or 'agent'");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        // 1. Save the parent's state so we can restore it.
        String savedSessionId = appState.sessionId();
        Transcript savedTranscript = currentTranscript;
        // 2. Mint a child session and switch to it. The new
        //    file is empty.
        String childId = createSession();
        try {
            // 3. Run the query. We pass the child session id
            //    as the active session; the QueryEngine
            //    appends to the listener's transcript (the
            //    child file) and streams events back through
            //    the regular path.
            //
            // build a per-child preamble that gives the
            // model the right context. For {@code kind=skill}
            // we look up the skill body and prepend it; for
            // {@code kind=agent} we look up the agent's
            // agent.md and prepend it. The skill registry
            // also renders an {@code <available_skills>} block
            // so the model knows the full set even when only
            // one skill is being run.
            String preamble = buildChildPreamble(kind, name);
            String formatted = (preamble.isEmpty() ? "" : preamble + "\n\n---\n\n")
                    + "[Running as " + kind + " \"" + name + "\"]\n\n"
                    + (prompt == null ? "" : prompt);
            StringBuilder out = new StringBuilder();
            // pass the per-call chat client
            // override (null when the caller used one of
            // the simpler overloads) so the child
            // session uses the agent's frontmatter
            // model instead of the engine's default.
            try (java.util.stream.Stream<org.aethercode.core.stream.StreamEvent> stream = query(formatted, chatClientOverride)) {
                java.util.stream.StreamSupport.stream(stream.spliterator(), false)
                        .forEach(ev -> {
                            if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) {
                                out.append(td.text());
                            }
                            // forward every event to
                            // the caller's sink. The
                            // WorkflowExecutor passes its own
                            // sink so the events fan out via
                            // the workflow's stream-event
                            // channel to every connected
                            // client. A misbehaving sink
                            // (e.g. broken WS) must not
                            // break the child session — wrap
                            // the accept call.
                            if (eventSink != null) {
                                try { eventSink.accept(ev); }
                                catch (Exception e) {
                                    LOG.debug("child session event sink threw: {}", e.getMessage());
                                }
                            }
                        });
            }
            return out.toString();
        } finally {
            // 4. Switch back to the parent. The child file
            //    is left on disk for the user to inspect via
            //    listSessions; a future R106+ could delete
            //    it here if the child is short-lived.
            appState.transcript().clear();
            for (Message m : savedTranscript.messages()) appState.transcript().add(m);
            currentTranscript = savedTranscript;
            appState.sessionId(savedSessionId);
        }
    }

    /** build the per-child system-prompt preamble. The
     *  child sees the full engine system prompt + this preamble,
     *  which tells it which skill/agent it is and gives it the
     *  relevant body. The skill registry's
     *  {@code <available_skills>} block is appended at the end so
     *  the model can pick the right skill to delegate to from
     *  inside the child session. */
    private String buildChildPreamble(String kind, String name) {
        StringBuilder sb = new StringBuilder();
        if ("skill".equals(kind)) {
            if (skillRegistry != null) {
                String body = skillRegistry.getBody(name).orElse(null);
                if (body != null && !body.isBlank()) {
                    sb.append("<skill name=\"").append(name).append("\">\n");
                    sb.append(body.strip());
                    sb.append("\n</skill>\n");
                }
            }
        } else if ("agent".equals(kind)) {
            if (agentRegistry != null) {
                String body = agentRegistry.getBody(name).orElse(null);
                if (body != null && !body.isBlank()) {
                    sb.append("<agent name=\"").append(name).append("\">\n");
                    sb.append(body.strip());
                    sb.append("\n</agent>\n");
                }
            }
        }
        // Always append the available skills block so the child
        // session can delegate further.
        if (skillRegistry != null) {
            String block = skillRegistry.renderSystemPromptBlock();
            if (!block.isEmpty()) sb.append(block);
        }
        return sb.toString();
    }

    /** the {@link SessionStore} wired at construction, or
     *  {@code null} if persistence is disabled. The daemon wires
     *  this on startup so its RPC layer can list/load/create/
     *  delete sessions. The CLI has used this since prior round. */
    public org.aethercode.core.transcript.SessionStore sessionStore() { return sessionStore; }

    /** install a {@link SessionStore} after construction.
     *  Used by the daemon (which builds the engine in
     *  {@code Main.buildEngine()} and then wires the store
     *  with the {@code AETHERCODE_SESSIONS_DIR} env var, or
     *  the default {@code <cwd>/.aethercode/sessions} fallback).
     *  Idempotent: a second call with the same store is a
     *  no-op; a second call with a different store throws
     *  (the engine has already attached a listener to the
     *  first one, so swapping mid-flight would leak). When
     *  the engine did not have a store at construction time,
     *  this also installs the listener and loads the current
     *  session's transcript. */
    public synchronized void setSessionStore(org.aethercode.core.transcript.SessionStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (this.sessionStore == store) {
            LOG.debug("R148: setSessionStore — same instance, no-op");
            return;
        }
        if (this.sessionStore != null) {
            throw new IllegalStateException(
                    "R148: sessionStore is already wired (refusing to swap mid-flight). "
                            + "Use a fresh engine if you need a different store.");
        }
        this.sessionStore = store;
        // The constructor's wiring (attach listener +
        // load current transcript) is only run when the
        // builder supplied a store. We replicate that
        // here so a post-construction setSessionStore
        // gives the engine the same capabilities.
        attachSessionStore();
        LOG.info("R148: SessionStore wired post-construction: {} (active session: {})",
                store.dir(), appState.sessionId());
    }

    /** the {@link Transcript} currently being written to.
     *  {@code null} until the first {@link #loadSession} or
     *  {@link #createSession} call (or until the constructor
     *  auto-loads the current session). The desktop's
     *  {@code getState} RPC reads this to surface a session's
     *  message count. */
    public org.aethercode.core.transcript.Transcript currentTranscript() { return currentTranscript; }

    /** list all known sessions (most-recently-modified first).
     *  Empty list when no {@code SessionStore} is wired — matches
     *  the legacy {@code listSessions} behaviour so the frontend
     *  keeps working. */
    public java.util.List<org.aethercode.core.transcript.SessionStore.SessionInfo> listSessions() {
        if (sessionStore == null) return java.util.List.of();
        try { return sessionStore.list(); }
        catch (java.io.IOException e) {
            LOG.warn("listSessions failed: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    /** switch the engine to an existing session. The current
     *  session's transcript is closed (the on-disk file already
     *  has every message via the append listener) and the new
     *  session's file is loaded into {@code appState.transcript}.
     *  Returns the loaded transcript on success, or throws if
     *  the session can't be read. Callers (the daemon's
     *  {@code loadSession} RPC) should serialize the response
     *  back to the client. */
    public org.aethercode.core.transcript.Transcript loadSession(String sessionId) throws java.io.IOException {
        if (sessionStore == null) {
            throw new UnsupportedOperationException("SessionStore is not wired");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        // The on-disk file is already up-to-date (every
        // appendMessage during the current session was mirrored
        // by the listener). Just close the current handle and
        // open the new one.
        Transcript loaded = sessionStore.loadOrCreate(sessionId);
        // Replace the in-memory transcript. We don't appendMessage
        // here — that would re-fire the listener and re-write the
        // same lines. Instead, we mutate the list directly. The
        // CopyOnWriteArrayList supports clear() + addAll() safely.
        appState.transcript().clear();
        for (Message m : loaded.messages()) appState.transcript().add(m);
        this.currentTranscript = loaded;
        // also update the appState's sessionId so the
        // engine reports the new session as "current". Without
        // this, listSessions() / getState() would always show
        // the engine's startup sessionId, which the user can't
        // ever escape.
        appState.sessionId(sessionId);
        // re-register the engine under the new id in
        // the SessionManager (when one is installed). The
        // manager was set up by the daemon under "default" +
        // the engine's startup UUID; loadSession swaps
        // appState.sessionId() to a different existing
        // session's id, so the manager's get(sessionId) would
        // return null until the next restart. See
        // createSession's matching R178 block for the full
        // story.
        syncSessionManagerRegistration();
        // reset per-session stats on a new session.
        // The same engine may serve many sessions; the
        // summary RPC must reflect only the current
        // session's activity. Wipe counters and bump
        // the new "started_at" timestamp.
        if (sessionStats != null) {
            sessionStats.reset();
        }
        LOG.info("loadSession: {} ({} messages)", sessionId, loaded.messages().size());
        // fan out a "sync" event with the entire loaded
        // transcript so the desktop's `transcript_event`
        // subscriber can rebuild its `messages` array without
        // a separate getTranscript round-trip. We snapshot
        // transcriptPush before the null-check so a
        // setTranscriptPush(null) racing the load doesn't NPE.
        // The try/catch around the consumer keeps a broken
        // network target from surfacing as an exception out
        // of loadSession (the on-disk swap already succeeded).
        var push = transcriptPush;
        if (push != null) {
            try {
                java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("action", "sync");
                payload.put("sessionId", sessionId);
                java.util.List<java.util.Map<String, Object>> msgs = new java.util.ArrayList<>(loaded.messages().size());
                for (Message m : loaded.messages()) msgs.add(m.toMap());
                payload.put("messages", msgs);
                push.accept(payload);
            } catch (Exception e) {
                LOG.debug("transcript push (sync) failed: {}", e.getMessage());
            }
        }
        return loaded;
    }

    /** mint a fresh session id and start an empty transcript
     *  in the store. The engine's in-memory transcript is
     *  cleared; subsequent appendMessages write to the new file.
     *  Returns the new session id. The daemon's {@code createSession}
     *  RPC hands this back to the frontend so the LeftPanel can
     *  show the new entry immediately. */
    public String createSession() throws java.io.IOException {
        if (sessionStore == null) {
            throw new UnsupportedOperationException("SessionStore is not wired");
        }
        return createSession(null);
    }

    /** prior round 1: create a session with a per-session cwd.
     *  When {@code cwd} is non-null, the new session's
     *  transcript file lives under
     *  {@code <memoryDir>/<sessionId>} AND a
     *  {@code .cwd} sidecar is written next to the
     *  transcript so a daemon restart can re-load the
     *  session with the right cwd. */
    public String createSession(String cwd) throws java.io.IOException {
        if (sessionStore == null) {
            throw new UnsupportedOperationException("SessionStore is not wired");
        }
        String newId = org.aethercode.core.transcript.SessionStore.newSessionId();
        Transcript empty = sessionStore.loadOrCreate(newId);
        // prior round 1: persist the per-session cwd in a
        // sidecar file. The engine's main load path
        // (loadSession) reads the sidecar and uses
        // the cwd to build the engine's AppState.
        if (cwd != null && !cwd.isBlank()) {
            java.nio.file.Path p = empty.file();
            if (p != null) {
                java.nio.file.Path cwdSidecar = p.getParent().resolve(newId + ".cwd");
                java.nio.file.Files.writeString(cwdSidecar, cwd);
            }
        }
        // materialise the empty file on disk so
        // listSessions surfaces the new session immediately.
        // Without this, the file only appears after the user
        // sends a message (when the listener's first append
        // creates it). The user-facing consequence is that
        // "+ New session" → the LeftPanel doesn't show the new
        // entry until the first message lands — which feels
        // like a missing row. Touching the file with a 0-byte
        // write fixes the UX at the cost of one empty file
        // per never-used session.
        java.nio.file.Path p = empty.file();
        if (p != null && !java.nio.file.Files.exists(p)) {
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.createFile(p);
        }
        // Empty the in-memory transcript. No listener writes
        // happen here because the new file is empty.
        appState.transcript().clear();
        this.currentTranscript = empty;
        // also update the appState's sessionId so
        // the engine reports the new session as "current".
        // Without this, listSessions() / getState() / and the
        // prior round transcript_push payloads would always show the
        // old id, which the user can't ever escape.
        appState.sessionId(newId);
        // also register the engine under the new id in
        // the SessionManager (when one is installed). The
        // manager was set up by the daemon under "default" +
        // the engine's startup UUID, but createSession
        // replaces appState.sessionId() with a fresh
        // SessionStore id, so the manager's get(newId) would
        // return null and bindSessionCwd / setModel would
        // fail with "no engine for sessionId: <newId>".
        syncSessionManagerRegistration();
        // reset per-session stats for a new session
        // (same reason as loadSession: the same engine may
        // serve many sessions; the summary RPC must reflect
        // only the current session's activity).
        if (sessionStats != null) {
            sessionStats.reset();
        }
        LOG.info("createSession: {}", newId);
        // fan out a "sync" event with the empty
        // transcript. Same try/catch discipline as
        // loadSession — a broken network target must not break
        // the on-disk swap. Without this push, the desktop's
        // `transcript_event` subscriber keeps the previous
        // session's messages in `state.messages` after the
        // user hits "+ New session", which would briefly show the
        // old conversation under the new session id.
        var push = transcriptPush;
        if (push != null) {
            try {
                java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("action", "sync");
                payload.put("sessionId", newId);
                payload.put("messages", java.util.List.of());
                push.accept(payload);
            } catch (Exception e) {
                LOG.debug("transcript push (sync) failed: {}", e.getMessage());
            }
        }
        return newId;
    }

    /** delete a session from the store. The current session
     *  cannot be deleted (the user would need to switch first);
     *  attempting it throws. Other sessions are simply removed
     *  from the store's directory; their messages are not
     *  recoverable. */
    public boolean deleteSession(String sessionId) throws java.io.IOException {
        if (sessionStore == null) {
            throw new UnsupportedOperationException("SessionStore is not wired");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (sessionId.equals(appState.sessionId())) {
            throw new IllegalStateException("cannot delete the active session; load another first");
        }
        boolean removed = sessionStore.delete(sessionId);
        LOG.info("deleteSession: {} removed={}", sessionId, removed);
        return removed;
    }

    /**
     * change this engine's cwd. The session's
     * project-memory cache (the R127 {@code LayeredMemoryStore}
     * entry keyed by cwd) is NOT invalidated here — the
     * daemon's switchProject RPC invalidates it explicitly so
     * the in-flight call site can keep a reference to the
     * engine during the switch. Returns the previous cwd
     * (the engine never had a "no cwd" state, but returning
     * null if it did is a useful sanity check).
     *
     * <p>Concurrency: the engine is single-threaded for
     * {@code query} calls, so a mid-query switchProject would
     * either block on the engine's read lock or run after
     * the query completes. The setter is intentionally
     * not synchronised on the engine monitor because the
     * {@code appState.cwd} field is volatile — the happens-
     * before edge guarantees a query started after
     * {@code setCwd} returns sees the new value.
     */
    public java.nio.file.Path setCwd(java.nio.file.Path cwd) {
        if (cwd == null) throw new IllegalArgumentException("cwd is required");
        java.nio.file.Path normalised = cwd.toAbsolutePath().normalize();
        java.nio.file.Path prev = appState.cwd();
        appState.cwd(normalised);
        LOG.info("setCwd: {} -> {}", prev, normalised);
        return prev;
    }

    /** load the current session's transcript from the
     *  store, replacing any in-memory state. Called once at
     *  construction (after the optional {@code b.transcript}
     *  has been loaded into appState) and again from
     *  {@link #loadSession}. The listener is installed AFTER
     *  this returns so the initial load doesn't re-write every
     *  message. */
    private void attachSessionStore() {
        try {
            Transcript t = sessionStore.loadOrCreate(appState.sessionId());
            // materialise the session file on disk so
            // listSessions surfaces the engine's startup
            // session immediately. Without this, the file only
            // appears after the first user message — which
            // makes the engine's "current session" invisible
            // to the LeftPanel until the user types something.
            java.nio.file.Path p = t.file();
            if (p != null && !java.nio.file.Files.exists(p)) {
                java.nio.file.Files.createDirectories(p.getParent());
                java.nio.file.Files.createFile(p);
            }
            // If the store has fewer messages than the in-memory
            // transcript (e.g. the user passed a `b.transcript`
            // that was a snapshot from elsewhere), keep the
            // union. The store version is older; the in-memory
            // version is fresher (likely the CLI's resume path
            // loaded messages from a different location).
            // We just trust the in-memory state and replace the
            // store's view on the next append — i.e. the listener
            // is the source of truth going forward.
            if (t.messages().size() < appState.transcript().size()) {
                // Overwrite the on-disk file with the in-memory
                // transcript so future loads are consistent.
                overwriteTranscript(t, appState.transcript());
            } else if (t.messages().size() > appState.transcript().size()) {
                // The store is fresher. Replace in-memory.
                appState.transcript().clear();
                for (Message m : t.messages()) appState.transcript().add(m);
            }
            this.currentTranscript = t;
            // Install the listener that mirrors every new message
            // to the file. The listener holds a reference to the
            // current Transcript, so when loadSession swaps the
            // transcript, the new writes go to the new file.
            //
            // also fan out to the optional transcriptPush
            // consumer (the HTTP+WS daemon's broadcast notifier).
            // We snapshot transcriptPush into a local before the
            // null-check so a concurrent setTranscriptPush(null)
            // mid-fire doesn't NPE the engine's turn loop. A
            // misbehaving consumer is wrapped in try/catch —
            // a network blip on the WS side must not break
            // appendMessage.
            appState.onMessageAppend((msg) -> {
                Transcript cur = currentTranscript;
                if (cur != null) {
                    try { cur.append(msg); }
                    catch (java.io.IOException e) {
                        LOG.warn("failed to persist message to session file {}: {}",
                                cur.file(), e.getMessage());
                    }
                }
                var push = transcriptPush;
                if (push != null) {
                    try {
                        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
                        payload.put("action", "append");
                        payload.put("sessionId", appState.sessionId());
                        payload.put("message", msg.toMap());
                        push.accept(payload);
                    } catch (Exception e) {
                        LOG.debug("transcript push (append) failed: {}", e.getMessage());
                    }
                }
            });
            LOG.info("SessionStore wired: {} ({} messages on disk)",
                    appState.sessionId(), t.messages().size());
        } catch (java.io.IOException e) {
            LOG.warn("SessionStore attach failed: {}", e.getMessage());
        }
    }

    /** rewrite the on-disk transcript with the in-memory
     *  contents. Used when the in-memory transcript is fresher
     *  than the file (e.g. a CLI resume that loaded messages
     *  from another store). The file is truncated and re-written
     *  with one JSONL line per message. */
    private void overwriteTranscript(Transcript target, java.util.List<Message> messages) {
        try {
            java.nio.file.Path file = target.file();
            if (file == null) return;
            java.nio.file.Files.createDirectories(file.getParent());
            try (var writer = java.nio.file.Files.newBufferedWriter(file,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Message m : messages) {
                    String line = serializeMessageForFile(m);
                    writer.write(line);
                    writer.write('\n');
                }
            }
        } catch (java.io.IOException e) {
            LOG.warn("overwriteTranscript failed: {}", e.getMessage());
        }
    }

    /** mirror of {@code Transcript.serialize} — write a
    /**
     * merge any project- or user-scope rules persisted on
     * disk into the in-memory allow list. We load from two
     * locations:
     *   1. {@code <cwd>/.aethercode/permissions.json} — the
     *      project scope. This file is the canonical record of
     *      what the user has decided to always allow inside this
     *      repo.
     *   2. {@code ~/.aethercode/permissions.json} — the user
     *      scope. Cross-project rules for the current OS user
     *      (e.g. "always allow npm install").
     *
     * <p>Existing in-memory allow rules (from settings.json) are
     * preserved; persisted rules are appended. If a persisted
     * rule's tool/prompt is already covered, the persisted one
     * is a duplicate — we don't deduplicate (the cost is small,
     * and deduping would risk hiding user intent).
     */
    private static void mergePersistedRules(SettingsPermissions perms) {
        java.util.List<java.nio.file.Path> files = java.util.List.of(
                java.nio.file.Paths.get(System.getProperty("user.dir"))
                        .resolve(".aethercode").resolve("permissions.json"),
                java.nio.file.Paths.get(System.getProperty("user.home"))
                        .resolve(".aethercode").resolve("permissions.json")
        );
        java.util.List<Rule> appended = new java.util.ArrayList<>(perms.allow == null ? java.util.List.of() : perms.allow);
        for (var file : files) {
            if (!java.nio.file.Files.exists(file)) continue;
            try {
                String body = java.nio.file.Files.readString(file);
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(body, java.util.Map.class);
                Object arr = raw.get("rules");
                if (!(arr instanceof java.util.List<?> list)) continue;
                for (Object e : list) {
                    if (!(e instanceof java.util.Map)) continue;
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) e;
                    appended.add(Rule.fromMap(m));
                }
            } catch (Exception ex) {
                // Skip a malformed file rather than abort the
                // whole engine. The TUI can show the user a
                // warning toast if it wants.
                LOG.warn("R86: skipping malformed permissions file {}: {}",
                        file, ex.toString());
            }
        }
        perms.allow = appended;
    }

    /**
     *  single message as a JSONL line. Kept local to avoid
     *  exposing Transcript's private serializer. */
    private static String serializeMessageForFile(Message m) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m_ = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .findAndRegisterModules();
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("id", m.id());
            out.put("role", m.role().name().toLowerCase());
            out.put("content", m.content());
            out.put("timestamp", m.timestamp().toString());
            out.put("metadata", m.metadata());
            return m_.writeValueAsString(out);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize message: " + e.getMessage(), e);
        }
    }

    @Override public String sessionId() { return appState.sessionId(); }
    @Override public List<Tool> tools() { return appState.toolPool(); }

    public void setPlanModeSuffix(String suffix) { this.planModeSuffix = suffix == null ? "" : suffix; }
    public String planModeSuffix() { return planModeSuffix; }

    /** install (or replace) the {@link org.aethercode.memory.MemoryLifecycle}.
     *  Idempotent: a second call with a null value is a no-op. The
     *  daemon uses this to wire the real lifecycle after the engine
     *  is built but before any query is issued. */
    public void setMemoryLifecycle(org.aethercode.memory.MemoryLifecycle lifecycle) {
        if (lifecycle == null) return;
        this.memoryLifecycle = lifecycle;
    }

    /** accessor for tests + the daemon shutdown path. */
    public org.aethercode.memory.MemoryLifecycle memoryLifecycle() { return memoryLifecycle; }

    @Override
    public Stream<StreamEvent> query(String userInput) {
        return query(userInput, null);
    }

    /**
     * run a single user prompt with a per-call
     * {@link ChatClient} override. When {@code override}
     * is non-null the LLM stream + cost record for THIS
     * call use {@code override} instead of the engine's
     * default. The engine's {@code chatClient} field is
     * not mutated, so concurrent callers (a main-loop
     * query and a workflow executor's child session) do
     * not race on a shared field. The override is
     * captured into the per-call {@code effectiveChatClient}
     * local in {@link QueryEngine#query(String,
     * ChatClient)}. Pass {@code null} to use the engine's
     * default — that's what the 1-arg overload does.
     */
    public Stream<StreamEvent> query(String userInput, ChatClient override) {
        return query(userInput, override, null);
    }

    /**
     * run a query with an optional per-call tool pool
     * override. The override is installed on the engine's
     * {@code AppState} for the duration of the query via a
     * thread-local, then cleared in a finally block. Used by
     * {@code AgentTool} to scope a subagent's tool set to a
     * role preset (e.g. an {@code explore} agent can't call
     * {@code file_write}). {@code toolPoolOverride} may be
     * {@code null} or empty — both mean "use the engine's
     * default pool". The override is a superset/replacement,
     * not an additive filter.
     */
    @Override
    public Stream<StreamEvent> query(String userInput, ChatClient override, List<org.aethercode.core.tool.Tool> toolPoolOverride) {
        if (toolPoolOverride != null && !toolPoolOverride.isEmpty()) {
            appState.pushToolPoolOverride(toolPoolOverride);
        }
        try {
            return queryInternal(userInput, override);
        } finally {
            if (toolPoolOverride != null && !toolPoolOverride.isEmpty()) {
                appState.popToolPoolOverride();
            }
        }
    }

    private Stream<StreamEvent> queryInternal(String userInput, ChatClient override) {
        // count this query in the per-session
        // stats so the summary RPC's `queries` field
        // reflects the actual turn-loop iterations.
        if (sessionStats != null) {
            sessionStats.recordQuery();
            // Each fresh query resets "state" to "running"
            // — the prior RunEnd's state would otherwise
            // linger if the TUI asks for a summary in
            // the middle of a long-running query.
            sessionStats.setState("running");
        }
        // backpressure gate. When the engine is over
        // its memory threshold, refuse the query up front
        // rather than letting it add to the pressure. The
        // caller (AetherCodeMethods.query) catches
        // {@link BackpressureException} and surfaces it as a
        // structured RPC error so the desktop can show
        // "System busy, please retry later" without spinning.
        if (concurrencyController != null && concurrencyController.backpressured()) {
            org.aethercode.core.concurrency.EngineStats s = concurrencyController.snapshot();
            throw new BackpressureException(
                    "Engine is backpressured (memory at " + s.memPct + "%). "
                            + "Please retry in a few seconds. Reduce the concurrency profile "
                            + "via setConcurrencyProfile(low) or close other apps to free memory.",
                    s);
        }
        // each user query becomes a Task. The Task ID is emitted as a
        // SideNote at the start of the stream so the TUI / CLI can correlate
        // engine events with task lifecycle. The task stays RUNNING until
        // the stream completes; transitions are visible in the
        // TaskRegistry.
        org.aethercode.tasks.TaskRegistry registry = org.aethercode.tasks.TaskRegistry.instance();
        org.aethercode.tasks.Task task = registry.create(
                org.aethercode.tasks.TaskType.USER, userInput, null);
        registry.updateStatus(task.id(), org.aethercode.tasks.TaskStatus.RUNNING);
        // recall the relevant memory files for this query and inject
        // them as a system-prompt section. We scan USER, PROJECT, and LOCAL
        // memory directories (in that priority order) and concatenate the
        // results, deduplicating by path. Surfaced paths are tracked in
        // AppState so subsequent turns in the same session don't re-inject
        // the same memory.
        // thread this through MemoryLifecycle so the lifecycle
        // gets the "onQueryStart" hook (creates the working buffer,
        // audits, runs the throttled decay pass) and can fire
        // "onMemoryRecallHit" on each surfaced file.
        if (memoryLifecycle != null) {
            memoryLifecycle.onQueryStart(userInput);
            // R280: bind the project cwd on the lifecycle so the post-query
            // hook can append a session-change entry to PROJECT_MEMORY.md.
            // This is best-effort: a missing cwd just means no change entry.
            try {
                if (appState != null && appState.cwd() != null) {
                    memoryLifecycle.setProjectCwd(appState.cwd().toString());
                }
            } catch (Exception projCwdEx) {
                LOG.debug("R280 setProjectCwd skipped: " + projCwdEx.getMessage());
            }
        }
        String projectMemorySection = buildProjectMemorySection();
        String memorySection = buildMemorySection(userInput);
        // also recall top-K experience records (prior round) and
        // render them as a separate system-prompt section. This closes
        // the R231 "write but never read" gap. Hits bump utility so
        // the next call sees a re-ranked list.
        String experienceSection = buildExperienceSection(userInput);
        // also recall SESSION k/v entries (R127 design intent
        // finally implemented) — anything the user said earlier in
        // this same session that was put into sessionStore.
        String sessionKvSection = buildSessionKvSection(userInput);
        // R280: project memory section precedes everything else.
        // The user's brief: "project memory needs to be put at the
        // front, with this session's entries excluded (the session
        // already knows its own work via the transcript)".
        String top    = projectMemorySection;
        String middle = combineThreeSections(memorySection, experienceSection, sessionKvSection);
        String combined = combineSections(top, middle);
        queryEngine.setMemorySection(combined);
        Stream<StreamEvent> inner = queryEngine.query(userInput, override);
        final String sectionForNote = memorySection;
        return StreamSupport.stream(new java.util.Spliterators.AbstractSpliterator<StreamEvent>(
                Long.MAX_VALUE,
                java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
            private java.util.Spliterator<StreamEvent> src = inner.spliterator();
            private boolean taskAnnounced = false;
            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super StreamEvent> action) {
                if (!taskAnnounced) {
                    action.accept(new StreamEvent.SideNote(
                            "task", "task " + task.id() + " started"));
                    // a second SideNote announces how many memories
                    // were recalled. Empty when no memories matched.
                    // also count "## Past experience" headers in
                    // the combined section so the user sees both numbers.
                    // R280: also surface the project-memory section size so
                    // the user knows what was injected at the top.
                    if (sectionForNote != null && !sectionForNote.isBlank()) {
                        int memCount = countRecalledFiles(sectionForNote);
                        int expCount = countExperienceEntries(sectionForNote);
                        String label = "recalled " + memCount + " memory file(s)";
                        if (expCount > 0) label += " + " + expCount + " experience(s)";
                        if (projectMemorySection != null && !projectMemorySection.isBlank()) {
                            int projChanges = countProjectMemoryChanges(projectMemorySection);
                            label += " + project memory (" + projChanges + " session-change line(s))";
                        }
                        action.accept(new StreamEvent.SideNote("memory", label));
                    }
                    taskAnnounced = true;
                }
                if (src == null) return false;
                boolean has = src.tryAdvance(ev -> {
                    if (ev instanceof StreamEvent.RunEnd re) {
                        registry.updateStatus(task.id(),
                                re.stopReason() != null && re.stopReason().startsWith("error")
                                        ? org.aethercode.tasks.TaskStatus.FAILED
                                        : org.aethercode.tasks.TaskStatus.COMPLETED);
                        // surface the stop reason + any error
                        // text into SessionStats so the summary RPC
                        // always has a fresh "state" + "last_error"
                        // regardless of pass/fail. The user explicitly
                        // asked for "a summary regardless of whether
                        // the task ended correctly" — and the most
                        // common reason the TUI shows a stale
                        // "running" was that the engine never updated
                        // anything on loop-detected / error stops.
                        if (sessionStats != null) {
                            String reason = re.stopReason() == null ? "end_turn" : re.stopReason();
                            sessionStats.setState(reason);
                            if (reason.startsWith("error")
                                    || reason.startsWith("loop_")
                                    || reason.startsWith("max_iterations")) {
                                sessionStats.setLastError(
                                        "run stopped: " + reason);
                            } else {
                                sessionStats.setLastError("");
                            }
                        }
                        // lifecycle hook on query end. The
                        // orchestrator audits, clears the working
                        // buffer, and runs Tier-2 / Tier-3 experience
                        // extraction (case-based by default; strategy
                        // gated on heuristic pattern match). The
                        // transcript snapshot is taken from the app
                        // state, which is the source of truth.
                        if (memoryLifecycle != null) {
                            boolean success = re.stopReason() != null
                                    && !re.stopReason().startsWith("error")
                                    && !re.stopReason().startsWith("loop_")
                                    && !re.stopReason().startsWith("max_iterations");
                            try {
                                memoryLifecycle.onQueryEnd(
                                        success,
                                        appState == null ? java.util.List.of() : appState.transcript(),
                                        null);
                            } catch (Exception lifecycleEx) {
                                LOG.warn("memory lifecycle onQueryEnd failed: {}",
                                        lifecycleEx.getMessage());
                            }
                        }
                    }
                    action.accept(ev);
                });
                if (!has) {
                    // Defensive: if inner ended without a RunEnd, mark completed.
                    registry.updateStatus(task.id(),
                            org.aethercode.tasks.TaskStatus.COMPLETED);
                    src = null;
                    return false;
                }
                return true;
            }
        }, false);
    }

    /** run MemoryRecall against USER, PROJECT, and LOCAL scopes for
     *  the current query and return a single system-prompt section. Empty
     *  string if no memory is relevant. Surfaced paths are marked so
     *  subsequent turns in the same session don't re-inject the same
     *  memory. Errors are swallowed (memory is a best-effort
     *  optimisation, not a correctness requirement).
     *
     *  <p>prior round: dedup is now O(n) via a {@link java.util.HashSet}
     *  keyed on the recalled file path, instead of the previous
     *  O(n²) {@code stream().anyMatch} scan. With 3 scopes × N
     *  files each, that turned a 9×N² into a 9×N loop. */
    private String buildMemorySection(String userInput) {
        if (memoryRecall == null) return "";
        List<org.aethercode.memory.MemoryRecall.RecalledFile> all = new ArrayList<>();
        // O(n) dedup key. The first scope to surface a given
        // path wins (USER > PROJECT > LOCAL).
        java.util.Set<java.nio.file.Path> seen = new java.util.HashSet<>();
        try {
            // The "agent type" is the model id by default. The same memory
            // file is shared across all models of the same family.
            String agentType = appState.mainLoopModel() == null
                    ? "default" : appState.mainLoopModel();
            java.util.List<java.nio.file.Path> surfaced = new ArrayList<>(
                    appState.surfacedMemories());
            for (org.aethercode.memory.MemoryScope scope : org.aethercode.memory.MemoryScope.values()) {
                java.nio.file.Path dir = org.aethercode.memory.MemoryPaths.agentMemoryDir(
                        agentType, scope, appState.cwd());
                List<org.aethercode.memory.MemoryRecall.RecalledFile> recalled =
                        memoryRecall.recall(dir, userInput, recentToolNames(), surfaced);
                for (var f : recalled) {
                    if (!seen.add(f.path)) continue;
                    all.add(f);
                    appState.markMemorySurfaced(f.path.toAbsolutePath());
                    // notify the lifecycle that this scope hit
                    // a memory file. The lifecycle audits + bumps its
                    // recallHits counter; the actual on-disk touch() of
                    // the file-backed item is done by the daemon (R232
                    // will close the loop with a proper FileBackedMemory
                    // handle wired in).
                    if (memoryLifecycle != null) {
                        memoryLifecycle.onMemoryRecallHit(
                                scope.name(), f.name, f.path.toString());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("memory recall failed: {}", e.getMessage());
            return "";
        }
        if (all.isEmpty()) return "";
        return org.aethercode.memory.MemoryRecall.render(all);
    }

    /**
     * recall top-K experience records (prior round) relevant to
     * the current input. Returns a markdown section ready to be
     * appended to the system prompt. Delegates the actual store scan
     * to {@link org.aethercode.memory.MemoryLifecycle#recallExperience}
     * so the lifecycle owns the audit + utility-bump side effects.
     */
    private String buildExperienceSection(String userInput) {
        if (memoryLifecycle == null) return "";
        try {
            var hits = memoryLifecycle.recallExperience(userInput,
                    org.aethercode.memory.MemoryLifecycle.DEFAULT_RECALL_PER_SCOPE);
            return org.aethercode.memory.MemoryLifecycle.renderExperienceSection(hits);
        } catch (Exception e) {
            LOG.warn("experience recall failed: {}", e.getMessage());
            return "";
        }
    }

    /** glue two non-empty sections with a blank line. */
    private static String combineSections(String a, String b) {
        boolean an = a == null || a.isBlank();
        boolean bn = b == null || b.isBlank();
        if (an && bn) return "";
        if (an) return b;
        if (bn) return a;
        return a + "\n\n" + b;
    }

    /** glue three non-empty sections with blank lines. */
    private static String combineThreeSections(String a, String b, String c) {
        return combineSections(combineSections(a, b), c);
    }

    // ----------------------------------------------------------------
    // R280: project memory section
    // ----------------------------------------------------------------

    /**
     * R280: build the project-memory system-prompt section.
     *
     * <p>Reads {@code PROJECT_MEMORY.md} from {@code appState.cwd()}
     * via the {@link MemoryLifecycle}'s underlying
     * {@link LayeredMemoryStore}. The section's purpose is to put
     * project-level context (info + recent session-change log) at the
     * top of the system prompt. Sessions from {@link #appState}'s
     * current {@code sessionId} are filtered out — the session
     * already sees its own work via the transcript.
     *
     * <p>Empty string when:
     * <ul>
     *   <li>no memory lifecycle wired (no daemon) — gracefully
     *       degrades to nothing</li>
     *   <li>no cwd set — project memory is project-scoped, no cwd
     *       → no project memory</li>
     *   <li>PROJECT_MEMORY.md does not exist yet (a fresh project)
     *       — nothing to inject</li>
     * </ul>
     */
    private String buildProjectMemorySection() {
        try {
            if (memoryLifecycle == null) return "";
            org.aethercode.memory.LayeredMemoryStore store = memoryLifecycle.store();
            if (store == null) return "";
            if (appState == null || appState.cwd() == null) return "";
            String cwd = appState.cwd().toString();
            String sid = appState.sessionId();
            String body = store.readProjectMemoryExcluding(cwd, sid);
            if (body == null || body.isBlank()) return "";
            String header = "# Project memory\n"
                    + "_Injected at the top of every prompt for this project. Entries from this session "
                    + "are filtered out (current session context is already in your transcript)._\n";
            return header + body;
        } catch (Exception e) {
            LOG.debug("R280 buildProjectMemorySection skipped: " + e.getMessage());
            return "";
        }
    }

    /** Count {@code [<sessionId> <iso>]} lines in a project-memory section
     *  for the SideNote label. */
    private static int countProjectMemoryChanges(String rendered) {
        if (rendered == null || rendered.isBlank()) return 0;
        int n = 0;
        for (String line : rendered.split("\n", -1)) {
            // match the on-disk format: [<non-blank> <iso>] desc
            if (line.startsWith("[") && line.contains("] ")
                    && Character.isLetterOrDigit(line.codePointAt(1))) {
                n++;
            }
        }
        return n;
    }

    /** recall the current session's k/v entries. Best-effort. */
    private String buildSessionKvSection(String userInput) {
        if (memoryLifecycle == null) return "";
        String sid = appState == null ? null : appState.sessionId();
        if (sid == null || sid.isBlank()) return "";
        try {
            var hits = memoryLifecycle.recallSessionKv(sid, userInput, 16);
            return org.aethercode.memory.MemoryLifecycle.renderSessionKvSection(hits);
        } catch (Exception e) {
            LOG.warn("session k/v recall failed: {}", e.getMessage());
            return "";
        }
    }

    /** tool names used in the last few messages — used as a
     *  recall signal. Returns the tool name for each tool_use block in
     *  the most recent 4 messages (deduplicated). */
    private List<String> recentToolNames() {
        java.util.List<Message> transcript = appState.transcript();
        int n = Math.min(4, transcript.size());
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (int i = transcript.size() - n; i < transcript.size(); i++) {
            if (i < 0) continue;
            Message m = transcript.get(i);
            if (m == null) continue;
            for (org.aethercode.core.message.ContentBlock b : m.content()) {
                if (b instanceof org.aethercode.core.message.ContentBlock.ToolUseBlock u) {
                    out.add(u.name());
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** cheap "how many files were recalled" counter for the
     *  SideNote. The MemoryRecall.render() output uses "## filename.md"
     *  as the per-file header so we count those. prior round: replaced
     *  the hand-rolled indexOf loop with a single split+count — the
     *  indexOf approach scanned the rendered text once per file
     *  (O(n) per match, O(n) matches = O(n²) in the worst case
     *  when the headers cluster). split gives us the parts directly.
     * also subtract the depth-2 "## Past experience" header
     *  (rendered by MemoryLifecycle.renderExperienceSection) so the
     *  count is the actual file count, not (files + section). */
    private static int countRecalledFiles(String rendered) {
        if (rendered == null || rendered.isBlank()) return 0;
        int n = 0;
        for (String line : rendered.split("\n", -1)) {
            if (!line.startsWith("## ")) continue;
            // Exclude the R232 "## Past experience" section heading
            // and the R232 "## Relevant memories (auto-recalled)"
            // section heading. Both start with "## " followed by a
            // recognised section word, NOT a filename.
            String tail = line.substring(3).trim();
            if (tail.startsWith("Past experience")) continue;
            if (tail.startsWith("Relevant memories")) continue;
            n++;
        }
        return n;
    }

    /** count experience entries in the rendered section. Each
     *  entry starts with "### N. title" (depth-3 header). */
    private static int countExperienceEntries(String rendered) {
        if (rendered == null || rendered.isBlank()) return 0;
        int n = 0;
        for (String line : rendered.split("\n", -1)) {
            if (line.startsWith("### ")) n++;
        }
        return n;
    }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        return new Builder()
                .sessionId(appState.sessionId())
                .cwd(appState.cwd())
                .model(appState.mainLoopModel())
                .permissionMode(appState.permissionMode())
                .tools(appState.toolPool());
    }

    public static final class Builder {
        private String sessionId = UUID.randomUUID().toString();
        private Path cwd = Path.of("").toAbsolutePath();
        private String model = "MiniMax-M3";
        private String apiKey;
        /** active agent role, drives the role-scoped
         *  rules subdirectory. Empty by default (no
         *  role layer). */
        private String role = "";
        private ChatClient.Options options = SpringAiChatClient.minimaxDefaults();
        /** optional provider spec (from
         *  {@code providers.yaml} or a programmatic
         *  call). When set, the engine builds the
         *  ChatClient from the spec's baseUrl +
         *  apiKeyEnv. The legacy {@link #apiKey} +
         *  {@link #options} still work for callers
         *  that don't have a provider spec (e.g. the
         *  --api-key / --base-url CLI path). */
        private org.aethercode.core.providers.ProviderSpec provider;
        /** per-query model-turn cap. Default 50 — bounds runaway tool
         *  loops but leaves comfortable headroom for real work. A simple
         *  chat finishes in 1 turn; a short tool flow uses 3-5; a long
         *  multi-step refactor can easily reach 20-30. Set to 0 or -1 to
         *  disable the cap (rely on the loop detector alone). The loop
         *  detector (window 8 / threshold 3) catches pathological "same
         *  tool call 3 times in a row" patterns regardless. */
        private int maxTurnsPerQuery = 50;
        /** loop-detector sliding window. The same tool-call
         *  fingerprint appearing this many times in the window triggers
         *  a stop. Default 8. Pass 0 / negative to disable. */
        private int loopDetectWindow = 8;
        /** loop-detector trigger threshold. The same tool-call
         *  fingerprint appearing this many times in the window triggers
         *  a stop. Default 3. */
        private int loopDetectThreshold = 3;
        /** per-session context window in tokens. Default 200K (matches
         *  Claude 3 family defaults). Set to 1_000_000 for million-token models
         *  (e.g. MiniMax-M3 advertises a 1M context). When non-positive, the
         *  default AutoCompact context window is used. */
        private int contextWindow = 0;
        private org.aethercode.core.permission.PermissionMode permissionMode =
                org.aethercode.core.permission.PermissionMode.DEFAULT;
        // opt-in to the AetherCodeAgent "create-agent"
        // system-prompt fragment. Defaults to {@code false}
        // so existing callers (unit tests, the REPL's
        // --print path, TUI's /prompt override) see no
        // behaviour change. The daemon (HTTP / stdio
        // paths in Main.java) sets this to {@code true}
        // so a fresh daemon session ships with the
        // multi-step planning / no-early-stop / batch-
        // tool-calls prompt that the AetherCodeAgent
        // facade documents. The default matters because
        // a future tightening of the AetherCodeAgent
        // fragment should not silently change the
        // engine's prompt for tests that don't opt in.
        private boolean createAgent = false;
        private SettingsPermissions permissions = SettingsPermissions.empty();
        // permission matrix (loaded from .aethercode/config.json). null = disabled (legacy).
        private org.aethercode.config.PermissionMatrix permissionMatrix = null;
        private ToolPermissionPrompter prompter;
        private List<Tool> tools = StandardTools.all();
        /** builder stores the SystemPrompt object (not
         *  just the rendered String) so a caller that wants
         *  section-level provenance can call renderWithSources()
         *  on the value passed in. The constructor unwraps
         *  it to a String when it hands it to the QueryEngine. */
        private org.aethercode.prompts.SystemPrompt systemPrompt;
        private HookRegistry hookRegistry;
        /** optional phase-budget tracker. When set, the
         *  engine wires a {@code PhaseBudgetHook} into the
         *  registry and exposes {@code phaseTracker()} for the
         *  daemon / TUI to read and update. Pass null to
         *  disable the budget (the default for backward
         *  compat). */
        private org.aethercode.hooks.builtin.PhaseTracker phaseTracker;
        /** optional multi-session manager. */
        private org.aethercode.sdk.SessionManager sessionManager;
        private Compactor compactor;
        private Transcript transcript;
        private CostTracker costTracker;
        /** optional {@code MemoryRecall} override. When {@code null}
         *  the engine builds a default one from the same chat client
         *  (so the side-query LLM pass is available). */
        private org.aethercode.memory.MemoryRecall memoryRecall;
        /** optional {@code MemoryLifecycle} override. When {@code null}
         *  the engine builds a default disabled one (so existing
         *  test/CLI paths that don't pass a lifecycle still work). The
         *  daemon wires the real one via {@code builder.memoryLifecycle(...)}. */
        private org.aethercode.memory.MemoryLifecycle memoryLifecycle;
        /** optional {@code SessionStore} for multi-session
         *  persistence. When {@code null} (the default) the engine
         *  is single-session and writes nothing to disk. */
        private org.aethercode.core.transcript.SessionStore sessionStore;
        /** project-tier skill roots ({@code <cwd>/.aethercode/skills/}).
         *  Multiple roots are scanned in declaration order; project-tier
         *  wins on name collision against the user tier. When the list is
         *  empty AND {@link #skillRegistry} is null, the engine builds no
         *  registry. */
        private List<Path> skillProjectDirs;
        /** user-tier skill root ({@code ~/.minimax/skills/}). Single
         *  directory; the typical Mavis layout. */
        private List<Path> skillDirs;
        /** reload interval for the skill registry. Default 10s. */
        private java.time.Duration skillReloadInterval = java.time.Duration.ofSeconds(10);
        /** pre-built skill registry (skips the dir-based
         *  construction). The CLI / daemon build their own from
         *  flags / config. */
        private org.aethercode.core.skill.SkillRegistry skillRegistry;
        /** directory containing Mavis agents
         *  ({@code ~/.minimax/agents/}). */
        private Path agentsDir;
        /** reload interval for the agent registry. Default 10s. */
        private java.time.Duration agentReloadInterval = java.time.Duration.ofSeconds(10);
        /** pre-built agent registry. */
        private org.aethercode.core.agent.AgentRegistry agentRegistry;
        /** pre-built concurrency controller. */
        private org.aethercode.core.concurrency.ConcurrencyController concurrencyController;
        /** initial concurrency profile name
         *  ({@code low} / {@code normal} / {@code high}). */
        private String concurrencyProfileName;
        /** throttle threshold percentage. Default 75. */
        private int concurrencyThrottlePct = 75;
        /** backpressure threshold percentage. Default 88. */
        private int concurrencyBackpressurePct = 88;
        /** memory monitor polling interval. Default 5s. */
        private long concurrencyMonitorMs = 5_000L;

        public Builder sessionId(String s) { this.sessionId = s; return this; }
        public Builder cwd(Path p) { this.cwd = p; return this; }
        public Builder model(String m) { this.model = m; return this; }
        public Builder apiKey(String k) { this.apiKey = k; return this; }
        /** install a provider spec. The engine
         *  will build its ChatClient from this spec's
         *  baseUrl + the apiKeyEnv-declared env var.
         *  When both a spec and a {@link #apiKey(String)}
         *  are set, the explicit key wins. */
        public Builder provider(org.aethercode.core.providers.ProviderSpec p) {
            this.provider = p;
            return this;
        }
        public Builder options(ChatClient.Options o) { this.options = o; return this; }
        /** set the active agent role. The role
         *  drives the per-agent role-scoped rules
         *  subdirectory (see
         *  {@code aethercode-prompts/docs/prior round-RULES-INJECTOR.md}).
         *  When non-empty, the engine loads rules from
         *  {@code <cwd>/.aethercode/rules/roles/<role>/}
         *  and {@code <home>/.aethercode/rules/roles/<role>/}
         *  in addition to the base rules directories.
         *  Names that fail the
         *  {@code RulesLoader.SAFE_ROLE} regex are
         *  silently treated as empty by the loader, so
         *  user-controlled input cannot inject
         *  path-traversal sequences. Default: empty
         *  (no role-scoped layer). */
        public Builder role(String r) { this.role = r == null ? "" : r; return this; }
        /** cap on model turns per query. {@code -1} or {@code 0} disables. */
        public Builder maxTurnsPerQuery(int n) { this.maxTurnsPerQuery = n; return this; }
        /** configure the loop detector. The same tool-call fingerprint
         *  appearing {@code threshold} times in the last {@code window}
         *  turns triggers an early stop. Pass 0 / negative to disable
         *  the detector (rely on {@link #maxTurnsPerQuery} alone). */
        public Builder loopDetector(int window, int threshold) {
            this.loopDetectWindow = window;
            this.loopDetectThreshold = threshold;
            return this;
        }
        /** per-session context window in tokens. 0 = use AutoCompact
         *  default (200K). 1_000_000 for million-token models. */
        public Builder contextWindow(int tokens) { this.contextWindow = tokens; return this; }
        public Builder permissionMode(org.aethercode.core.permission.PermissionMode m) { this.permissionMode = m; return this; }
        public Builder permissions(SettingsPermissions p) { this.permissions = p; return this; }
        // opt into the AetherCodeAgent create-agent system-prompt
        // fragment. Defaults to false so existing callers (unit tests,
        // --print, TUI /prompt overrides) see no change. The daemon's
        // Main.java flips this on so a fresh session ships with the
        // todo-first / no-early-stop / batch-tool-calls prompt that
        // closes the gap where models ignore the existing
        // "Phase 1 — Plan" guidance on the first turn. The setter is
        // idempotent — calling it twice with the same value is a
        // no-op. The fragment is appended to the SystemPrompt's
        // identity section, so the {@code systemPrompt(String)}
        // setter still wins if the caller supplied their own
        // prompt.
        public Builder createAgent(boolean on) { this.createAgent = on; return this; }
        // install the permission matrix. When non-null, the engine
        // wraps the ProjectPermissionPolicy in a MatrixPermissionPolicy so
        // per-tool × per-path × per-op-kind decisions are made BEFORE the
        // existing deny/ask/allow rule list.
        public Builder permissionMatrix(org.aethercode.config.PermissionMatrix m) {
            this.permissionMatrix = m;
            return this;
        }
        public Builder prompter(ToolPermissionPrompter p) { this.prompter = p; return this; }
        public Builder tools(List<Tool> t) { this.tools = t; return this; }
        /** backwards-compat setter. Stores the raw
         *  String so the QueryEngine still gets the same
         *  text the caller intended. Callers that want
         *  section-level provenance (for the /prompt RPC
         *  or for a future debug panel) should prefer
         *  the {@link #systemPrompt(SystemPrompt)} overload. */
        public Builder systemPrompt(String s) {
            // Wrap a single-section SystemPrompt around
            // the supplied String so the rest of the
            // engine can use the new field type without
            // having to special-case "raw String" again.
            this.systemPrompt = org.aethercode.prompts.SystemPrompt.builder()
                    .identity(s == null ? "" : s)
                    .build();
            return this;
        }
        /** install a pre-built SystemPrompt (the
         *  one produced by the default-builder path or
         *  one assembled by the caller). The engine
         *  hands the rendered String to the QueryEngine
         *  for the LLM call site, and keeps the full
         *  object so the {@code getSystemPrompt} RPC
         *  can expose section provenance. */
        public Builder systemPrompt(org.aethercode.prompts.SystemPrompt p) {
            this.systemPrompt = p;
            return this;
        }
        public Builder hookRegistry(HookRegistry h) { this.hookRegistry = h; return this; }
        /** install a {@link org.aethercode.hooks.builtin.PhaseTracker}
         *  for per-phase tool-call budget enforcement. Pass
         *  {@code null} to disable (default). When non-null
         *  the engine auto-registers a
         *  {@code PhaseBudgetHook} into the hook registry. */
        public Builder phaseTracker(org.aethercode.hooks.builtin.PhaseTracker t) { this.phaseTracker = t; return this; }
        /** install a {@link SessionManager} for
         *  multi-session routing. When set, the
         *  AetherCodeMethods RPCs that take a sessionId
         *  route to the engine registered for that
         *  session. Pass null (default) for the
         *  single-engine path. */
        public Builder sessionManager(org.aethercode.sdk.SessionManager m) { this.sessionManager = m; return this; }
        public Builder compactor(Compactor c) { this.compactor = c; return this; }
        public Builder transcript(Transcript t) { this.transcript = t; return this; }
        public Builder costTracker(CostTracker t) { this.costTracker = t; return this; }
        /** install a custom {@code MemoryRecall}. When omitted the
         *  engine builds a default one. Useful in tests where you want
         *  to inject a stub. */
        public Builder memoryRecall(org.aethercode.memory.MemoryRecall r) { this.memoryRecall = r; return this; }
        /** install a {@code MemoryLifecycle}. When omitted
         *  the engine builds a disabled no-op (so existing test paths
         *  that don't need the lifecycle still pass). The daemon wires
         *  a real one with the LayeredMemoryStore + audit + ForgettingPolicy
         *  collaborators. */
        public Builder memoryLifecycle(org.aethercode.memory.MemoryLifecycle l) { this.memoryLifecycle = l; return this; }
        /** install a {@code SessionStore} for multi-session
         *  persistence. When set, every {@code appState.appendMessage}
         *  is mirrored to a per-session JSONL file under the store's
         *  directory, and the engine supports {@code listSessions /
         *  loadSession / createSession / deleteSession}. The CLI
         *  has used this since prior round; the daemon starts using it in
         *  R106. Pass {@code null} (the default) for unit tests
         *  that don't need persistence. */
        public Builder sessionStore(org.aethercode.core.transcript.SessionStore s) { this.sessionStore = s; return this; }
        /** project-tier skill roots (one entry per project
         *  directory to scan). Project wins on name collision. */
        public Builder skillProjectDirs(List<Path> dirs) { this.skillProjectDirs = dirs; return this; }
        /** user-tier skill root directory. */
        public Builder skillDirs(List<Path> dirs) { this.skillDirs = dirs; return this; }
        /** override the default 10s reload interval. */
        public Builder skillReloadInterval(java.time.Duration d) { this.skillReloadInterval = d; return this; }
        /** install a pre-built registry (skips the
         *  dir-based construction). */
        public Builder skillRegistry(org.aethercode.core.skill.SkillRegistry r) { this.skillRegistry = r; return this; }
        /** Mavis agents directory. */
        public Builder agentsDir(Path p) { this.agentsDir = p; return this; }
        /** override the default 10s reload interval. */
        public Builder agentReloadInterval(java.time.Duration d) { this.agentReloadInterval = d; return this; }
        /** install a pre-built registry. */
        public Builder agentRegistry(org.aethercode.core.agent.AgentRegistry r) { this.agentRegistry = r; return this; }
        /** install a pre-built controller. */
        public Builder concurrencyController(org.aethercode.core.concurrency.ConcurrencyController c) { this.concurrencyController = c; return this; }
        /** set the initial profile. */
        public Builder concurrencyProfile(String name) { this.concurrencyProfileName = name; return this; }
        /** override the throttle threshold (default 75%). */
        public Builder concurrencyThrottlePct(int pct) { this.concurrencyThrottlePct = pct; return this; }
        /** override the backpressure threshold (default 88%). */
        public Builder concurrencyBackpressurePct(int pct) { this.concurrencyBackpressurePct = pct; return this; }
        /** override the memory monitor interval (default 5s). */
        public Builder concurrencyMonitorMs(long ms) { this.concurrencyMonitorMs = ms; return this; }

        public AetherCodeEngine build() { return new AetherCodeEngine(this); }
    }

    private static org.aethercode.prompts.SystemPrompt defaultSystemPrompt(Builder b) {
        // load user-supplied project / global rules. Failures are swallowed
        // by the loader (logged at debug) so a missing or unreadable rules
        // directory can never block an engine boot.
        // forward the active role so the role-scoped
        // rules subdirectory is included in the prompt.
        // return the SystemPrompt object (not just the String) so
        // the /prompt RPC can later call renderWithSources() to expose
        // section-level provenance to the user.
        // read the project's workflow mode from
        // <cwd>/.aethercode/config.json and gate the design-first section
        // accordingly. Default is "design-first" (the new behaviour);
        // "legacy" disables it. Missing file / bad file -> design-first.
        // when the builder opted into the create-agent
        // fragment, append it to the identity section so the
        // model reads "what you are" + "what to do for
        // multi-step tasks" as a single block at the start of
        // the prompt. We don't add a new section type
        // (SystemPrompt is immutable from the caller's side);
        // we just extend the existing identity String.
        java.nio.file.Path userHome = null;
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            userHome = java.nio.file.Path.of(home);
        }
        String rules = org.aethercode.prompts.RulesLoader.load(b.cwd, userHome, b.role);
        org.aethercode.config.AetherCodeConfig cfg =
                org.aethercode.config.ConfigEngine.loadFromProjectRoot(b.cwd);
        // null -> use the SystemPrompt's own default (the design-first
        // block). Empty string -> explicitly disable. This way
        // .aethercode/config.json with workflow="legacy" cleanly
        // suppresses the section.
        String designFirst = "legacy".equalsIgnoreCase(cfg.workflow) ? "" : null;
        // assemble the identity section. Default behaviour is
        // the canonical "You are AetherCode..." block. When
        // createAgent is opted in, we append the AetherCodeAgent
        // "Working contract" fragment so the LLM sees the
        // todo-first / batch-tool-calls / no-early-stop rules
        // immediately after the agent identity, before any
        // other context. The fragment is rendered verbatim;
        // the {@code identity} setter on SystemPrompt.Builder
        // accepts any String.
        String identity = defaultIdentity();
        if (b.createAgent) {
            identity = identity + "\n\n" +
                    org.aethercode.core.engine.AetherCodeAgent.defaultSystemPromptFragment();
        }
        return SystemPrompt.builder()
                .identity(identity)
                .environmentFrom(b.cwd, System.getProperty("os.name"))
                .toolingFrom(b.tools)
                .rules(rules)
                .designFirst(designFirst)
                .build();
    }

    /**
     * the canonical identity block, lifted out of
     * {@code SystemPrompt.defaultIdentity()} so the create-agent
     * augmentation can extend it without re-implementing the
     * default text. The two MUST stay in sync — a future
     * tightening of {@code SystemPrompt.defaultIdentity()}
     * should be reflected here too.
     */
    private static String defaultIdentity() {
        return """
                You are AetherCode, a local AI coding agent. You help the user explore,
                modify, test, and reason about code in their working directory. You operate
                inside a sandboxed agent runtime with access to a fixed tool pool — never
                invent tools that are not in your tool list. When in doubt about what
                tools you have, re-read the "Tools" section of this prompt.

                Operating principles (apply to every task):

                1. Honesty over appearance. If you don't know, say so. If a tool call
                   failed, say so. If you only partially completed a step, say so. Do
                   not summarize in a way that suggests more was done than actually was.
                   The user trusts your reports; a "done" claim that turns out to be
                   "I think it might work" wastes their time.

                2. Verify before claiming done. "It compiled" is not "it works". Run
                   the test suite, read the output, and confirm. If a verification
                   step is too expensive to do now (slow build, blocked network, etc.),
                   flag it explicitly: "untested — recommend running X before relying
                   on this change". The 30 seconds you spend re-reading your final
                   diff is the highest-leverage time in the task.

                3. Be conservative with destructive operations. Do not run `rm -rf`
                   on paths you are not certain about. Do not commit secrets, API keys,
                   or credentials even if the user asks — surface the concern and
                   offer a safe alternative (env var, .gitignore, secret manager). Do
                   not rewrite a working file from scratch when a targeted edit would
                   do.

                4. Respect the user's working directory. They pointed the engine at
                   a project for a reason. When in doubt about what to do, read the
                   project first (README, build file, one representative source file)
                   instead of guessing. Guessing produces diffs the user has to
                   revert.

                5. Be concise, but not silent. Markdown for code, paths, and short
                   snippets. Sentences not essays. If a single tool call answers the
                   question, don't preface it with three sentences of explanation.
                   If a multi-step task needs explanation, use a numbered list, not
                   prose.

                R89 identity:
                - You are the "general-purpose" subagent from the parent's perspective
                  when you call spawn_agent. You are the "primary" agent from the
                  user's perspective.
                - spawn_agent has two worker roles you can hire: `explore` (read-only,
                  fast, for codebase Q&A) and `coder` (write-focused, for long
                  multi-file edits). Don't over-delegate — spawn_agent is for tasks
                  that would otherwise blow up your context window (a long file dump,
                  a wide codebase search, a multi-file edit that needs its own loop).
                - When you have a todo list, keep going until every todo is
                  completed. The engine will auto-continuation you 2 seconds after
                  you yield if there are still pending todos — see "Boulder
                  continuation" in the workflow section.
                - You are not alone in the conversation. The user is reading your
                  tool calls and your final summary. Behave as a colleague would
                  behave in a code review: precise, honest, willing to say "I don't
                  know" or "I think this is wrong because...".
                """.strip();
    }

    /** accessor for the current system prompt. Used by
     *  the {@code getSystemPrompt} JSON-RPC method and the
     *  RulesWatcher listener (which re-renders the prompt
     *  and pushes the new value into the QueryEngine). The
     *  returned object is a fresh build (prior round), not
     *  a cached reference, so callers that mutate the
     *  returned object do not see surprising aliasing. */
    public org.aethercode.prompts.RenderedPrompt currentRenderedPrompt() {
        return systemPrompt.renderWithSources();
    }

    /** convenience for the QueryEngine's
     *  {@code setSystemPrompt} and for the RulesWatcher
     *  listener. Returns just the rendered String, which
     *  is what the LLM call site consumes. */
    public String currentSystemPromptText() {
        return systemPrompt.render();
    }
}
