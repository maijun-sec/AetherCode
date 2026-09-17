package org.aethercode.protocol.methods;

import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.memory.ProjectMemoryCompressor;
import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.aethercode.sdk.AetherCodeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 *
 * registers the AetherCode-specific JSON-RPC methods on a
 * {@link JsonRpcDispatcher} and wires them to a live
 * {@link AetherCodeEngine}.
 *
 * <p>Methods exposed:
 * <ul>
 *   <li>{@code ping} —liveness check; returns server version + uptime</li>
 *   <li>{@code getState} —engine state snapshot (model, mode, tool count, ...)</li>
 *   <li>{@code listTools} —list of registered tools</li>
 *   <li>{@code setModel} —change the model at runtime</li>
 *   <li>{@code setPermissionMode} —change the permission mode at runtime</li>
 *   <li>{@code setSystemPrompt} —replace the engine system prompt</li>
 *   <li>{@code query} —start a new query; streams events via
 *       {@code stream_event} notifications; returns the run id
 *       (and the first turn's task id) immediately</li>
 *   <li>{@code cancel} —cancel a running query by run id</li>
 *   <li>{@code listSessions} —list saved sessions (if SessionStore wired)</li>
 *   <li>{@code loadSession} —switch the engine to a saved session</li>
 *   <li>{@code listTasks} —legacy: list of in-flight + recent tasks</li>
 *   <li>{@code listProjects} —legacy: list of recent projects (cwds)</li>
 *   <li>{@code switchProject} —legacy: switch the engine's cwd</li>
 *   <li>{@code permission_response} —legacy: client reply to a
 *       {@code permission_request} notification. The {@code requestId}
 *       in the params matches the one in the notification.</li>
 *   <li>{@code getMetrics} —R77: per-engine metrics snapshot</li>
 *   <li>{@code getTraces} —R78: recent span recorder snapshot
 *       ({@code {inFlight, completed, traces: [...]}})</li>
 *   <li>{@code getTrace} —R79: single-trace snapshot
 *       ({@code {traceId, inFlight, completed, spans: [...]}});
 *       requires a {@code traceId} parameter</li>
 * </ul>
 *
 * <p>Notifications emitted by the server:
 * <ul>
 *   <li>{@code stream_event} —a single {@link StreamEvent} from the engine,
 *       wrapped as {@code {runId, event}}</li>
 *   <li>{@code task_state} —task lifecycle: {@code {taskId, status, runId?}}</li>
 *   <li>{@code log} —log lines (level + message), useful for the TUI console panel</li>
 *   <li>{@code permission_request} —legacy: a tool call needs a
 *       permission decision. Params: {@code {requestId, runId, tool,
 *       input, reason, riskLevel}}. The client must reply with a
 *       {@code permission_response} method call.</li>
 * </ul>
 */
public class AetherCodeMethods {

    private static final Logger LOG = LoggerFactory.getLogger(AetherCodeMethods.class);

    public static final String NOTIFY_STREAM_EVENT       = "stream_event";
    public static final String NOTIFY_TASK_STATE         = "task_state";
    public static final String NOTIFY_LOG                = "log";
    public static final String NOTIFY_PERMISSION_REQUEST = "permission_request";

    /** the {@code kind} value embedded in a
     *  {@link #NOTIFY_TASK_STATE} notification when the
     *  in-session todo list changes (the model just called
     *  {@code todo_write}). The desktop's Plan tab subscribes
     *  to {@code kind=todo_update} and replaces its local
     *  snapshot on every emission. The payload is the full
     *  new list (aethercode model: the model sends the entire
     *  list on every call so the renderer never has to diff). */
    public static final String TODO_UPDATE_KIND          = "todo_update";
    /**
     * a session's cwd changed (via switchProject or
     *  the next time the engine's setCwd is called). The
     *  TUI / Desktop uses this to refresh the cwd pill
     *  in the Header and to invalidate any cached
     *  project-memory view. Payload: { sessionId, oldCwd,
     *  newCwd, atMs }. */
    public static final String NOTIFY_CWD_CHANGED        = "cwd_changed";
    /**
     * a low-risk tool call was auto-approved by
     * the daemon (the user had {@code autoApproveLowRisk}
     * enabled, default true). Payload: { tool, input,
     * reason, sessionId, requestId (synthetic, "auto-..."),
     * atMs }. The UI uses this to surface an
     * "auto-allowed: N" StatusBar badge so the user
     * always knows the daemon short-circuited a prompt
     * on their behalf —no silent approvals.
     */
    public static final String NOTIFY_PERMISSION_AUTO_APPROVED = "permission_auto_approved";

    // per-method tags surfaced via /api/methods so the
    // R121 raw-RPC command palette can group / filter the
    // 50+ RPCs. Each method is annotated with one or more
    // tags from this fixed set. The renderer uses them for
    // two purposes: (1) chip-style filters in the palette
    // ("show me the engine-state write RPCs"), and (2)
    // a future group-collapsed view ("Permissions" / "Engine"
    // / "Workflow"). Tags are static so a renderer can
    // pre-render the filter bar without waiting for the
    // daemon to respond.
    //
    // The set is intentionally small (12 tags) so the
    // palette's chip row stays one line. Adding more would
    // push the user into a "which of these 30 chips do I
    // want" decision —diminishing returns.
    public static final String TAG_READ         = "read";        // read-only, no state change
    public static final String TAG_WRITE        = "write";       // mutates daemon state
    public static final String TAG_ENGINE       = "engine";      // engine configuration
    public static final String TAG_SESSION      = "session";     // session lifecycle
    public static final String TAG_PERMISSION   = "permission";  // permission policy
    public static final String TAG_LOOP         = "loop";        // loop detector
    public static final String TAG_TOOLS        = "tools";       // tool pool
    public static final String TAG_WORKFLOW     = "workflow";    // workflow mgmt
    public static final String TAG_MEMORY       = "memory";      // memory store
    public static final String TAG_TASK         = "task";        // task board
    public static final String TAG_SKILL        = "skill";       // skill registry
    public static final String TAG_AGENT        = "agent";       // agent registry
    public static final String TAG_PROJECT      = "project";     // project switch
    public static final String TAG_DIAGNOSTIC   = "diagnostic";  // ping, metrics, traces

    /**
     * canonical method →tags map. Keys are the
     * exact RPC names the dispatcher understands; values
     * are the tag set surfaced via {@code /api/methods}.
     * The map is static (built once at class init) so
     * {@code /api/methods} is O(1) and the renderer
     * doesn't pay a per-call allocation.
     *
     * <p>Maintenance rule: when a new RPC is added, add
     * the same name here with the appropriate tag(s).
     * The {@code AetherCodeMethodsR124Test} suite pins
     * that every method in the dispatch switch is also
     * present here —a refactor that adds a new RPC but
     * forgets to tag it gets caught.
     */
    public static final Map<String, String[]> METHOD_TAGS;
    static {
        Map<String, String[]> m = new java.util.LinkedHashMap<>();
        // Diagnostic
        m.put("ping",                                  new String[]{TAG_READ, TAG_DIAGNOSTIC});
        // richer health snapshot than ping (model + idle
        // time + lastError + pendingPermissionCount + healthy bool).
        m.put("engineHealth",                          new String[]{TAG_READ, TAG_DIAGNOSTIC});
        m.put("getMetrics",                            new String[]{TAG_READ, TAG_DIAGNOSTIC});
        m.put("getTraces",                             new String[]{TAG_READ, TAG_DIAGNOSTIC});
        m.put("getTrace",                              new String[]{TAG_READ, TAG_DIAGNOSTIC});
        m.put("getEngineStats",                        new String[]{TAG_READ, TAG_DIAGNOSTIC});
        // Engine config (read)
        m.put("getState",                              new String[]{TAG_READ, TAG_ENGINE});
        m.put("getSystemPrompt",                       new String[]{TAG_READ, TAG_ENGINE});
        m.put("getSystemPromptSection",                new String[]{TAG_READ, TAG_ENGINE});
        m.put("getPhaseBudget",                        new String[]{TAG_READ, TAG_ENGINE});
        m.put("listModels",                            new String[]{TAG_READ, TAG_ENGINE});
        // Engine config (write)
        m.put("setModel",                              new String[]{TAG_WRITE, TAG_ENGINE});
        m.put("setPermissionMode",                     new String[]{TAG_WRITE, TAG_ENGINE, TAG_PERMISSION});
        m.put("setLoopDetectorThresholds",             new String[]{TAG_WRITE, TAG_ENGINE, TAG_LOOP});
        m.put("setAutoApproveLowRisk",                 new String[]{TAG_WRITE, TAG_ENGINE, TAG_PERMISSION});
        m.put("setAutoApproveMediumHigh",              new String[]{TAG_WRITE, TAG_ENGINE, TAG_PERMISSION});
        // 3-layer memory surface. Read methods are
        // tagged "read" (no state change); write methods
        // are tagged "write" + "memory". A future R-round
        // could collapse these into a single
        // "memory.{scope}.{action}" shape, but the
        // flat names are easier to wire from the
        // renderer's typed wrapper.
        m.put("getMemory",                             new String[]{TAG_READ, TAG_MEMORY});
        m.put("setMemory",                             new String[]{TAG_WRITE, TAG_MEMORY});
        m.put("listMemory",                            new String[]{TAG_READ, TAG_MEMORY});
        m.put("deleteMemory",                          new String[]{TAG_WRITE, TAG_MEMORY});
        m.put("compressProjectMemory",                new String[]{TAG_WRITE, TAG_MEMORY});
        // cwd is bound to the session, not the
        // engine. switchProject is a session-scoped
        // write that also invalidates the project
        // memory cache for the old cwd.
        m.put("switchProject",                         new String[]{TAG_WRITE, TAG_PROJECT});
        m.put("setSystemPrompt",                       new String[]{TAG_WRITE, TAG_ENGINE});
        m.put("setPhase",                              new String[]{TAG_WRITE, TAG_ENGINE});
        m.put("setPhaseBudget",                        new String[]{TAG_WRITE, TAG_ENGINE});
        m.put("setConcurrencyProfile",                 new String[]{TAG_WRITE, TAG_ENGINE});
        m.put("switchProvider",                        new String[]{TAG_WRITE, TAG_ENGINE});
        // Permission
        m.put("permissionResponse",                    new String[]{TAG_WRITE, TAG_PERMISSION});
        m.put("permissionPolicyOverride",              new String[]{TAG_WRITE, TAG_PERMISSION});
        // live status of the permission queue.
        m.put("getPermissionStatus",                   new String[]{TAG_READ, TAG_PERMISSION});
        m.put("loopAck",                               new String[]{TAG_WRITE, TAG_LOOP});
        m.put("getSkipStats",                          new String[]{TAG_READ, TAG_PERMISSION});
        // Session
        m.put("listSessions",                          new String[]{TAG_READ, TAG_SESSION});
        m.put("loadSession",                           new String[]{TAG_READ, TAG_SESSION});
        m.put("createSession",                         new String[]{TAG_WRITE, TAG_SESSION});
        m.put("deleteSession",                         new String[]{TAG_WRITE, TAG_SESSION});
        m.put("listEngines",                           new String[]{TAG_READ, TAG_SESSION});
        m.put("createEngine",                          new String[]{TAG_WRITE, TAG_SESSION});
        m.put("deleteEngine",                          new String[]{TAG_WRITE, TAG_SESSION});
        m.put("setActiveEngine",                       new String[]{TAG_WRITE, TAG_SESSION});
        m.put("getActiveEngine",                       new String[]{TAG_READ, TAG_SESSION});
        // Tools
        m.put("listTools",                             new String[]{TAG_READ, TAG_TOOLS});
        m.put("listToolActions",                       new String[]{TAG_READ, TAG_TOOLS});
        // Tasks
        m.put("listTasks",                             new String[]{TAG_READ, TAG_TASK});
        m.put("createTask",                            new String[]{TAG_WRITE, TAG_TASK});
        m.put("updateTaskStatus",                      new String[]{TAG_WRITE, TAG_TASK});
        m.put("retrySubTask",                          new String[]{TAG_WRITE, TAG_TASK});
        // Workflow
        m.put("listWorkflows",                         new String[]{TAG_READ, TAG_WORKFLOW});
        m.put("getWorkflow",                           new String[]{TAG_READ, TAG_WORKFLOW});
        m.put("runWorkflow",                           new String[]{TAG_WRITE, TAG_WORKFLOW});
        m.put("writeWorkflow",                         new String[]{TAG_WRITE, TAG_WORKFLOW});
        m.put("deleteWorkflow",                        new String[]{TAG_WRITE, TAG_WORKFLOW});
        m.put("listCwdFiles",                          new String[]{TAG_READ, TAG_WORKFLOW});
        // Memory
        m.put("listMemory",                            new String[]{TAG_READ, TAG_MEMORY});
        m.put("readMemory",                            new String[]{TAG_READ, TAG_MEMORY});
        m.put("writeMemory",                           new String[]{TAG_WRITE, TAG_MEMORY});
        m.put("deleteMemory",                          new String[]{TAG_WRITE, TAG_MEMORY});
        // Skills
        m.put("listSkills",                            new String[]{TAG_READ, TAG_SKILL});
        m.put("getSkillBody",                          new String[]{TAG_READ, TAG_SKILL});
        m.put("reloadSkills",                          new String[]{TAG_WRITE, TAG_SKILL});
        // install a SKILL.md into the user- or
        // project-tier root + auto-reload. Powers the
        // desktop's /skill add slash command.
        m.put("addSkill",                             new String[]{TAG_WRITE, TAG_SKILL});
        m.put("reloadRegistries",                       new String[]{TAG_WRITE, TAG_SKILL});
        // Agents
        m.put("listAgents",                            new String[]{TAG_READ, TAG_AGENT});
        m.put("getAgentBody",                          new String[]{TAG_READ, TAG_AGENT});
        m.put("createAgent",                           new String[]{TAG_WRITE, TAG_AGENT});
        m.put("updateAgent",                           new String[]{TAG_WRITE, TAG_AGENT});
        m.put("deleteAgent",                           new String[]{TAG_WRITE, TAG_AGENT});
        m.put("reloadAgents",                          new String[]{TAG_WRITE, TAG_AGENT});
        // Projects
        m.put("listProjects",                          new String[]{TAG_READ, TAG_PROJECT});
        m.put("switchProject",                         new String[]{TAG_WRITE, TAG_PROJECT});
        // Misc read
        m.put("getTranscript",                         new String[]{TAG_READ});
        m.put("listProviders",                         new String[]{TAG_READ});
        m.put("query",                                 new String[]{TAG_WRITE});
        m.put("cancel",                                new String[]{TAG_WRITE});
        m.put("setCwd",                                new String[]{TAG_WRITE});
        // T-500 / design.md §5.4: the namespaced memory/*
        // surface (T-070..T-075). Read vs write split
        // matches the existing getMemory/setMemory split.
        m.put("memory/get",                            new String[]{TAG_READ, TAG_MEMORY});
        m.put("memory/appendProjectChange",            new String[]{TAG_WRITE, TAG_MEMORY});
        m.put("memory/appendSessionFact",              new String[]{TAG_WRITE, TAG_MEMORY});
        m.put("memory/appendSessionChange",            new String[]{TAG_WRITE, TAG_MEMORY}); // R280
        m.put("memory/setProjectInfo",                 new String[]{TAG_WRITE, TAG_MEMORY, TAG_PROJECT}); // R280
        m.put("memory/readProjectMemory",              new String[]{TAG_READ,  TAG_MEMORY, TAG_PROJECT}); // R280
        m.put("memory/compact",                        new String[]{TAG_WRITE, TAG_MEMORY});
        m.put("memory/switchProject",                  new String[]{TAG_WRITE, TAG_PROJECT, TAG_MEMORY});
        m.put("memory/list",                           new String[]{TAG_READ, TAG_MEMORY});
        // T-500 / design.md §2.9: the compact/* surface
        // (T-190..T-193). All except compact/status are
        // writes; status is a read.
        m.put("compact/run",                           new String[]{TAG_WRITE});
        m.put("compact/status",                        new String[]{TAG_READ});
        m.put("compact/reset",                         new String[]{TAG_WRITE});
        m.put("compact/history",                       new String[]{TAG_READ});
        // T-500 / design.md §5.1 + §5.4: the theme/*
        // surface. Reads (list / get / active) are
        // tagged TAG_READ; writes (set / import /
        // export) are tagged TAG_WRITE.
        m.put("theme/list",                            new String[]{TAG_READ});
        m.put("theme/get",                             new String[]{TAG_READ});
        m.put("theme/set",                             new String[]{TAG_WRITE});
        m.put("theme/import",                          new String[]{TAG_WRITE});
        m.put("theme/export",                          new String[]{TAG_WRITE});
        m.put("theme/active",                          new String[]{TAG_READ});
        // T-500 / design.md §2.8: context meter
        // backing. Single read; tagged diagnostic so
        // a renderer's "engine state" filter includes
        // it.
        m.put("context/info",                          new String[]{TAG_READ, TAG_DIAGNOSTIC});
        // Read-only aliases (legacy-B) —kept for old clients
        // and tagged so a renderer's "show me all engine reads"
        // filter includes them. (No extra entries here —the
        // listProviders / setCwd / query / cancel entries
        // above already cover them.)
        METHOD_TAGS = java.util.Collections.unmodifiableMap(m);
    }
    /** skip-confirmation counter changed. Payload:
     *  { sessionId, remaining, source ("rpc" | "auto-detect" | "consume" | "clear") }. */
    public static final String NOTIFY_SKIP_CONFIRMATION  = "skip_confirmation";
    // skip-low notification. Fired ONCE per session
    // when the per-session skip counter crosses DOWN
    // through the waterline (e.g. 6 -> 5 with waterline=5).
    // The UI uses this to show "skip running low, re-arm?".
    public static final String NOTIFY_SKIP_LOW           = "skip_low";
    /** background subagent lifecycle. Fired on every
     *  state transition in the {@code SubagentRegistry} —     *  new running, completed, failed, cancelled. The
     *  payload mirrors the registry's {@code SubagentEvent}
     *  fields ({@code jobId, role, status, elapsedMs,
     *  summary, atMs}) so the TUI can render the most
     *  recent event without re-querying the daemon. */
    public static final String NOTIFY_SUBAGENT_EVENT    = "subagent_event";

    /** Decision string the TUI sends back. Matches {@code
     *  ToolPermissionPrompter}'s decision vocabulary plus two
     *  persistence-modifiers used by the TUI:
     *  <ul>
     *    <li>{@code allow}        —run this tool call once</li>
     *    <li>{@code deny}         —block this tool call once</li>
     *    <li>{@code always_allow} —persist a per-(tool, target) allow</li>
     *    <li>{@code always_deny}  —persist a per-(tool, target) deny</li>
     *  </ul> */
    public static final String DECISION_ALLOW        = "allow";
    public static final String DECISION_DENY         = "deny";
    public static final String DECISION_ALWAYS_ALLOW = "always_allow";
    public static final String DECISION_ALWAYS_DENY  = "always_deny";

    /** Pending permission asks, keyed by requestId. The {@link
     *  org.aethercode.protocol.permissions.JsonRpcPermissionPrompter}
     *  creates a CompletableFuture and parks it here when a tool
     *  needs a decision. The {@link #permissionResponse} handler
     *  resolves it. */
    private final Map<String, CompletableFuture<PermissionDecision>> pendingPermissions
            = new ConcurrentHashMap<>();

    private final AetherCodeEngine engine;
    /**
     * runtime toggle for "auto-approve low risk
     * tool calls without prompting". The default is
     * true (the legacy init-time heuristic was always-on;
     * R120 promotes it to a runtime flag with a UI
     * toggle). The flag is read by the
     * {@link org.aethercode.protocol.permissions.JsonRpcPermissionPrompter}
     * which short-circuits the future for low-risk
     * calls (read-only, glob, grep, etc.) and emits a
     * {@code NOTIFY_PERMISSION_AUTO_APPROVED}
     * notification. Volatile + AtomicBoolean so a
     * flip is visible to the next permission ask
     * without a full reconnect.
     */
    private volatile boolean autoApproveLowRisk = true;
    /**
     * opt-in flag for "auto-approve medium- AND
     * high-risk tool calls without prompting". Distinct
     * from {@link #autoApproveLowRisk} so a user can
     * leave the safe low-risk shortcut on while keeping
     * explicit prompts for medium / high. Critical risk
     * (rm -rf, sudo, mkfs) is NEVER auto-approved —it
     * always asks.
     *
     * <p>R268d (2026-09-15): default flipped from
     * {@code false} to {@code true}. The previous default
     * caused batch workflows (e.g. "write 6 unit tests"
     * with file_write × 6) to hang on every permission
     * ask — the desktop's permission banner would time
     * out at 5min, LLM would retry, and the task would
     * never complete unattended. With autoApproveMediumHigh
     * = true the daemon short-circuits file_write and
     * bash (safe commands) so an unattended workflow can
     * run end-to-end. The R89 write-guard still blocks
     * silent overwrites of existing files, and the risk
     * classifier still prompts for rm -rf / sudo / mkfs.
     *
     * <p>Existing desktop users with
     * {@code prefs.autoApproveMediumHigh === false} in
     * localStorage keep their explicit opt-out (the
     * desktop pushes the persisted value to the daemon on
     * connect, so the user's choice is preserved). Only
     * first-time users get the new true default.
     */
    private volatile boolean autoApproveMediumHigh = true;
    /**
     * total number of low-risk tool calls
     * auto-approved since the daemon started. Surfaced
     * in the desktop's StatusBar so the user can see
     * the cumulative count at a glance (the
     * notification stream has the per-event detail
     * for users who want to scroll).
     */
    private final java.util.concurrent.atomic.AtomicLong autoApprovedCount
            = new java.util.concurrent.atomic.AtomicLong(0);
    /**
     * cumulative count of medium / high-risk
     * tool calls that were auto-approved via the
     * {@link #autoApproveMediumHigh} flag. Tracked
     * separately from {@link #autoApprovedCount} so
     * the UI can warn ("you've auto-approved N
     * medium/high calls —was that intentional?")
     * without conflating it with the everyday
     * read-only shortcuts.
     */
    private final java.util.concurrent.atomic.AtomicLong autoApprovedElevatedCount
            = new java.util.concurrent.atomic.AtomicLong(0);
    /** when set, the active engine for any RPC
     *  call is fetched from this SessionManager instead
     *  of the {@link #engine} field. The default path
     *  (no manager) keeps using the single engine for
     *  backward compat. */
    private final org.aethercode.sdk.SessionManager sessionManager;
    private final Consumer<JsonRpcNotification> notifier;
    /** 3-layer memory facade. Null when the daemon
     *  was started without a memory base (e.g. a test
     *  fixture that only exercises the engine). The
     *  memory RPCs ({@code getMemory}, {@code setMemory},
     *  {@code listMemory}, {@code deleteMemory},
     *  {@code compressProjectMemory}) all return
     *  {@code {ok: false, reason: "memory not configured"}}
     *  when this is null. */
    private volatile org.aethercode.memory.LayeredMemoryStore memoryStore;
    private final Map<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    /** session-level "a run is currently in flight"
     *  set. We refuse a second query for the same session
     *  while the first is still streaming, so the user's
     *  manual "Continue" never races with the boulder hook's
     *  2s auto-continue. Auto-continue dispatchers also
     *  check this gate (and skip if set) so the two paths
     *  cannot both squeeze a query in. The set is keyed
     *  by sessionId, value is the runId currently in
     *  flight. Removed in the run's finally block. */
    private final Map<String, String> sessionRunLock = new ConcurrentHashMap<>();
    private final AtomicLong runCounter = new AtomicLong();
    private final long startMs = System.currentTimeMillis();
    /** boulder-continuation hook. Wired in the constructor; the
     *  hook subscribes to {@code AppState.fireSessionIdle} and, when
     *  the todo list still has incomplete items, schedules a 2s
     *  countdown that re-prompts the engine via
     *  {@link EngineContinuationDispatcher}.
     *
     *  <p>legacy: this is the dispatcher/hook for
     *  the DEFAULT engine (the one passed to the
     *  constructor). Factory-built engines (via
     *  the SessionManager) get their own pair,
     *  built lazily by
     *  {@link #ensureSessionIdleListener} and
     *  stored in {@link #perEngineDispatchers} /
     *  {@link #perEngineHooks}. */
    private final org.aethercode.hooks.builtin.TodoContinuationHook continuationHook;
    /** bridge between the engine-agnostic
     *  {@link org.aethercode.hooks.builtin.TodoContinuationHook} and
     *  the live {@link AetherCodeEngine} + {@link Consumer<JsonRpcNotification>}.
     *  Created in the constructor; never replaced.
     * this is the dispatcher for the
     *  default engine. */
    private final EngineContinuationDispatcher continuationDispatcher;
    /** per-engine dispatchers / hooks for
     *  factory-built engines. Keyed by
     *  {@link AetherCodeEngine#appState}'s
     *  sessionId (the wire-level id). The default
     *  engine's pair lives in
     *  {@link #continuationDispatcher} /
     *  {@link #continuationHook} (no double-registration).
     *  Populated by
     *  {@link #ensureSessionIdleListener} when a
     *  non-default engine first appears. */
    private final Map<String, org.aethercode.hooks.builtin.TodoContinuationHook> perEngineHooks =
            new ConcurrentHashMap<>();
    private final Map<String, EngineContinuationDispatcher> perEngineDispatchers =
            new ConcurrentHashMap<>();
    /** provider registry. Wired by the
     *  daemon after construction (the CLI's
     *  Main.buildEngine loads
     *  {@code ~/.aethercode/providers.yaml} and
     *  passes the registry through {@code
     *  DaemonRunner}). When null, the listProviders
     *  RPC returns an empty list (the renderer falls
     *  back to the bundled defaults baked into
     *  TypeScript). The setter is volatile because
     *  the daemon may swap providers on the fly
     *  (e.g. when the user picks a different
     *  provider from the Settings panel). */
    private volatile org.aethercode.core.providers.ProviderRegistry providerRegistry;
    /** the current provider+model. Updated by
     *  {@link #switchProvider} so the daemon can
     *  rebuild its ChatClient on demand. Mirrors
     *  the engine's appState.mainLoopModel field
     *  but tracks the provider separately so the
     *  listProviders RPC can show "currently using"
     *  in the Settings panel. */
    private volatile String currentProviderName;
    private volatile String currentModelId;
    /** optional resolver that builds a
     *  fresh {@link org.aethercode.core.llm.ChatClient}
     *  from a {@code "provider/model"} string. The
     *  protocol module does NOT depend on
     *  {@code aethercode-engine-springai} (the
     *  implementation module that owns
     *  {@code SpringAiChatClient}), so the
     *  resolver is injected by the CLI's
     *  {@code DaemonRunner} after construction.
     *  The resolver closes over
     *  {@code providerRegistry} +
     *  {@code SpringAiChatClient.forProvider}.
     *  When null, model resolution falls back to
     *  the engine's default client (the legacy
     * legacy behaviour). */
    private volatile java.util.function.Function<String, org.aethercode.core.llm.ChatClient> chatClientResolver;

    public AetherCodeMethods(AetherCodeEngine engine,
                              Consumer<JsonRpcNotification> notifier) {
        this(engine, null, notifier);
    }

    /** constructor that wires an optional
     *  SessionManager. The default constructor (single
     *  engine) is preserved for backward compat. */
    public AetherCodeMethods(AetherCodeEngine engine,
                              org.aethercode.sdk.SessionManager sessionManager,
                              Consumer<JsonRpcNotification> notifier) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sessionManager = sessionManager;
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.continuationDispatcher = new EngineContinuationDispatcher(engine, this);
        this.continuationHook = new org.aethercode.hooks.builtin.TodoContinuationHook(continuationDispatcher);
        // wire the engine's skip-confirmation consumption
        // listener to a JSON-RPC notification so the TUI / Desktop
        // status bar updates immediately. Done at construction so
        // the first permission check is already covered.
        if (engine != null) {
            engine.setSkipConsumedListener(remaining -> {
                try {
                    notifier.accept(new JsonRpcNotification(
                            JsonRpcMessage.VERSION, NOTIFY_SKIP_CONFIRMATION,
                            Map.of(
                                    "sessionId", engine.appState().sessionId(),
                                    "remaining", remaining,
                                    "source", "consume"
                            )));
                } catch (RuntimeException ignore) {
                    // A failing notifier must never break a permission check.
                }
            });
            // wire the engine's skip-low listener to a
            // JSON-RPC notification. Fired ONCE per session
            // when the counter crosses DOWN through the
            // waterline. The UI uses this to show "skip
            // running low, re-arm?".
            engine.setSkipLowListener((sessionId, remaining) -> {
                try {
                    notifier.accept(new JsonRpcNotification(
                            JsonRpcMessage.VERSION, NOTIFY_SKIP_LOW,
                            Map.of(
                                    "sessionId", sessionId,
                                    "remaining", remaining
                            )));
                } catch (RuntimeException ignore) {
                    // A failing notifier must never break a permission check.
                }
            });
            // wire the engine's todo-list update listener
            // to a JSON-RPC notification. The model emits a
            // new list on every todo_write call (and the
            // sub_todo_write path can also flip sub-task
            // status without a top-level change). The desktop's
            // Plan tab uses this for live, daemon-driven
            // rendering — previously, the panel relied on the
            // wire payload of the tool_use_start event, which
            // works but is one layer removed from the engine's
            // canonical state. The CLI's existing
            // onTodoUpdate listener (Main.runHeadless) keeps
            // firing alongside this one — both writers are
            // intentionally independent and the renderer
            // de-dupes on the wire.
            final Consumer<JsonRpcNotification> todoNotifier = notifier;
            engine.appState().onTodoUpdate(todos -> {
                try {
                    todoNotifier.accept(new JsonRpcNotification(
                            JsonRpcMessage.VERSION, NOTIFY_TASK_STATE,
                            Map.of(
                                    "kind", TODO_UPDATE_KIND,
                                    "sessionId", engine.appState().sessionId(),
                                    "params", Map.of("todos", todos)
                            )));
                } catch (RuntimeException ignore) {
                    // A failing notifier must never break the
                    // engine's todoList publish path — the AppState
                    // already updated, so the CLI's in-process
                    // listener sees the new list regardless.
                }
            });
        }
        // when a SessionManager is wired, register
        // an onSessionIdle listener on every NEW engine
        // the manager creates. The SessionManager fires
        // create-listeners on getOrCreate (legacy) —we
        // add ours after construction so the default
        // engine (which is already in the manager) gets
        // the listener too, AND every factory-built
        // engine gets a per-engine dispatcher / hook
        // pair.
        if (sessionManager != null) {
            // Wire the default engine (the one passed in
            // via the legacy ctor) —it may already be
            // registered with the manager, or it may not
            // (depends on who called us). The SessionManager
            // dedup logic tolerates both.
            ensureSessionIdleListener(engine);
            // also register a SessionManager
            // on-create listener that wires the same
            // SESSION_IDLE listener (with a fresh
            // per-engine dispatcher / hook pair) onto
            // every factory-built engine. Without this,
            // createEngine-built sessions would silently
            // miss the boulder-continuation behaviour
            // (the default engine gets it via the call
            // above; non-default engines would not).
            // The listener is held for the lifetime of
            // the AetherCodeMethods instance —there's
            // no unregister path because the methods
            // instance is process-scoped.
            sessionManager.addOnCreateListener(this::ensureSessionIdleListener);
            LOG.info("对应历史 round: SESSION_IDLE create-listener wired (per-engine dispatchers will materialise on demand)");
        }
    }

    /** when a SessionManager is present, this
     *  returns the engine registered for the active
     *  session id; otherwise it returns the constructor
     *  engine. The active session id is the one the
     *  user most recently {@code setActiveSession}'d.
     *  This indirection is what makes the daemon
     *  multi-session: a single dispatcher routes to
     *  whichever engine is currently active, and a
     *  sessionId param (future work) can route to a
     *  specific one. */
    private AetherCodeEngine currentEngine() {
        if (sessionManager == null) return engine;
        org.aethercode.sdk.SessionManager.EngineHandle h = sessionManager.active();
        return h == null ? engine : h.engine;
    }

    /** resolve the SessionManager to use for
     *  RPCs that consult the multi-session surface
     *  (listEngines / createEngine / deleteEngine /
     *  setActiveEngine / getActiveEngine). Prefers
     *  the manager passed to the 3-arg constructor
     *  (the new wiring path); falls back to the
     *  engine's own accessor (the legacy-B path
     *  where the engine was built with a
     *  {@code Builder.sessionManager(...)} call).
     *  Returns null when neither is wired, in which
     *  case the RPCs return {@code {ok: false,
     *  error: "session manager not configured"}}.
     *
     *  <p>Note: the methods-level field is the
     *  preferred source because it's the
     *  {@code DaemonRunner}-driven path; the
     *  engine-level field is the legacy
     *  {@code AetherCodeEngine.Builder} path. Both
     *  can co-exist; the methods-level field wins
     *  when both are set. */
    private org.aethercode.sdk.SessionManager effectiveSessionManager() {
        if (sessionManager != null) return sessionManager;
        return engine.sessionManager();
    }

    /** per-RPC sessionId routing helper.
     *  Resolves the {@link AetherCodeEngine} that a
     *  given RPC call should target. When
     *  {@code sessionId} is null or blank, returns
     *  {@link #currentEngine()} (the active engine
     *  in multi-session mode, or the constructor
     *  engine in legacy single-engine mode). When
     *  {@code sessionId} is non-blank, looks up
     *  the engine in the effective
     *  {@link SessionManager}; returns null when
     *  the id is unknown (the caller is expected
     *  to surface a {@code {ok: false, error: "no
     *  such sessionId: <id>"}} response).
     *
     *  <p>This helper is the legacy equivalent of
     *  the inline routing in {@link #query}
     *  (legacy), extracted for the four new RPCs
     *  ({@code getState} / {@code listTools} /
     *  {@code setModel} / {@code setPermissionMode})
     *  that don't need the full {@code query}
     *  body. The {@code query} method uses its
     *  own inline routing because the body is
     *  too large to refactor through a helper
     *  closure. */
    private AetherCodeEngine resolveRpcTarget(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return currentEngine();
        }
        org.aethercode.sdk.SessionManager sm = effectiveSessionManager();
        if (sm == null) {
            return null;
        }
        org.aethercode.sdk.SessionManager.EngineHandle h = sm.get(sessionId);
        return h == null ? null : h.engine;
    }

    /** legacy: install the SESSION_IDLE listener
     *  on an engine. Called from the constructor
     *  (for the default engine) and from the
     *  SessionManager on-create listener (for
     *  factory-built engines).
     *
     *  <p>The default engine uses the constructor's
     *  {@link #continuationDispatcher} /
     *  {@link #continuationHook} pair. Non-default
     *  engines (legacy) get a fresh pair built on
     *  demand and stored in
     *  {@link #perEngineDispatchers} /
     *  {@link #perEngineHooks} keyed by
     *  sessionId. The same {@code SESSION_IDLE}
     *  listener logic runs for both paths; only
     *  the dispatcher/hook reference differs.
     *
     *  <p>The pair is built once per sessionId
     *  (subsequent calls for the same id are
     *  no-ops via the per-engine map's
     *  {@code putIfAbsent} semantics). This means
     *  the listener is NOT re-installed on a
     *  re-registration (the SessionManager's
     *  dedup ensures a sessionId is wired
     *  exactly once). */
    private void ensureSessionIdleListener(AetherCodeEngine eng) {
        if (eng == null) return;
        // per-engine pair. Default engine
        // uses the constructor fields; every other
        // engine gets a fresh pair keyed by
        // sessionId. The closure captures `eng` (the
        // target engine) so each listener routes to
        // the right dispatcher.
        final String sessionId;
        try {
            sessionId = eng.appState().sessionId();
        } catch (Throwable t) {
            LOG.warn("对应历史 round: cannot read sessionId for engine wiring: {}", t.getMessage());
            return;
        }
        if (sessionId == null || sessionId.isBlank()) {
            LOG.debug("对应历史 round: skipping session-idle wiring for engine with no sessionId");
            return;
        }
        final org.aethercode.hooks.builtin.TodoContinuationHook hook;
        final EngineContinuationDispatcher dispatcher;
        if (eng == engine) {
            // Default engine path: use the
            // constructor-installed pair.
            hook = continuationHook;
            dispatcher = continuationDispatcher;
        } else {
            // factory-built engine path.
            // Build a fresh pair if we haven't seen
            // this sessionId before.
            EngineContinuationDispatcher existingDispatcher = perEngineDispatchers.get(sessionId);
            if (existingDispatcher != null) {
                LOG.debug("对应历史 round: SESSION_IDLE already wired for sessionId={}", sessionId);
                return;
            }
            EngineContinuationDispatcher freshDispatcher = new EngineContinuationDispatcher(eng, this);
            org.aethercode.hooks.builtin.TodoContinuationHook freshHook =
                    new org.aethercode.hooks.builtin.TodoContinuationHook(freshDispatcher);
            // putIfAbsent: a concurrent
            // ensureSessionIdleListener for the same
            // id loses the race; both call sites
            // will have built an equivalent pair, but
            // only one wins the map.
            EngineContinuationDispatcher prevDisp = perEngineDispatchers.putIfAbsent(sessionId, freshDispatcher);
            org.aethercode.hooks.builtin.TodoContinuationHook prevHook = perEngineHooks.putIfAbsent(sessionId, freshHook);
            hook = prevHook != null ? prevHook : freshHook;
            dispatcher = prevDisp != null ? prevDisp : freshDispatcher;
            LOG.info("对应历史 round: SESSION_IDLE listener wired for non-default engine sessionId={}", sessionId);
        }
        eng.appState().onSessionIdle(ev -> {
            try {
                String sid = eng.appState().sessionId();
                if (sid == null || sid.isBlank()) {
                    return;
                }
                hook.onSessionIdle(
                        sid,
                        ev.runId(),
                        ev.stopReason(),
                        ev.todoList());
            } catch (Throwable hookEx) {
                LOG.warn("R89 boulder hook threw on session-idle for sessionId={}: {}",
                        eng.appState().sessionId(), hookEx.getMessage());
            }
        });
        // forward subagent lifecycle events to the
        // client. The registry is a process-scoped singleton
        // (mirroring BashJobRegistry), so we subscribe at
        // construction time and the same subscription
        // outlives the engine's session swaps. The fan-out
        // is best-effort: a malformed notification (a
        // misbehaving listener in the registry, or a
        // shutdown in progress) is logged and skipped so
        // a transient failure can't kill the RPC thread.
        org.aethercode.tools.task.SubagentRegistry.instance()
                .onChange(ev -> {
                    try {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("jobId",     ev.jobId());
                        payload.put("role",      ev.role());
                        payload.put("status",    ev.status().name());
                        payload.put("elapsedMs", ev.elapsedMs());
                        payload.put("summary",   ev.summary());
                        payload.put("atMs",      ev.atMs());
                        // include the sessionId so the
                        // renderer can filter events for its
                        // own session in a multi-session daemon.
                        // Always present (empty string for
                        // legacy-D jobs) so the wire shape is
                        // stable across engine versions.
                        payload.put("sessionId", ev.sessionId() == null ? "" : ev.sessionId());
                        // include the captured result
                        // text on COMPLETED (and the error on
                        // FAILED) so the SubagentPanel can offer
                        // an "insert into input" action without
                        // an extra round-trip. Empty for
                        // RUNNING / CANCELLED transitions.
                        payload.put("result", ev.result() == null ? "" : ev.result());
                        // include the cancellation reason
                        // on CANCELLED transitions so the UI can
                        // show "cancelled by user (timeout)"
                        // instead of a bare "cancelled". Empty
                        // for all other transitions and for
                        // cancellations issued without a reason.
                        payload.put("reason", ev.reason() == null ? "" : ev.reason());
                        // include the streaming partial
                        // result. Only meaningful when the
                        // status is RUNNING (the worker has
                        // produced some text but has not yet
                        // finished); empty for terminal
                        // transitions and for jobs that have
                        // not started streaming. The TUI
                        // SubagentPanel renders this in real
                        // time so the user sees a live preview
                        // of in-flight work.
                        payload.put("partialResult",
                                ev.partialResult() == null ? "" : ev.partialResult());
                        notifier.accept(new JsonRpcNotification(
                                JsonRpcMessage.VERSION, NOTIFY_SUBAGENT_EVENT, payload));
                    } catch (Throwable nfy) {
                        LOG.warn("对应历史 round subagent notify failed: {}", nfy.getMessage());
                    }
                });
    }

    // ------------------------------------------------------------------
    // public helpers used by EngineContinuationDispatcher.
    // ------------------------------------------------------------------

    /** Push a custom JSON-RPC notification to the client. The
     *  {@code method} is the JSON-RPC method (e.g.
     *  {@code "stream_event"} or
     *  {@code "todo_continuation_countdown"}), the {@code params}
     *  is the payload map. Used by the boulder dispatcher to
     *  forward events from the continuation re-query and to
     *  push countdown-start / side-note messages. */
    public void notifyCustom(String method, Map<String, Object> params) {
        if (notifier == null) return;
        notifier.accept(new JsonRpcNotification(
                JsonRpcMessage.VERSION, method, params));
    }

    /** Push a structured log entry to the client. Mirrors the
     *  shape used by the existing {@code NOTIFY_LOG} emissions
     *  (the TUI's console panel renders these). */
    public void notifyLog(Map<String, Object> payload) {
        notifyCustom(NOTIFY_LOG, payload);
    }

    /** Cancel a pending continuation countdown for the session.
     *  Used by {@link EngineContinuationDispatcher} when the
     *  user clicks "Stop" so the 2s window is cut short instead
     *  of waiting it out. No-op when no countdown is scheduled. */
    public void cancelPendingContinuationCountdown(String sessionId) {
        if (continuationHook != null) continuationHook.cancelPendingCountdown(sessionId);
    }

    /**
     * try to acquire the session-level run lock
     * for an auto-continue dispatch. Returns the new
     * runId on success, or null when the session is
     * already busy (the dispatch should bail and let
     * the next idle event re-arm the countdown). The
     * caller MUST release the lock when its run
     * finishes (the run's finally block does this
     * automatically —see query()).
     *
     * <p>This is the same {@code sessionRunLock} the
     * user-typed query() checks, so a user-typed
     * "Continue" that lands in the 2s window between the
     * previous run's end and the dispatch's start
     * wins: the user's query acquires the lock and
     * the auto-continue is skipped (null return) so
     * the two never overlap.
     */
    public String tryAcquireSessionLockForContinuation(String sessionId) {
        if (sessionId == null) return null;
        // Generate the runId the dispatch would use so
        // the lock is owned by a real, identifiable
        // run.
        String newRunId = "cont-" + runCounter.incrementAndGet();
        String existing = sessionRunLock.putIfAbsent(sessionId, newRunId);
        if (existing != null) return null;
        return newRunId;
    }

    /**
     * release the session-level run lock. Used
     * by {@link EngineContinuationDispatcher} when
     * the auto-continue run finishes.
     */
    public void releaseSessionLock(String sessionId, String runId) {
        if (sessionId == null || runId == null) return;
        sessionRunLock.remove(sessionId, runId);
    }

    // -----------------------------------------------------------------
    //  Test-only helpers (no production caller).
    //
    //  legacy tests use these to plant / read the
    //  session-level run lock without spinning up a
    //  full run thread. They're public so the test
    //  class can drive them, but no production code
    //  should be calling them —the run-end hook
    //  releases the lock through the same `remove`
    //  path the helpers use.
    // -----------------------------------------------------------------

    /** Test-only: return the runId currently holding
     *  the session lock (or null if free). */
    public String sessionRunLockForTest(String sessionId) {
        return sessionRunLock.get(sessionId);
    }

    /** Test-only: plant an arbitrary lock holder. */
    public void holdSessionLockForTest(String sessionId, String runId) {
        sessionRunLock.put(sessionId, runId);
    }

    /** Test-only: free the session lock regardless
     *  of who holds it. */
    public void releaseSessionLockForTest(String sessionId, String runId) {
        sessionRunLock.remove(sessionId, runId);
    }

    /** Render a {@link StreamEvent} as a plain Map for the wire.
     *  Package-private —exposed so the boulder dispatcher can
     *  reuse the same shape the regular query path uses. */
    static Map<String, Object> eventToMapPublic(StreamEvent ev) {
        return eventToMap(ev);
    }

    /** install the provider registry after
     *  construction. The CLI does this once at
     *  startup; a future R-round that supports
     *  live-reload (e.g. when the user edits
     *  providers.yaml and hits "—) can call
     *  this again. */
    public void setProviderRegistry(org.aethercode.core.providers.ProviderRegistry reg) {
        this.providerRegistry = reg;
    }
    /** install a resolver that builds a
     *  {@link org.aethercode.core.llm.ChatClient}
     *  from a {@code "provider/model"} string.
     *  The CLI's {@code DaemonRunner} wires this
     *  with a closure that uses
     *  {@code SpringAiChatClient.forProvider} +
     *  the {@code providerRegistry}. The
     *  protocol module does NOT directly
     *  import the engine-springai classes —the
     *  resolver is the seam. Pass {@code null}
     *  to clear (e.g. on daemon shutdown). */
    public void setChatClientResolver(java.util.function.Function<String, org.aethercode.core.llm.ChatClient> resolver) {
        this.chatClientResolver = resolver;
        LOG.info("chat client resolver {}",
                resolver == null ? "cleared" : "installed");
    }

    /**
     * inject the layered memory facade. The
     * {@code DaemonRunner} calls this once at startup
     * with a fully-configured store (USER / PROJECT /
     * SESSION backends wired, chat client plumbed
     * through to {@link ProjectMemoryCompressor}).
     * The store is {@code volatile} so a hot-swap (e.g.
     * user manually reloads config) takes effect on
     * the next RPC without daemon restart.
     */
    public void setMemoryStore(org.aethercode.memory.LayeredMemoryStore store) {
        this.memoryStore = store;
        LOG.info("memory store {}", store == null ? "cleared" : "installed");
    }

    public org.aethercode.memory.LayeredMemoryStore memoryStore() {
        return memoryStore;
    }

    /** hold the daemon-scoped {@link org.aethercode.memory.MemoryLifecycle}
     *  so {@code DaemonRunner} can stop it on shutdown. The
     *  lifecycle is wired into the engine via
     *  {@link org.aethercode.sdk.AetherCodeEngine#setMemoryLifecycle}
     *  (one per engine), not the methods. Volatile so a hot-swap
     *  (e.g. a settings change that disables the lifecycle) takes
     *  effect on the next RPC. */
    private volatile org.aethercode.memory.MemoryLifecycle memoryLifecycle;

    public void setMemoryLifecycle(org.aethercode.memory.MemoryLifecycle lifecycle) {
        this.memoryLifecycle = lifecycle;
    }

    public org.aethercode.memory.MemoryLifecycle memoryLifecycle() {
        return memoryLifecycle;
    }

    /** hold the daemon-scoped {@link org.aethercode.memory.MemoryExtractor}
     *  so {@code DaemonRunner} can stop / swap it on shutdown or settings
     *  change. The lifecycle is wired into the engine via
     *  {@link org.aethercode.sdk.AetherCodeEngine#setMemoryLifecycle}
     *  (one per engine), not the methods. Volatile so a hot-swap
     *  takes effect on the next RPC. */
    private volatile org.aethercode.memory.MemoryExtractor memoryExtractor;

    public void setMemoryExtractor(org.aethercode.memory.MemoryExtractor extractor) {
        this.memoryExtractor = extractor;
    }

    public org.aethercode.memory.MemoryExtractor memoryExtractor() {
        return memoryExtractor;
    }

    /** hold the daemon-scoped {@link org.aethercode.core.llm.ChatClient}
     *  used by the lifecycle's Tier-3 strategy extraction. Wired by
     *  {@code DaemonRunner} at startup; null in headless / --print
     *  paths (strategy extraction silently degrades to no-op). */
    private volatile org.aethercode.core.llm.ChatClient memoryChatClient;

    public void setMemoryChatClient(org.aethercode.core.llm.ChatClient c) {
        this.memoryChatClient = c;
    }

    public org.aethercode.core.llm.ChatClient memoryChatClient() {
        return memoryChatClient;
    }

    // ----------------------------------------------------------------
    // viewAuditLog RPC
    // ----------------------------------------------------------------

    /**
     * return recent audit log entries. The TUI "View audit log"
     * button + any external monitor call this RPC.
     *
     * <p>Shape:
     * <pre>
     *   viewAuditLog({ sinceMs?: number, limit?: number })
     *     -&gt; {
     *          ok: true,
     *          count: N,
     *          entries: ["...json line 1...", "...json line 2...", ...],
     *          path: "<absolute path to audit.log>"
     *        }
     * </pre>
     *
     * <p>{@code sinceMs} filters to entries after that epoch ms (best-effort:
     * the audit log is append-only JSONL so we scan tail-ward and stop on
     * the first older line). {@code limit} caps the returned rows (default
     * 100, max 1000). Returns an empty list when the audit is disabled
     * or the file doesn't exist yet.
     */
    @SuppressWarnings("unchecked")
    public Object viewAuditLog(Object params) {
        Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : Map.of();
        long sinceMs = p.get("sinceMs") instanceof Number n ? n.longValue() : 0L;
        int limit = p.get("limit") instanceof Number n ? n.intValue() : 100;
        if (limit <= 0) limit = 100;
        if (limit > 1000) limit = 1000;
        org.aethercode.memory.MemoryAudit audit = org.aethercode.memory.MemoryAudit.current();
        if (audit == null) {
            return Map.of("ok", true, "count", 0, "entries", List.of(),
                    "path", "", "note", "audit not initialised");
        }
        try {
            int read = Math.max(limit * 4, 200);  // read a wider window then filter
            java.util.List<String> raw = audit.readRecent(read);
            java.util.List<String> filtered = new java.util.ArrayList<>();
            for (String line : raw) {
                if (line == null || line.isBlank()) continue;
                if (sinceMs > 0) {
                    // best-effort: extract "ts":"..." and compare
                    int i = line.indexOf("\"ts\":\"");
                    if (i >= 0) {
                        int j = line.indexOf('"', i + 6);
                        if (j > i) {
                            String ts = line.substring(i + 6, j);
                            try {
                                java.time.Instant inst = java.time.Instant.parse(ts);
                                if (inst.toEpochMilli() < sinceMs) continue;
                            } catch (Exception parseEx) {
                                // unparseable — include anyway
                            }
                        }
                    }
                }
                filtered.add(line);
                if (filtered.size() >= limit) break;
            }
            return Map.of("ok", true, "count", filtered.size(),
                    "entries", filtered, "path", audit.logFile().toString());
        } catch (IOException e) {
            LOG.warn("viewAuditLog failed: {}", e.getMessage());
            return Map.of("ok", false, "reason", e.getMessage());
        }
    }

    /** package-private accessor for the chat client
     *  resolver. Used by {@code DaemonRunner.buildMemoryStore}
     *  to plumb the LLM client into the
     *  {@link ProjectMemoryCompressor}. Returns null if
     *  no chat client has been wired (the compressor
     *  falls back to its tag-only line). */
    public java.util.function.Function<String, org.aethercode.core.llm.ChatClient> chatClientResolverField() {
        return chatClientResolver;
    }

    public void setCurrentProvider(String provider, String model) {
        this.currentProviderName = provider;
        this.currentModelId = model;
    }
    public String currentProviderName() { return currentProviderName; }
    public String currentModelId() { return currentModelId; }

    public void registerAll(JsonRpcDispatcher dispatcher) {
        dispatcher.register("ping",                  this::ping);
        // richer health snapshot than ping. The TUI
        // calls this on a 5s timer to render a status badge
        // (idle time, last error, pending permission asks).
        dispatcher.register("engineHealth",          this::engineHealth);
        dispatcher.register("getState",              this::getState);
        // surface the active system prompt
        // (string + section-level provenance) so the
        // TUI /prompt slash command can show the user
        // exactly what the model is seeing.
        dispatcher.register("getSystemPrompt",       this::getSystemPrompt);
        dispatcher.register("getSystemPromptSection",this::getSystemPromptSection);
        // per-phase tool-call budget surface. The
        // tracker is owned by the engine (set via
        // AetherCodeEngine.Builder.phaseTracker). The
        // three RPCs let the TUI / dashboard read state
        // and reconfigure caps.
        dispatcher.register("getPhaseBudget",        this::getPhaseBudget);
        dispatcher.register("setPhase",             this::setPhase);
        dispatcher.register("setPhaseBudget",       this::setPhaseBudget);
        // multi-session surface. The TUI / desktop
        // use these to manage multiple engines in a
        // single daemon (one per cwd, typically). When
        // the engine was built WITHOUT a SessionManager
        // (the default), all RPCs return ok=false.
        //
        // Note: the method names use the "Engines"
        // suffix to avoid collision with the pre-existing
        // listSessions / createSession / deleteSession
        // RPCs (legacy) which operate on the persisted
        // session-store (transcripts on disk). The legacy
        // family operates on the in-memory
        // SessionManager (one engine per session in
        // one process).
        dispatcher.register("listEngines",            this::listEngines);
        dispatcher.register("createEngine",          this::createEngine);
        dispatcher.register("deleteEngine",          this::deleteEngine);
        dispatcher.register("setActiveEngine",       this::setActiveEngine);
        dispatcher.register("getActiveEngine",       this::getActiveEngine);
        dispatcher.register("listTools",             this::listTools);
        // per-tool permission action assessment.
        dispatcher.register("listToolActions",       this::listToolActions);
        dispatcher.register("setModel",              this::setModel);
        dispatcher.register("setPermissionMode",     this::setPermissionMode);
        // per-session skip-confirmation counter.
        dispatcher.register("setSkipConfirmation",  this::setSkipConfirmation);
        // runtime loop-detector threshold tweak.
        // the legacy window/threshold were set at
        // engine build time only (AetherCodeEngine.Builder
        // .loopDetector(window, threshold)). The
        // Settings panel needs a way to nudge them at
        // runtime without re-spawning the JVM, so we
        // delegate to QueryEngine.setLoopDetector which
        // is the same setter the builder uses.
        dispatcher.register("setLoopDetectorThresholds",
                                                       this::setLoopDetectorThresholds);
        // toggle the daemon-side "auto-approve
        // low risk tool calls" flag. The default is
        // true (R87's init-time heuristic was always-on;
        // R120 promotes it to a runtime toggle with a
        // desktop UI). The flag is read by
        // JsonRpcPermissionPrompter on every permission
        // ask so a flip takes effect on the very next
        // tool call.
        dispatcher.register("setAutoApproveLowRisk", this::setAutoApproveLowRisk);
        // medium / high-risk auto-approve toggle (headless
        // mode). Same signature as the low-risk toggle.
        dispatcher.register("setAutoApproveMediumHigh", this::setAutoApproveMediumHigh);
        // skip-confirmation adoption stats.
        dispatcher.register("getSkipStats",         this::getSkipStats);
        // heuristic permission-mode suggestion for
        // fresh projects. Returns the cached suggestion
        // (mode + reasons) plus the user's current mode
        // so the UI can render "💡 suggested: X".
        dispatcher.register("getPermissionModeSuggestion",
                this::getPermissionModeSuggestion);
        dispatcher.register("setSystemPrompt",       this::setSystemPrompt);
        dispatcher.register("query",                 this::query);
        dispatcher.register("cancel",                this::cancel);
        // model context-window introspection. The TUI
        // (and any other client) needs to know the
        // current model + its context-window size so it
        // can show "200.0K / 200.0K (100%)" instead of
        // "200.0K / 1.0M (20%)" (the user-reported bug
        // was the TUI showing 100% on MiniMax-M1 when
        // it actually had 1M budget). The endpoint
        // returns: provider (e.g. "minmax"), model
        // (e.g. "MiniMax-M3"), contextWindow (e.g.
        // 1000000), maxOutput (e.g. 512000), and the
        // current usage. Read-only, no side effects.
        dispatcher.register("getContextInfo",        this::getContextInfo);
        // per-session summary. The user explicitly
        // asked for "a summary regardless of whether the
        // task ended correctly" — so this RPC always
        // returns the current SessionStats snapshot
        // (files_written / files_read / shell_calls /
        // queries / state / last_error / by_tool /
        // summary_text). The TUI's "End-of-task" panel
        // and the loop-detected banner both call it.
        dispatcher.register("summary",                this::summary);
        // explicit compact RPC. The TUI's
        // "Suggest Compact" button can call this to
        // trigger a synchronous compaction of the
        // current session's transcript. Returns a
        // summary {compactedFrom, compactedTo,
        // tokensBefore, tokensAfter}. The pre-flight
        // auto-compact (legacy) already runs on every
        // query, but the TUI button needs an
        // explicit endpoint for manual triggers.
        dispatcher.register("compact",               this::compactTranscript);
        // legacy .5: worktree RPCs (Option B). The
        // manager creates a directory under
        // AETHERCODE_WORKTREE_ROOT (default
        // java.io.tmpdir/aethercode-worktrees) so
        // a session can be backed by an isolated
        // workspace. The actual git worktree
        // integration is deferred (see
        // WorktreeManager for the stub).
        dispatcher.register("addWorktree",           this::addWorktree);
        dispatcher.register("removeWorktree",        this::removeWorktree);
        dispatcher.register("listWorktrees",         this::listWorktrees);
        // legacy .7: supervisor RPCs (Option C).
        // The supervisor is a daemon that owns
        // multiple child daemons (one per
        // project). legacy .7 ships the API +
        // supervisor-mode flag; the actual
        // subprocess management is deferred.
        dispatcher.register("registerChild",         this::registerChild);
        dispatcher.register("unregisterChild",       this::unregisterChild);
        dispatcher.register("listChildren",          this::listChildren);
        // explicit health probe + RPC
        // forwarder. The heartbeat thread runs
        // in the background; this RPC is the
        // synchronous probe for the TUI.
        dispatcher.register("healthCheckChild",      this::healthCheckChild);
        dispatcher.register("forwardRpc",            this::forwardRpc);
        // forward a JSON-RPC notification
        // (fire-and-forget) to a child daemon.
        // Half-step toward a full WS proxy; the
        // HTTP notification path covers
        // permission_response, loop_ack, etc.
        // Live streaming events still need the
        // TUI to connect to the child directly.
        dispatcher.register("proxyNotification",     this::proxyNotification);
        // toggle auto-restart on the
        // supervisor. Off by default; the TUI
        // can flip it on for production.
        dispatcher.register("setAutoRestart",        this::setAutoRestart);
        dispatcher.register("listSessions",          this::listSessions);
        dispatcher.register("loadSession",           this::loadSession);
        // mint a new session / delete an existing one. The
        // desktop's `createNewSession` action calls createSession
        // (to get a real engine session id, not a local UUID) and
        // then loadSession (to switch). deleteSession removes the
        // JSONL file from the store. Both require a SessionStore
        // to be wired at engine construction; otherwise the
        // RPCs return ENGINE_ERROR.
        dispatcher.register("createSession",         this::createSession);
        dispatcher.register("deleteSession",         this::deleteSession);
        dispatcher.register("listTasks",             this::listTasks);
        // Kanban-style task CRUD. The renderer's
        // Kanban board creates new tasks, updates their
        // status (drag-drop), and lists the live set.
        // createTask fires a `task_event` notification
        // (action="create") via the engine's taskPush so
        // every connected client sees the new column
        // entry. updateTaskStatus fires
        // (action="update"). Both also fan out via the
        // existing listTasks pull path (the desktop
        // refetches on Kanban mount) so a slow WS push
        // doesn't strand a board in a stale state.
        dispatcher.register("createTask",           this::createTask);
        dispatcher.register("updateTaskStatus",     this::updateTaskStatus);
        dispatcher.register("listProjects",          this::listProjects);
        dispatcher.register("switchProject",         this::switchProject);
        // 3-layer memory RPCs. Read + write + the
        // manual compression trigger. See the
        // "R127: 3-layer memory RPCs" block above for
        // the wire shapes. These RPCs are no-ops if
        // the daemon was started without a memory base
        // (the LayeredMemoryStore is null at startup;
        // DaemonRunner injects it after construction).
        dispatcher.register("getMemory",             this::getMemory);
        dispatcher.register("setMemory",             this::setMemory);
        dispatcher.register("listMemory",            this::listMemory);
        dispatcher.register("deleteMemory",          this::deleteMemory);
        dispatcher.register("compressProjectMemory",  this::compressProjectMemory);
        // TUI "View audit log" button + external monitor
        dispatcher.register("viewAuditLog",          this::viewAuditLog);
        // hooks for the lifecycle's strategy chat client
        dispatcher.register("permissionResponse",   this::permissionResponse);
        // live status of the permission queue. The TUI
        // calls this on (re)connect so it can render a
        // "N permission(s) pending" badge even when notifications
        // were missed (e.g. long sleep / fast batch). Returns
        // the count + a per-ask snapshot (id, done, cancelled).
        dispatcher.register("getPermissionStatus",  this::getPermissionStatus);
        dispatcher.register("getMetrics",            this::getMetrics);
        dispatcher.register("getTraces",             this::getTraces);
        dispatcher.register("getTrace",              this::getTrace);
        dispatcher.register("listModels",            this::listModels);
        // retry a previously-failed sub-task. R89 RPC is a thin
        // wrapper over query() that re-issues the sub-task's goal as
        // a new user prompt; the engine retains all prior transcript
        // context, so the model can pick up where the previous
        // attempt left off.
        dispatcher.register("retrySubTask",          this::retrySubTask);
        // install a per-(tool, target) override on top of the
        // current ProjectPermissionPolicy. Mirrors the "always allow
        // for this session" affordance the desktop exposes.
        dispatcher.register("permissionPolicyOverride", this::permissionPolicyOverride);
        // memory browser/editor. The desktop surfaces these
        // through a new MemoryPanel —list / read / write / delete
        // over the same per-scope dirs the engine reads via
        // MemoryPaths.
        dispatcher.register("listMemory",   this::listMemory);
        dispatcher.register("readMemoryFile",   this::readMemoryFile);
        dispatcher.register("writeMemoryFile",  this::writeMemoryFile);
        dispatcher.register("deleteMemoryFile", this::deleteMemoryFile);
        // the LoopGuardBanner's "Continue" button calls this to
        // reset the loop detector's tier (so a previously-paused
        // loop can continue past its current streak). The detector
        // is owned by the per-query QueryEngine; when no query is
        // in flight (or the detector was disabled) the RPC is a
        // no-op and returns {ok: true, tier: 0}.
        dispatcher.register("loopAck", this::loopAck);
        // workflow picker. The desktop input bar lets the
        // user attach a workflow to a query; the engine reads the
        // YAML, walks the steps, and emits `workflow_step` side
        // notes so the chat can show "executing step 3 of 7".
        // R102 ships the file-side plumbing (list / read); the
        // executor lands in R103 alongside the workflow-system
        // skill wiring.
        dispatcher.register("listWorkflows",   this::listWorkflows);
        dispatcher.register("getWorkflow",    this::getWorkflow);
        dispatcher.register("runWorkflow",    this::runWorkflow);
        // workflow editor. /workflow create writes a new
        // YAML into `<cwd>/.aethercode/workflows/<name>.yaml`;
        // /workflow modify overwrites an existing one; the
        // path-scope check refuses anything outside the workflow
        // dir. R103 will add a third path `validateWorkflow`
        // for pre-flight schema checks.
        dispatcher.register("writeWorkflow",  this::writeWorkflow);
        dispatcher.register("deleteWorkflow", this::deleteWorkflow);
        // `@`-mention file autocomplete. The input bar
        // listens for `@` and shows a dropdown of cwd files
        // matching the user's prefix. The desktop inserts the
        // chosen path as `@<path>` so the model can see the
        // full path in the prompt. We cap the result at 50
        // entries (sorted, depth-limited) to keep the dropdown
        // usable on large projects.
        dispatcher.register("listCwdFiles", this::listCwdFiles);
        // skill registry. Read SKILL.md from
        // <cwd>/.aethercode/skills/ and ~/.minimax/skills/; let the
        // desktop list, fetch, and force-reload. Auto-reload runs
        // every 10s in the background; the reloadSkills RPC is the
        // explicit user-triggered equivalent.
        dispatcher.register("listSkills",     this::listSkills);
        dispatcher.register("getSkillBody",   this::getSkillBody);
        dispatcher.register("reloadSkills",   this::reloadSkills);
        dispatcher.register("addSkill",      this::addSkill);
        dispatcher.register("reloadRegistries", this::reloadRegistries);
        // Mavis agent registry. Read ~/.minimax/agents/<n>/agent.md
        // and expose the list to the desktop. The workflow executor's
        // kind: agent step uses getAgentBody internally; we expose it
        // on the wire too so the desktop can preview an agent's body
        // before wiring it into a workflow.
        dispatcher.register("listAgents",     this::listAgents);
        dispatcher.register("getAgentBody",   this::getAgentBody);
        // dynamic Agent CRUD. The Settings
        // panel's "Agents" tab uses these to
        // create / update / delete agents on disk
        // (the same on-disk file the registry
        // reads). The registry reloads after
        // each call so listAgents sees the new
        // entry without a manual refresh.
        dispatcher.register("createAgent",    this::createAgent);
        dispatcher.register("updateAgent",    this::updateAgent);
        dispatcher.register("deleteAgent",    this::deleteAgent);
        // re-read every agent file from
        // disk. The Settings panel calls this
        // after a model change so the editor
        // sees the just-saved frontmatter
        // (e.g. the new model: field).
        dispatcher.register("reloadAgents",   this::reloadAgents);
        // engine health + concurrency profile. The
        // StatusBar polls getEngineStats every 5s; the Settings
        // panel writes setConcurrencyProfile when the user
        // changes the profile dropdown.
        dispatcher.register("getEngineStats",         this::getEngineStats);
        dispatcher.register("setConcurrencyProfile",  this::setConcurrencyProfile);
        // server-pushed transcript recovery. The
        // desktop's `transcript_event` subscriber keeps the
        // renderer's `messages` array in sync with every
        // appendMessage / loadSession. After a WS reconnect
        // (or on first launch), the renderer calls this RPC
        // to back-fill the messages it missed —the
        // alternative would be re-reading localStorage, but
        // localStorage is being retired in legacy.
        dispatcher.register("getTranscript",         this::getTranscript);
        // multi-provider support. The renderer's
        // Settings panel reads listProviders on open to
        // populate the provider picker; switchProvider
        // takes a (providerName, model) pair and rebuilds
        // the engine's ChatClient on the fly. Returns the
        // updated list of available models for the new
        // provider so the renderer can refresh its
        // model picker in one round-trip.
        dispatcher.register("listProviders",         this::listProviders);
        dispatcher.register("switchProvider",       this::switchProvider);
        // toggle the boulder auto-continue per session. The
        // TUI's countdown toast calls this with `stopped=true` when
        // the user clicks "Stop"; the next user-prompt submit clears
        // it (see the query() handler) so the user can keep
        // working with the model after the stop.
        dispatcher.register("setContinuationStopped", this::setContinuationStopped);
        // cancel a running background subagent. The
        // TUI / desktop panel calls this when the user
        // presses 'c' on a RUNNING row (TUI) or clicks the
        // Cancel button (desktop). The registry's cancel()
        // interrupts the worker thread; the
        // subagent_event notification carries the result
        // back to the same UI.
        dispatcher.register("subagentCancel", this::subagentCancel);
    }

    // ------------------------------------------------------------------
    // setContinuationStopped
    // ------------------------------------------------------------------

    /**
     * Toggle the per-session "auto-continue stopped" flag. Called
     * by the TUI's countdown toast when the user clicks "Stop",
     * and cleared on the next {@code query} call so the user can
     * keep working with the model after the stop.
     *
     * <p>Shape:
     * <ul>
     *   <li>in:  {@code { stopped: true|false }} —     *       when {@code sessionId} is omitted we use the
     *       engine's current session</li>
     *   <li>out: {@code { stopped: bool, sessionId: string }}</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public Object setContinuationStopped(Object params) {
        Map<String, Object> p = asMap(params);
        String sessionId = (String) p.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = engine.appState().sessionId();
        }
        Object stoppedRaw = p.get("stopped");
        boolean stopped = stoppedRaw instanceof Boolean b
                ? b.booleanValue() : true;
        if (continuationDispatcher != null) {
            continuationDispatcher.setContinuationStopped(sessionId, stopped);
        }
        LOG.info("R89 setContinuationStopped session={} stopped={}", sessionId, stopped);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", sessionId);
        r.put("stopped", stopped);
        return r;
    }

    // ------------------------------------------------------------------
    // Permission ask over JSON-RPC
    // ------------------------------------------------------------------

    /** Internal record of a permission decision the client has made. */
    public record PermissionDecision(String decision, String reason) {
        public boolean isAllow() { return DECISION_ALLOW.equals(decision) || DECISION_ALWAYS_ALLOW.equals(decision); }
        public boolean isPersist() { return DECISION_ALWAYS_ALLOW.equals(decision) || DECISION_ALWAYS_DENY.equals(decision); }
    }

    /** Issue a permission ask to the client. Returns a future that the
     *  permissionResponse handler will resolve. If no client is
     *  connected (e.g. tests), returns a future that fails immediately
     *  so the caller can fall back to the in-process policy.
     *
     *  <p>R163: callers are encouraged to pass the live
     *  {@code AppState.runId()} (or the engine's
     *  {@code StreamingToolExecutor.runId}) as {@code runId} so the
     *  TUI can correlate multiple pending asks to a single run. The
     *  the legacy default was {@code "n/a"} which made the field
     *  effectively useless — every ask showed up as the same opaque
     *  token. Pass null or blank to keep the legacy {@code "n/a"}
     *  shape (existing callers that don't track a runId continue to
     *  work). */
    public CompletableFuture<PermissionDecision> askPermission(
            String runId, String toolName, Map<String, Object> input,
            String reason, String riskLevel) {
        if (notifier == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no client"));
        }
        String requestId = "perm-" + java.util.UUID.randomUUID();
        CompletableFuture<PermissionDecision> fut = new CompletableFuture<>();
        pendingPermissions.put(requestId, fut);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("runId",     (runId == null || runId.isBlank()) ? "n/a" : runId);
        payload.put("tool",      toolName);
        payload.put("input",     input);
        payload.put("reason",    reason);
        payload.put("riskLevel", riskLevel);
        // include the current pending count in every
        // permission_request so a TUI that joins late (or comes
        // back from sleep and missed a few notifications) can
        // render a "N permission(s) pending" badge without
        // having to issue a separate getPermissionStatus RPC.
        payload.put("pendingCount", pendingPermissions.size());
        notifier.accept(new JsonRpcNotification(
                org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                NOTIFY_PERMISSION_REQUEST, payload));
        return fut;
    }

    /**
     * number of permission asks that have been issued but
     * not yet answered. Surfaced in the TUI's status bar so the
     * user can see "1 permission pending" when a long-running
     * tool batch is awaiting decisions on multiple calls.
     * Atomic counter view of {@link #pendingPermissions}.
     */
    public int pendingPermissionCount() {
        return pendingPermissions.size();
    }

    /**
     * snapshot of all pending permission asks, keyed by
     * {@code requestId}. Each entry carries the tool name, input
     * (best-effort), reason and risk level so the TUI can render
     * a queue panel when multiple asks are in flight. Read-only
     * view — the caller must not mutate the returned map.
     */
    public java.util.Map<String, java.util.Map<String, Object>> pendingPermissionSnapshot() {
        java.util.Map<String, java.util.Map<String, Object>> out =
                new java.util.LinkedHashMap<>();
        for (var e : pendingPermissions.entrySet()) {
            java.util.Map<String, Object> v = new java.util.LinkedHashMap<>();
            // The requestId is the only thing we reliably know
            // without rebuilding the notification payload. We
            // also surface the request future's completion
            // status so a TUI can show "stale" / "cancelled"
            // hints if the future has been completed but not
            // removed yet.
            v.put("requestId", e.getKey());
            v.put("done",      e.getValue().isDone());
            v.put("cancelled", e.getValue().isCancelled());
            v.put("completedExceptionally", e.getValue().isCompletedExceptionally());
            out.put(e.getKey(), v);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    /**
     * getter for the autoApproveLowRisk flag.
     * The {@link org.aethercode.protocol.permissions.JsonRpcPermissionPrompter}
     * reads this on every permission ask; the value
     * is volatile so a flip is visible without a
     * reconnect.
     */
    public boolean isAutoApproveLowRisk() { return autoApproveLowRisk; }
    public long getAutoApprovedCount() { return autoApprovedCount.get(); }
    /**
     * setter for the autoApproveLowRisk flag.
     * Returns the new value. The store's RPC wiring
     * uses this; the local setter is also exposed for
     * tests + the Java SDK / SDK consumer.
     */
    public AetherCodeMethods setAutoApproveLowRisk(boolean enabled) {
        this.autoApproveLowRisk = enabled;
        return this;
    }

    /**
     * getter for the autoApproveMediumHigh flag.
     * Mirrors {@link #isAutoApproveLowRisk} but covers
     * medium / high risk. The
     * {@link org.aethercode.protocol.permissions.JsonRpcPermissionPrompter}
     * checks BOTH this and the low-risk flag; whichever
     * applies wins. Critical risk is never short-circuited.
     */
    public boolean isAutoApproveMediumHigh() { return autoApproveMediumHigh; }

    /**
     * live permission-mode name (the canonical {@link
     * org.aethercode.core.permission.PermissionMode} enum string).
     * The {@link org.aethercode.protocol.permissions.JsonRpcPermissionPrompter}
     * reads this on every ask and short-circuits the autoApproveMediumHigh
     * path when the user is in an explicit-ask mode (ASK_BEFORE_TOOL /
     * DEFAULT / PLAN) — R277 fix. Returns {@code "DEFAULT"} if the
     * engine is null (defensive fallback for unit tests).
     */
    public String currentPermissionModeName() {
        if (engine == null) return "DEFAULT";
        try {
            return engine.appState().permissionMode().name();
        } catch (Throwable t) {
            return "DEFAULT";
        }
    }

    /**
     * true iff the current permission mode is one of the explicit-ask
     * tiers (ASK_BEFORE_TOOL / DEFAULT / PLAN). The JsonRpcPermissionPrompter
     * skips the autoApproveMediumHigh short-circuit when this returns true
     * so the user's "主动询问" / "ask" choice is actually honoured — the
     * R268d default-flipped-to-true change made the flag override the
     * mode, which silently bypassed every medium/high-risk call. R277
     * restores the mode-as-source-of-truth invariant.
     */
    public boolean isAskMode() {
        String m = currentPermissionModeName();
        return "ASK_BEFORE_TOOL".equals(m) || "DEFAULT".equals(m) || "PLAN".equals(m);
    }

    /**
     * local setter (Java SDK / tests). Returns
     * the new value for chaining. The wire-level setter
     * is {@code setAutoApproveMediumHigh(Object params)}
     * below.
     */
    public AetherCodeMethods setAutoApproveMediumHigh(boolean enabled) {
        this.autoApproveMediumHigh = enabled;
        return this;
    }

    /**
     * total number of medium / high-risk tool
     * calls auto-approved since the daemon started.
     * Surfaced via the {@code setAutoApproveMediumHigh}
     * RPC response so the UI can render a separate
     * "elevated auto-allow: N" indicator distinct from
     * the everyday low-risk count.
     */
    public long getAutoApprovedElevatedCount() { return autoApprovedElevatedCount.get(); }

    public Object permissionResponse(Object params) {
        Map<String, Object> p = asMap(params);
        String requestId = stringOrThrow(p, "requestId");
        String decision = String.valueOf(p.getOrDefault("decision", DECISION_DENY));
        String reason = String.valueOf(p.getOrDefault("reason", ""));
        CompletableFuture<PermissionDecision> fut = pendingPermissions.remove(requestId);
        if (fut == null) {
            // Stale / unknown id —the client may have sent a reply
            // for a permission we already timed out. Don't error; just
            // log.
            LOG.warn("permissionResponse for unknown requestId: {}", requestId);
            return Map.of("ok", false, "reason", "unknown requestId");
        }
        fut.complete(new PermissionDecision(decision, reason));
        return Map.of("ok", true, "requestId", requestId, "decision", decision);
    }

    /**
     * live status of the permission queue. Returns the
     * current pending count plus a per-ask snapshot (id, done,
     * cancelled, completedExceptionally) so a TUI that joins
     * late (or comes back from sleep and missed a few
     * notifications) can render a "N permission(s) pending"
     * badge without having to re-derive the state from
     * individual notifications.
     *
     * <p>No params. The RPC is read-only; safe to call on every
     * (re)connect.
     */
    public Object getPermissionStatus(Object params) {
        return Map.of(
                "ok", true,
                "pendingCount", pendingPermissions.size(),
                "autoApprovedCount", autoApprovedCount.get(),
                "autoApprovedElevatedCount", autoApprovedElevatedCount.get(),
                "autoApproveLowRisk", autoApproveLowRisk,
                "autoApproveMediumHigh", autoApproveMediumHigh,
                "asks", pendingPermissionSnapshot());
    }

    // ------------------------------------------------------------------
    // Task list (lightweight —full task graph is not exported)
    // ------------------------------------------------------------------

    public Object listTasks(Object params) {
        // return the real TaskRegistry's live
        // task list. legacy-3 this RPC derived tool
        // calls from the current session's transcript —        // a "tasks" view, but not the engine's task
        // graph. The Kanban board needs the real graph
        // (USER / AGENT task types, parent/child links,
        // live status transitions) so we use the
        // process-singleton TaskRegistry from here.
        // The tool-call view is still useful (and
        // cheaper) so the desktop's TaskList panel
        // keeps using it via a separate code path.
        java.util.List<org.aethercode.tasks.Task> live =
                org.aethercode.tasks.TaskRegistry.instance().list();
        List<Map<String, Object>> out = new ArrayList<>(live.size());
        for (org.aethercode.tasks.Task t : live) {
            out.add(taskToMap(t));
        }
        return Map.of("tasks", out, "count", out.size());
    }

    /** serialise a {@link org.aethercode.tasks.Task}
     *  to a plain Map for the wire. Same shape as the
     *  engine's private taskToMap so the Kanban board
     *  and the existing TaskList can share an
     *  adapter. */
    private static Map<String, Object> taskToMap(org.aethercode.tasks.Task t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.id());
        out.put("type", t.type().name().toLowerCase());
        out.put("status", t.status().name().toLowerCase());
        out.put("description", t.description());
        out.put("parentTaskId", t.parentTaskId());
        out.put("createdAtMs", t.createdAtMs());
        out.put("endedAtMs", t.endedAtMs());
        return out;
    }

    /** create a new task. The renderer's
     *  Kanban "+" button calls this with a description
     *  and an optional type. The task is created in
     *  PENDING status; the engine's taskPush consumer
     *  (wired by {@code DaemonRunner.runHttp}) fans
     *  the create out as a {@code task_event} with
     *  action=create. Returns the new task so the
     *  caller can insert it into local state
     *  immediately (no round-trip wait for the WS
     *  push). */
    public Object createTask(Object params) {
        Map<String, Object> p = asMap(params);
        String description = stringOrThrow(p, "description");
        // type defaults to "user" (the multica-style
        // "issue" type); an explicit "agent" type is
        // used by the agent-spawn path.
        org.aethercode.tasks.TaskType type = org.aethercode.tasks.TaskType.USER;
        if (p.get("type") instanceof String s && !s.isBlank()) {
            try {
                type = org.aethercode.tasks.TaskType.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw invalidParams("unknown task type: " + s
                        + "; valid: " + java.util.Arrays.toString(org.aethercode.tasks.TaskType.values()));
            }
        }
        String parentTaskId = p.get("parentTaskId") instanceof String ps && !ps.isBlank() ? ps : null;
        org.aethercode.tasks.Task t =
                org.aethercode.tasks.TaskRegistry.instance().create(type, description, parentTaskId);
        return Map.of("ok", true, "task", taskToMap(t));
    }

    /** transition a task to a new status.
     *  The renderer's Kanban drag-drop calls this
     *  with a {@code status} param. Idempotent for
     *  terminal states (the TaskRegistry already
     *  drops no-op transitions on completed/failed/
     *  killed tasks). Returns the updated task. */
    public Object updateTaskStatus(Object params) {
        Map<String, Object> p = asMap(params);
        String id = stringOrThrow(p, "id");
        String statusStr = stringOrThrow(p, "status");
        org.aethercode.tasks.TaskStatus next;
        try {
            next = org.aethercode.tasks.TaskStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidParams("unknown task status: " + statusStr
                    + "; valid: " + java.util.Arrays.toString(org.aethercode.tasks.TaskStatus.values()));
        }
        try {
            org.aethercode.tasks.Task t =
                    org.aethercode.tasks.TaskRegistry.instance().updateStatus(id, next);
            return Map.of("ok", true, "task", taskToMap(t));
        } catch (IllegalArgumentException e) {
            throw new JsonRpcProtocolException(
                    "updateTaskStatus failed: " + e.getMessage(),
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        }
    }

    /** Walk back from {@code startIdx} for a tool_use block whose id
     *  matches {@code toolUseId}. Stops at the assistant turn boundary
     *  so the lookup is O(tools in current turn). */
    private static String lookupToolName(
            java.util.List<org.aethercode.core.message.Message> transcript,
            int startIdx, String toolUseId) {
        for (int j = startIdx - 1; j >= 0; j--) {
            var m = transcript.get(j);
            if (m.role() == org.aethercode.core.message.Role.ASSISTANT) {
                for (var b : m.content()) {
                    if (b instanceof org.aethercode.core.message.ContentBlock.ToolUseBlock tu
                            && toolUseId.equals(tu.id())) {
                        return tu.name();
                    }
                }
                // If we found an assistant message without a matching
                // tool_use, this is the boundary of the current turn.
                // Stop looking —older assistant messages belong to
                // previous turns.
                if (!m.content().isEmpty()
                        && m.content().get(0) instanceof org.aethercode.core.message.ContentBlock.TextBlock) {
                    return "(unknown)";
                }
            }
        }
        return "(unknown)";
    }

    // ------------------------------------------------------------------
    // project list + cwd switch
    // ------------------------------------------------------------------

    public Object listProjects(Object params) {
        // Recent cwds from the AetherCode home directory (.aethercode/projects.json).
        // For now, just return the current project so the TUI has
        // something to display. R83: use the cwd's last path
        // segment as the project name (e.g. "fathom-dfa"), not
        // the placeholder "current" the desktop used to show.
        Map<String, Object> current = new LinkedHashMap<>();
        String cwd = java.lang.System.getProperty("user.dir");
        current.put("cwd", cwd);
        current.put("name", projectNameFor(cwd));
        current.put("active", true);
        return Map.of("projects", List.of(current), "current", cwd);
    }

    /** extract a friendly project name from a cwd. Strips
     *  trailing separators, returns the last path segment, and
     *  falls back to the full cwd if no separator is present. */
    private static String projectNameFor(String cwd) {
        if (cwd == null || cwd.isBlank()) return "current";
        // Strip both kinds of trailing separators and normalise
        // forward-slash so we can split consistently on Windows.
        String trimmed = cwd.replace('\\', '/');
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        int slash = trimmed.lastIndexOf('/');
        String last = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return last.isBlank() ? cwd : last;
    }

    @SuppressWarnings("unchecked")
    public Object switchProject(Object params) {
        Map<String, Object> p = asMap(params);
        String cwd = stringOrThrow(p, "cwd");
        // the cwd is now bound to the SESSION,
        // not the daemon. The renderer can switch cwd
        // any number of times without touching the
        // engine; the binding only takes effect when
        // the renderer fires a query (or via this
        // explicit switchProject). This RPC does
        // three things:
        //   1. Update the target engine's appState.cwd
        //      via AetherCodeEngine.setCwd(...).
        //   2. Persist the binding to SessionMemoryStore
        //      (session_info.cwd) so a daemon restart
        //      restores it.
        //   3. Invalidate the project memory cache for
        //      that cwd —the brief says "if a session switches cwd,
        //      the project-tier memory
        //      is recreated."
        //
        // The sessionId is optional: when omitted, the
        // active engine is updated. Multi-session
        // clients should pass sessionId explicitly.
        String sessionId = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sessionId);
        if (target == null) {
            return Map.of("ok", false, "reason",
                    "no engine for sessionId: " + (sessionId == null ? "<active>" : sessionId));
        }
        java.nio.file.Path newCwd = java.nio.file.Paths.get(cwd).toAbsolutePath().normalize();
        java.nio.file.Path oldCwd = target.setCwd(newCwd);
        // Persist to SQLite. We always write the
        // default session row so a fresh daemon
        // restores the cwd on next boot.
        if (memoryStore != null) {
            memoryStore.sessionStore().upsertSession(
                    target.appState().sessionId(),
                    newCwd.toString(),
                    null /* firstPrompt is captured on createSession */);
            // Invalidate the cached project store for
            // the OLD cwd (the new one will lazy-load
            // on the next read). The brief calls this
            // "project-tier memory is recreated."
            if (oldCwd != null) {
                memoryStore.invalidateProject(oldCwd.toString());
            }
        }
        notifier.accept(new JsonRpcNotification(
                org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                "cwd_changed",
                Map.of("sessionId", target.appState().sessionId(),
                        "oldCwd", String.valueOf(oldCwd),
                        "newCwd", newCwd.toString(),
                        "atMs", System.currentTimeMillis())));
        return Map.of(
                "ok", true,
                "sessionId", target.appState().sessionId(),
                "oldCwd", String.valueOf(oldCwd),
                "newCwd", newCwd.toString());
    }

    // ------------------------------------------------------------------
    // Methods
    // ------------------------------------------------------------------

    public Object ping(Object params) {
        // this RPC is now read-write: it both returns
        // the engine's view of itself AND records that a
        // client successfully reached us (used by
        // engineHealth to detect a frozen TUI). The legacy
        // shape is preserved verbatim so existing callers
        // that only read the version / uptime / model / id
        // continue to work.
        engine.recordPing();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("version",   org.aethercode.sdk.AetherCodeEngine.class.getPackage().getImplementationVersion());
        r.put("uptimeMs",  System.currentTimeMillis() - startMs);
        r.put("model",     engine.appState().mainLoopModel());
        r.put("sessionId", engine.appState().sessionId());
        return r;
    }

    /**
     * richer health snapshot than {@link #ping}. Pings
     * are for "is the daemon alive"; engineHealth is for "is
     * the engine stuck, has it errored recently, is a
     * permission ask waiting, how busy is it". The TUI
     * subscribes to this on a 5s timer (or after every
     * reconnect) to render a status-bar badge.
     *
     * <p>No params. Read-only. Always returns
     * {@code {ok: true, ...}} so the caller can detect a
     * crash via the {@code healthy} boolean rather than
     * the JSON-RPC error code.
     */
    public Object engineHealth(Object params) {
        engine.recordPing();
        org.aethercode.sdk.SessionStats stats = engine.sessionStats();
        long now = System.currentTimeMillis();
        long lastActivity = stats.lastActivityAtMs();
        long idleForMs = lastActivity == 0 ? -1 : (now - lastActivity);
        long lastPingForMs = engine.lastPingAtMs() == 0 ? -1 : (now - engine.lastPingAtMs());
        long lastErrorForMs = stats.lastErrorAtMs() == 0 ? -1 : (now - stats.lastErrorAtMs());
        // a daemon that has been silent (no pings, no
        // activity) for > 5 minutes is "stale" — the TUI can
        // surface a "reconnect?" hint without us having to
        // throw exceptions. The threshold is conservative; the
        // user is typically chatting for <2 minutes between
        // turns, and a 5-minute idle is unusual but not
        // necessarily broken.
        boolean healthy = idleForMs < 0 || idleForMs < 5 * 60_000L;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("healthy", healthy);
        r.put("version", org.aethercode.sdk.AetherCodeEngine.class.getPackage().getImplementationVersion());
        r.put("uptimeMs", now - startMs);
        r.put("model", engine.appState().mainLoopModel());
        r.put("sessionId", engine.appState().sessionId());
        r.put("permissionMode", engine.appState().permissionMode().name());
        r.put("state", stats.state());
        r.put("lastError", stats.lastError());
        r.put("lastErrorAtMs", stats.lastErrorAtMs());
        r.put("lastErrorForMs", lastErrorForMs);
        r.put("lastActivityAtMs", lastActivity);
        r.put("idleForMs", idleForMs);
        r.put("lastPingAtMs", engine.lastPingAtMs());
        r.put("lastPingForMs", lastPingForMs);
        r.put("queries", stats.queries());
        r.put("totalToolCalls", stats.totalToolCalls());
        r.put("pendingPermissionCount", pendingPermissionCount());
        return r;
    }

    @SuppressWarnings("unchecked")
    public Object getState(Object params) {
        // resolve the target engine via
        // sessionId (falls back to currentEngine()).
        Map<String, Object> p = asMap(params);
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId",       target.appState().sessionId());
        r.put("model",           target.appState().mainLoopModel());
        r.put("permissionMode",  target.appState().permissionMode().name());
        r.put("toolCount",       target.appState().toolPool().size());
        r.put("tools",           target.appState().toolPool().stream().map(t -> t.name()).toList());
        r.put("transcriptSize",  target.appState().transcript().size());
        r.put("contextWindow",   target.appState().contextWindow());
        r.put("maxTurns",        target.queryEngine().maxTurnsPerQuery());
        r.put("loopWindow",      target.queryEngine().loopDetectWindow());
        r.put("loopThreshold",   target.queryEngine().loopDetectThreshold());
        // surface the per-session skip-confirmation counter
        // so the TUI / Desktop status bar can show "skip: 3"
        // without subscribing to the live notification stream.
        r.put("skipConfirmationRemaining",
                target.skipConfirmationRegistry().remaining(target.appState().sessionId()));
        // surface the last skip-low snapshot. Null when
        // the counter has not crossed the waterline yet on
        // this engine. Lets a reconnecting client render
        // "skip running low" even if it missed the live
        // notification.
        target.lastSkipLow().ifPresentOrElse(snap -> r.put("skipLow", Map.of(
                "sessionId", snap.sessionId(),
                "remaining", snap.remaining(),
                "atMs",      snap.atMs()
        )), () -> r.put("skipLow", null));
        // include the cached permission-mode
        // suggestion. Null when the suggester failed.
        org.aethercode.config.PermissionModeSuggester.Suggestion s =
                target.permissionModeSuggestion();
        if (s == null) {
            r.put("permissionModeSuggestion", null);
        } else {
            r.put("permissionModeSuggestion", Map.of(
                    "mode",    s.mode().name(),
                    "reasons", s.reasons()
            ));
        }
        // include adoption stats. consumed / armed /
        // prompts. The UI uses this to show "4/7 prompts used
        // skip" without subscribing to the live stream.
        AetherCodeEngine.SkipStats ss = target.skipStats();
        r.put("skipStats", Map.of(
                "consumed", ss.consumed(),
                "armed",    ss.armed(),
                "prompts",  ss.prompts(),
                "adoption", ss.adoption(),
                // per-tool breakdown. Tools that were
                // never auto-allowed are not in the map.
                "byTool",   target.skipStatsByTool()
        ));
        return r;
    }

    /**
     * dedicated RPC for the skip-confirmation adoption
     * stats. Returns the same shape as {@code getState().skipStats}
     * but as a top-level call so the UI can refresh it on a
     * tighter interval (e.g. once per turn) without paying for
     * the full state snapshot.
     *
     * <p>R107: also includes {@code byTool} —a map of
     * tool-name to consume count, sorted by count desc. Empty
     * tools (never auto-allowed) are not in the map.
     */
    public Object getSkipStats(Object params) {
        Map<String, Object> p = asMap(params);
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        AetherCodeEngine.SkipStats ss = target.skipStats();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", target.appState().sessionId());
        r.put("consumed",  ss.consumed());
        r.put("armed",     ss.armed());
        r.put("prompts",   ss.prompts());
        r.put("adoption",  ss.adoption());
        r.put("byTool",    target.skipStatsByTool());
        // surface the current waterline + last skip-low
        // snapshot so the UI can decide whether to render
        // "skip running low" even after a reconnect.
        r.put("lowWaterline", target.skipLowWaterline());
        target.lastSkipLow().ifPresent(snap -> r.put("lastSkipLow", Map.of(
                "sessionId", snap.sessionId(),
                "remaining", snap.remaining(),
                "atMs",      snap.atMs()
        )));
        return r;
    }

    /** return the active system prompt as a
     *  structured payload. The shape is:
     *  <pre>
     *    {
     *      text:         String,  // the full rendered prompt
     *      totalChars:   Number,
     *      sectionCount: Number,
     *      sections: [
     *        { name, length, source, firstLine }, ...
     *      ]
     *    }
     *  </pre>
     *  The TUI /prompt command shows this so the user
     *  can verify what the model is actually seeing.
     *  The "source" label is the provenance tag the
     *  built-in identity / workflow use (legacy), and
     *  the per-section "firstLine" gives a one-line
     *  preview so the TUI can show the structure
     *  without dumping the whole prompt.
     */
    public Object getSystemPrompt(Object params) {
        org.aethercode.prompts.RenderedPrompt rp = engine.currentRenderedPrompt();
        java.util.List<Map<String, Object>> sections = new java.util.ArrayList<>();
        for (org.aethercode.prompts.RenderedPrompt.Section s : rp.sections()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",      s.name());
            m.put("length",    s.text().length());
            m.put("source",    s.source());
            m.put("firstLine", firstLine(s.text()));
            // surface the per-layer file paths
            // for the rules section. The TUI's
            // /prompt table shows these as a
            // comma-separated column so the user can
            // answer "where is this rule coming
            // from?" without /prompt <name> drill-down.
            // The path list comes from a thread-local
            // accumulator on the RulesLoader; see
            // RulesLoader.lastLoadedFileNames() for the
            // contract. Sections other than "rules"
            // (identity / workflow / environment /
            // tooling / planMode / memory) do not
            // produce a list —the field is omitted so
            // the wire shape stays simple.
            if ("rules".equals(s.name())) {
                m.put("paths", org.aethercode.prompts.RulesLoader.lastLoadedFileNames());
            }
            sections.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("text",         rp.text());
        r.put("totalChars",   rp.text().length());
        r.put("sectionCount", rp.sections().size());
        r.put("sections",     sections);
        return r;
    }

    /** drill-down for {@code /prompt <name>}. Returns the
     *  full text of one section (no truncation) plus its
     *  metadata, or {@code {ok: false, error: "...",
     *  available: [...]}} if no section matches. The match is
     *  case-insensitive on {@code name}. A missing or blank
     *  {@code name} returns {@code ok: false} with a clear
     *  error.
     *
     *  <p>Why a separate RPC: the TUI's {@code /prompt} (legacy)
     *  surfaces a table with each section's first line. The user
     *  asked for "click a section to see its full text" —that's
     *  what this RPC powers. We deliberately do NOT include the
     *  full text in the {@code getSystemPrompt} response because
     *  the table can stay narrow (one row per section) and a
     *  3,000+ char section would otherwise blow out the
     *  scrollback.
     *
     *  <p>Param shape: accepts a {@code {name: "..."}} map (the
     *  canonical form), a bare string (treated as the name), or
     *  null. Anything else returns {@code ok: false} with a
     *  clear error. */
    public Object getSystemPromptSection(Object params) {
        String name = null;
        if (params instanceof java.util.Map<?, ?> m) {
            Object v = m.get("name");
            if (v != null) name = v.toString();
        } else if (params instanceof String s) {
            name = s;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            r.put("ok", false);
            r.put("error", "missing required field 'name'");
            return r;
        }
        org.aethercode.prompts.RenderedPrompt rp = engine.currentRenderedPrompt();
        for (org.aethercode.prompts.RenderedPrompt.Section s : rp.sections()) {
            if (s.name() != null && s.name().equalsIgnoreCase(name)) {
                r.put("ok",     true);
                r.put("name",   s.name());
                r.put("source", s.source());
                r.put("length", s.text().length());
                r.put("text",   s.text());
                return r;
            }
        }
        // No match. Return the list of available names so the
        // TUI can show "did you mean..." without a second RPC.
        java.util.List<String> available = new java.util.ArrayList<>();
        for (org.aethercode.prompts.RenderedPrompt.Section s : rp.sections()) {
            if (s.name() != null) available.add(s.name());
        }
        r.put("ok",        false);
        r.put("error",     "section not found: " + name);
        r.put("available", available);
        return r;
    }

    /** per-phase tool-call budget snapshot.
     *  Shape: {currentPhase, buckets: [{phase, toolCalls,
     *  costUsd, maxToolCalls, maxCostUsd, remainingToolCalls,
     *  remainingCostUsd}, ...], overBudget: bool}. Returns
     *  {@code {ok: false, error: "phase tracker not configured"}}
     *  when the engine was built without a
     *  {@code PhaseTracker} (the default —opt-in). */
    public Object getPhaseBudget(Object params) {
        org.aethercode.hooks.builtin.PhaseTracker t = engine.phaseTracker();
        Map<String, Object> r = new LinkedHashMap<>();
        if (t == null) {
            r.put("ok", false);
            r.put("error", "phase tracker not configured (set AetherCodeEngine.Builder.phaseTracker)");
            return r;
        }
        org.aethercode.hooks.builtin.PhaseTracker.Snapshot s = t.snapshot();
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (Map.Entry<String, org.aethercode.hooks.builtin.PhaseTracker.Bucket> e : s.buckets().entrySet()) {
            org.aethercode.hooks.builtin.PhaseTracker.Bucket b = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase",               e.getKey());
            m.put("toolCalls",           b.toolCalls);
            m.put("costUsd",             b.costUsd);
            m.put("maxToolCalls",        b.maxToolCalls);
            m.put("maxCostUsd",          b.maxCostUsd);
            m.put("remainingToolCalls",  b.remainingToolCalls());
            m.put("remainingCostUsd",    b.remainingCostUsd());
            buckets.add(m);
        }
        r.put("ok",           true);
        r.put("currentPhase", s.currentPhase());
        r.put("overBudget",   t.isOverBudget());
        r.put("buckets",      buckets);
        return r;
    }

    /** transition the tracker to a new phase.
     *  Accepts a {@code {name: "..."}} map. A missing /
     *  blank name returns {@code ok: false}. */
    public Object setPhase(Object params) {
        org.aethercode.hooks.builtin.PhaseTracker t = engine.phaseTracker();
        Map<String, Object> r = new LinkedHashMap<>();
        if (t == null) {
            r.put("ok", false);
            r.put("error", "phase tracker not configured");
            return r;
        }
        String name = null;
        if (params instanceof Map<?, ?> m) {
            Object v = m.get("name");
            if (v != null) name = v.toString();
        } else if (params instanceof String s) {
            name = s;
        }
        if (name == null || name.isBlank()) {
            r.put("ok", false);
            r.put("error", "missing required field 'name'");
            return r;
        }
        try {
            t.setPhase(name);
        } catch (IllegalArgumentException iae) {
            r.put("ok", false);
            r.put("error", iae.getMessage());
            return r;
        }
        r.put("ok", true);
        r.put("currentPhase", t.currentPhase());
        return r;
    }

    /** reconfigure a phase's cap. Accepts
     *  {@code {phase, maxToolCalls, maxCostUsd}}. Negative
     *  numbers are rejected with {@code ok: false}. A
     *  cap of 0 means "unlimited" (matches the
     *  PhaseTracker convention). */
    public Object setPhaseBudget(Object params) {
        org.aethercode.hooks.builtin.PhaseTracker t = engine.phaseTracker();
        Map<String, Object> r = new LinkedHashMap<>();
        if (t == null) {
            r.put("ok", false);
            r.put("error", "phase tracker not configured");
            return r;
        }
        if (!(params instanceof Map<?, ?> m)) {
            r.put("ok", false);
            r.put("error", "params must be an object {phase, maxToolCalls, maxCostUsd}");
            return r;
        }
        String phase = m.get("phase") == null ? null : m.get("phase").toString();
        Object mtObj = m.get("maxToolCalls");
        Object mcObj = m.get("maxCostUsd");
        if (phase == null || phase.isBlank() || mtObj == null || mcObj == null) {
            r.put("ok", false);
            r.put("error", "missing required field (phase / maxToolCalls / maxCostUsd)");
            return r;
        }
        int maxCalls;
        double maxCost;
        try {
            maxCalls = (mtObj instanceof Number) ? ((Number) mtObj).intValue() : Integer.parseInt(mtObj.toString());
            maxCost  = (mcObj instanceof Number) ? ((Number) mcObj).doubleValue() : Double.parseDouble(mcObj.toString());
        } catch (NumberFormatException nfe) {
            r.put("ok", false);
            r.put("error", "maxToolCalls / maxCostUsd must be numeric");
            return r;
        }
        try {
            t.setBudget(phase, maxCalls, maxCost);
        } catch (IllegalArgumentException iae) {
            r.put("ok", false);
            r.put("error", iae.getMessage());
            return r;
        }
        r.put("ok", true);
        r.put("phase", phase);
        r.put("maxToolCalls", maxCalls);
        r.put("maxCostUsd", maxCost);
        return r;
    }

    // -------------------------------------------------------------------
    // multi-session surface.
    //
    //  Every RPC in this block is a no-op (returns
    //  ok=false with a clear error) when the engine was
    //  built without a SessionManager —the default
    //  single-engine path stays backward-compatible.
    // -------------------------------------------------------------------

    /** list every registered session, plus the
     *  active session id. The shape is
     *  {@code {ok, activeSessionId, sessions: [{sessionId,
     *  createdAtMs, lastAccessMs, ageMs, idleMs, model,
     *  permissionMode}, ...]}}. Insertion-ordered.
     *
     *  <p>legacy: resolves the SessionManager via
     *  {@link #effectiveSessionManager()} so the
     *  manager wired by the daemon (3-arg ctor) wins
     *  over the engine's own accessor. */
    public Object listEngines(Object params) {
        org.aethercode.sdk.SessionManager m = effectiveSessionManager();
        Map<String, Object> r = new LinkedHashMap<>();
        if (m == null) {
            r.put("ok", false);
            r.put("error", "session manager not configured (set AetherCodeEngine.Builder.sessionManager or pass SessionManager to AetherCodeMethods constructor)");
            return r;
        }
        r.put("ok", true);
        r.put("activeSessionId", m.activeSessionId());
        r.put("count", m.size());
        r.put("sessions", m.wireSnapshot());
        return r;
    }

    /** create a new session. Accepts
     *  {@code {sessionId: "..."}} (a string fallback is
     *  tolerated). Returns
     *  {@code {ok, sessionId, created: bool, alreadyExists: bool, active: bool}}.
     *
     *  <p>legacy: routes through
     *  {@link #effectiveSessionManager()}. */
    public Object createEngine(Object params) {
        org.aethercode.sdk.SessionManager m = effectiveSessionManager();
        Map<String, Object> r = new LinkedHashMap<>();
        if (m == null) {
            r.put("ok", false);
            r.put("error", "session manager not configured");
            return r;
        }
        String id = null;
        if (params instanceof Map<?, ?> mp) {
            Object v = mp.get("sessionId");
            if (v != null) id = v.toString();
        } else if (params instanceof String s) {
            id = s;
        }
        if (id == null || id.isBlank()) {
            r.put("ok", false);
            r.put("error", "missing required field 'sessionId'");
            return r;
        }
        org.aethercode.sdk.SessionManager.EngineHandle existing = m.get(id);
        if (existing != null) {
            r.put("ok", true);
            r.put("sessionId", id);
            r.put("created", false);
            r.put("alreadyExists", true);
            r.put("active", id.equals(m.activeSessionId()));
            return r;
        }
        try {
            org.aethercode.sdk.SessionManager.EngineHandle h = m.create(id);
            r.put("ok", true);
            r.put("sessionId", id);
            r.put("created", h != null);
            r.put("alreadyExists", false);
            r.put("active", id.equals(m.activeSessionId()));
            return r;
        } catch (RuntimeException re) {
            r.put("ok", false);
            r.put("error", re.getMessage());
            return r;
        }
    }

    /** remove a session. Accepts
     *  {@code {sessionId: "..."}}. The default session
     *  is protected —the manager returns false. The
     *  active session is re-set to the default if it
     *  was the deleted one.
     *
     *  <p>legacy: routes through
     *  {@link #effectiveSessionManager()}. */
    public Object deleteEngine(Object params) {
        org.aethercode.sdk.SessionManager m = effectiveSessionManager();
        Map<String, Object> r = new LinkedHashMap<>();
        if (m == null) {
            r.put("ok", false);
            r.put("error", "session manager not configured");
            return r;
        }
        String id = null;
        if (params instanceof Map<?, ?> mp) {
            Object v = mp.get("sessionId");
            if (v != null) id = v.toString();
        } else if (params instanceof String s) {
            id = s;
        }
        if (id == null || id.isBlank()) {
            r.put("ok", false);
            r.put("error", "missing required field 'sessionId'");
            return r;
        }
        boolean removed = m.delete(id);
        r.put("ok", true);
        r.put("sessionId", id);
        r.put("removed", removed);
        r.put("activeSessionId", m.activeSessionId());
        return r;
    }

    /** set the active session. The session must
     *  already exist (callers typically use
     *  {@code createSession} first). Accepts
     *  {@code {sessionId: "..."}}.
     *
     *  <p>legacy: routes through
     *  {@link #effectiveSessionManager()}. */
    public Object setActiveEngine(Object params) {
        org.aethercode.sdk.SessionManager m = effectiveSessionManager();
        Map<String, Object> r = new LinkedHashMap<>();
        if (m == null) {
            r.put("ok", false);
            r.put("error", "session manager not configured");
            return r;
        }
        String id = null;
        if (params instanceof Map<?, ?> mp) {
            Object v = mp.get("sessionId");
            if (v != null) id = v.toString();
        } else if (params instanceof String s) {
            id = s;
        }
        if (id == null || id.isBlank()) {
            r.put("ok", false);
            r.put("error", "missing required field 'sessionId'");
            return r;
        }
        try {
            m.setActive(id);
        } catch (IllegalArgumentException iae) {
            r.put("ok", false);
            r.put("error", iae.getMessage());
            return r;
        }
        r.put("ok", true);
        r.put("activeSessionId", m.activeSessionId());
        return r;
    }

    /** read the active session id.
     *
     *  <p>legacy: routes through
     *  {@link #effectiveSessionManager()}. */
    public Object getActiveEngine(Object params) {
        org.aethercode.sdk.SessionManager m = effectiveSessionManager();
        Map<String, Object> r = new LinkedHashMap<>();
        if (m == null) {
            r.put("ok", false);
            r.put("error", "session manager not configured");
            return r;
        }
        r.put("ok", true);
        r.put("activeSessionId", m.activeSessionId());
        return r;
    }

    /** extract the first non-blank line of
     *  {@code text} as a short preview. Capped at 80
     *  characters so a long line does not blow up the
     *  payload. */
    private static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.split("\n", 3)) {
            String t = line.strip();
            if (!t.isEmpty()) {
                return t.length() > 80 ? t.substring(0, 80) + "..." : t;
            }
        }
        return "";
    }

    /** return the engine's metrics snapshot. The shape is
     *  a flat object of long counters + a costUsd double. */
    public Object getMetrics(Object params) {
        return engine.metrics().snapshot();
    }

    /** list the model ids known to the engine's
     *  CostTracker. Shape: {@code {models: ["claude-...", ...]}}.
     *  Each entry also carries the (input, output) USD price per
     *  1k tokens for the settings panel. The first entry is the
     *  engine's current default. */
    public Object listModels(Object params) {
        java.util.List<String> models = engine.costTracker().knownModels();
        java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
        for (String m : models) {
            double[] p = engine.costTracker().priceFor(m);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id",          m);
            e.put("inputPer1k",  p == null ? 0.0 : p[0]);
            e.put("outputPer1k", p == null ? 0.0 : p[1]);
            e.put("default",     m.equals(engine.appState().mainLoopModel()));
            entries.add(e);
        }
        return Map.of("models", entries, "default", engine.appState().mainLoopModel());
    }

    /** return the engine's recent trace spans. The params
     *  object is optional; if absent or {@code limit} is missing
     *  we default to 10 spans. The shape is
     *  {@code {inFlight, completed, traces: [...]}}. */
    @SuppressWarnings("unchecked")
    public Object getTraces(Object params) {
        int limit = 10;
        if (params instanceof Map) {
            Object l = ((Map<String, Object>) params).get("limit");
            if (l instanceof Number n) {
                int v = n.intValue();
                if (v > 0 && v <= 256) limit = v;
            }
        }
        return engine.traces().snapshot(limit);
    }

    /** return a single trace (one root span + all of its
     *  descendants currently retained in the deque). The params
     *  object must contain a {@code traceId} field. The shape is
     *  {@code {traceId, inFlight, completed, spans: [...]}}. */
    @SuppressWarnings("unchecked")
    public Object getTrace(Object params) {
        Map<String, Object> p = asMap(params);
        String traceId = stringOrThrow(p, "traceId");
        return engine.traces().snapshotForTrace(traceId);
    }

    public Object listTools(Object params) {
        // per-RPC sessionId routing. When
        // sessionId is provided, list the tool pool
        // of the engine registered for that id.
        Map<String, Object> p = asMap(params);
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (var t : target.appState().toolPool()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",        t.name());
            entry.put("description", t.description() != null ? t.description() : "");
            tools.add(entry);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", target.appState().sessionId());
        r.put("tools", tools);
        return r;
    }

    /**
     * list each tool together with its current default
     * permission action. The action is computed by:
     * <ol>
     *   <li>If {@code tool.isReadOnly(emptyInput) == true}, the tool is
     *       unconditionally {@code ALLOW} (defence in depth —read-only
     *       tools bypass the matrix).</li>
     *   <li>Otherwise we feed a typical sample input through
     *       {@link org.aethercode.config.OpKindDetector} to get a
     *       representative {@link org.aethercode.config.OpKind}, then
     *       look it up in the matrix.</li>
     * </ol>
     * The {@code isSafe} flag is true exactly when the default action
     * is ALLOW. UIs use it to show a "safe" badge in the tool list.
     *
     * <p>This is best-effort: the matrix may have ASK rules for the
     * default op-kind and the live {@code ProjectPermissionPolicy} may
     * still prompt on a real call. The flag is a hint, not a contract.
     */
    public Object listToolActions(Object params) {
        Map<String, Object> p = asMap(params);
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        org.aethercode.config.PermissionMatrix matrix = null;
        if (target.policy() instanceof org.aethercode.permission.MatrixPermissionPolicy mpp) {
            matrix = mpp.matrix();
        }
        java.nio.file.Path cwd = target.appState().cwd();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (var t : target.appState().toolPool()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",        t.name());
            entry.put("description", t.description() != null ? t.description() : "");

            // 1. Read-only tools are unconditionally safe.
            boolean toolIsReadOnly = t.isReadOnly(Map.of());
            // 2. Compute the default op-kind from a sample input.
            Map<String, Object> sample = sampleInputFor(t.name());
            org.aethercode.config.OpKind opKind =
                    org.aethercode.config.OpKindDetector.detect(t.name(), sample, cwd);
            String samplePath = sample.containsKey("file_path")
                    ? String.valueOf(sample.get("file_path"))
                    : (sample.containsKey("command")
                            ? String.valueOf(sample.get("command"))
                            : null);
            entry.put("defaultOpKind", opKind.name());
            entry.put("samplePath",    samplePath);
            entry.put("isReadOnly",    toolIsReadOnly);

            // 3. Look up the action. Read-only tools get ALLOW
            //    without consulting the matrix.
            String action;
            if (toolIsReadOnly) {
                action = "ALLOW";
            } else if (matrix != null) {
                action = matrix.lookup(t.name(), samplePath, opKind).name();
            } else {
                action = "ASK";
            }
            entry.put("defaultAction", action);
            entry.put("isSafe",        "ALLOW".equals(action));
            tools.add(entry);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", target.appState().sessionId());
        r.put("tools",     tools);
        return r;
    }

    /**
     * produce a representative sample input for a tool so
     * {@link org.aethercode.config.OpKindDetector} can pick the
     * "default" op-kind for {@code listToolActions}. The samples
     * are deliberately conservative: they exercise the most common
     * case the user would hit, not the worst case.
     */
    private static Map<String, Object> sampleInputFor(String toolName) {
        if (toolName == null) return Map.of();
        return switch (toolName.toLowerCase(java.util.Locale.ROOT)) {
            case "file_read"   -> Map.of("file_path", "src/main/java/Foo.java");
            case "file_write"  -> Map.of("file_path", "src/main/java/Foo.java");
            case "file_edit"   -> Map.of("file_path", "src/main/java/Foo.java",
                                        "old_string", "a", "new_string", "b");
            case "notebook_edit" -> Map.of("file_path", "notebook.ipynb",
                                            "old_string", "a", "new_string", "b");
            case "bash", "shell" -> Map.of("command", "mvn -B test");
            case "glob"        -> Map.of("pattern", "**/*.java");
            case "grep"        -> Map.of("pattern", "TODO");
            case "web_fetch"   -> Map.of("url", "https://example.com");
            case "web_search"  -> Map.of("query", "junit testing");
            case "ask_user_question" -> Map.of("question", "Which approach?");
            case "todo_write"  -> Map.of("todos", java.util.List.of());
            case "sub_todo_write" -> Map.of("todos", java.util.List.of());
            default            -> Map.of();
        };
    }

    @SuppressWarnings("unchecked")
    public Object setModel(Object params) {
        Map<String, Object> p = asMap(params);
        String model = stringOrThrow(p, "model");
        // per-RPC sessionId routing.
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        // AppState.mainLoopModel(String) updates the model id; the
        // engine rebuilds the chat client on the next query.
        target.appState().mainLoopModel(model);
        return Map.of("sessionId", target.appState().sessionId(), "model", model);
    }

    @SuppressWarnings("unchecked")
    public Object setPermissionMode(Object params) {
        Map<String, Object> p = asMap(params);
        String modeStr = stringOrThrow(p, "mode");
        PermissionMode mode;
        try {
            mode = PermissionMode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            throw invalidParams(
                    "unknown permission mode: " + modeStr +
                    "; valid: " + java.util.Arrays.toString(PermissionMode.values()));
        }
        // per-RPC sessionId routing.
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        // the live policy also has to know about the new mode.
        // The previous code only updated AppState, so the constructed
        // ProjectPermissionPolicy kept the original mode and every
        // tool call continued to behave per the original mode (e.g.
        // DEFAULT ask-permission) even though the user had switched
        // to ACCEPT_TASK or BYPASS_PERMISSIONS in the TUI.
        target.appState().permissionMode(mode);
        target.setPermissionMode(mode);
        return Map.of("sessionId", target.appState().sessionId(), "mode", mode.name());
    }

    /**
     * return the cached permission-mode suggestion
     * for the project's root. The shape is:
     * <pre>
     *   {
     *     "sessionId":      String,
     *     "currentMode":    String,   // the user's effective mode
     *     "suggestedMode":  String,   // null when the suggester failed
     *     "reasons":        [String]  // non-empty when suggestedMode is non-null
     *   }
     * </pre>
     *
     * <p>The UI uses this to render "💡 suggested: ACCEPT_TASK"
     * next to the current mode. The user can accept via
     * {@code setPermissionMode} (which is the existing RPC).
     */
    public Object getPermissionModeSuggestion(Object params) {
        Map<String, Object> p = asMap(params);
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("sessionId",   target.appState().sessionId());
        r.put("currentMode", target.appState().permissionMode().name());
        org.aethercode.config.PermissionModeSuggester.Suggestion s =
                target.permissionModeSuggestion();
        if (s == null) {
            r.put("suggestedMode", null);
            r.put("reasons",       java.util.List.of());
        } else {
            r.put("suggestedMode", s.mode().name());
            r.put("reasons",       s.reasons());
        }
        return r;
    }

    /**
     * set the per-session skip-confirmation counter. The next {@code
     * rounds} tool calls that would otherwise prompt the user are
     * auto-allowed; the counter then returns to 0 and normal confirmation
     * flow resumes. {@code rounds <= 0} clears the counter.
     *
     * <p>Common usage: the model sees a user message like
     * "no confirmation needed for next 5 rounds" and calls this RPC.
     * The model can also detect the pattern automatically via
     * {@link org.aethercode.config.SkipConfirmationDetector}, but the
     * RPC is the explicit hook.
     *
     * <p>R99: also emits a {@link #NOTIFY_SKIP_CONFIRMATION} notification
     * so the TUI / Desktop status bar updates immediately without
     * waiting for the next {@code getState} poll.
     */
    public Object setSkipConfirmation(Object params) {
        Map<String, Object> p = asMap(params);
        int rounds = intOrThrow(p, "rounds");
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        target.skipConfirmationRegistry().set(target.appState().sessionId(), rounds);
        int remaining = target.skipConfirmationRegistry()
                .remaining(target.appState().sessionId());
        // notify the UI so the status bar updates immediately.
        notifyCustom(NOTIFY_SKIP_CONFIRMATION, Map.of(
                "sessionId", target.appState().sessionId(),
                "remaining", remaining,
                "source", "rpc"
        ));
        return Map.of("ok", true, "sessionId", target.appState().sessionId(),
                "rounds", rounds,
                "remaining", remaining);
    }

    /**
     * runtime loop-detector threshold tweak. The
     * Settings panel's "Loop Detection" section calls
     * this when the user drags a slider. the legacy
     * window / threshold pair was fixed at engine build
     * time; the only way to change them was to
     * re-spawn the JVM, which is a bad UX for "I want
     * the detector to be more lenient right now".
     *
     * <p>Validation: both fields are integers, 0 or
     * negative disables the detector (the underlying
     * {@code QueryEngine.setLoopDetector} maps any
     * negative value to -1 internally, which the
     * per-query builder reads as "skip the detector
     * entirely"). The window must be at least the
     * threshold —a window smaller than the threshold
     * would let the detector fire immediately on a
     * single fingerprint, which is rarely what the
     * user wants.
     *
     * <p>Return shape: {@code {ok, sessionId, window,
     * threshold, disabled}} so the caller can confirm
     * the new values landed and which mode the
     * detector is in.
     */
    public Object setLoopDetectorThresholds(Object params) {
        Map<String, Object> p = asMap(params);
        int window = intOrThrow(p, "window");
        int threshold = intOrThrow(p, "threshold");
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no such sessionId: " + sid);
        }
        // Sanity check: window must be >= threshold when
        // both are positive. A negative value on either
        // axis means "disabled" (per QueryEngine contract).
        if (window > 0 && threshold > 0 && window < threshold) {
            return Map.of("ok", false, "error",
                    "window (" + window + ") must be >= threshold (" + threshold + ")");
        }
        if (threshold <= 0 && window > 0) {
            return Map.of("ok", false, "error",
                    "threshold must be > 0 when window > 0 (set window=0 to disable)");
        }
        // Delegate. QueryEngine.setLoopDetector stores
        // both values in volatile fields; the next
        // query() call (or currentLoopDetector() if a
        // query is in flight) reads them. No re-spawn
        // needed.
        target.queryEngine().setLoopDetector(window, threshold);
        boolean disabled = window <= 0 || threshold <= 0;
        return Map.of(
                "ok", true,
                "sessionId", target.appState().sessionId(),
                "window", disabled ? -1 : window,
                "threshold", disabled ? -1 : threshold,
                "disabled", disabled
        );
    }

    /**
     * toggle the daemon-side "auto-approve low
     * risk tool calls" flag. When enabled (the
     * default), {@code JsonRpcPermissionPrompter}
     * short-circuits low-risk tool calls (read-only,
     * glob, grep, search, list) —instead of emitting
     * {@code permission_request} and waiting for the
     * client's {@code permissionResponse}, the
     * prompter returns {@code Allow} immediately and
     * emits {@code NOTIFY_PERMISSION_AUTO_APPROVED}
     * so the UI can show an "auto-allowed" badge.
     *
     * <p>When disabled, low-risk calls fall through
     * to the normal prompt path (the user clicks
     * Allow/Deny per call, the same as medium / high
     * risk).
     *
     * <p>Validation: {@code enabled} is a boolean
     * (no string coercion). The toggle is one-way per
     * call —the renderer re-fires with the new value
     * rather than relying on a flip in the same RPC.
     */
    public Object setAutoApproveLowRisk(Object params) {
        Map<String, Object> p = asMap(params);
        if (!p.containsKey("enabled")) {
            throw invalidParams("missing required field: enabled");
        }
        Object raw = p.get("enabled");
        boolean enabled;
        if (raw instanceof Boolean) {
            enabled = (Boolean) raw;
        } else if (raw instanceof Number) {
            // 0 = false, anything else = true. The
            // Tauri→JS layer sometimes serialises a
            // boolean as 0/1.
            enabled = ((Number) raw).intValue() != 0;
        } else {
            throw invalidParams("enabled must be a boolean");
        }
        this.autoApproveLowRisk = enabled;
        return Map.of(
                "ok", true,
                "enabled", enabled,
                "autoApprovedCount", autoApprovedCount.get()
        );
    }

    /**
     * wire-level handler for
     * {@code setAutoApproveMediumHigh}. Same shape as
     * {@link #setAutoApproveLowRisk} (boolean / 0-1)
     * so the renderer's typed wrapper works
     * unchanged. Response carries BOTH counters so
     * a single round-trip refreshes the StatusBar
     * badge pair.
     */
    public Object setAutoApproveMediumHigh(Object params) {
        Map<String, Object> p = asMap(params);
        if (!p.containsKey("enabled")) {
            throw invalidParams("missing required field: enabled");
        }
        Object raw = p.get("enabled");
        boolean enabled;
        if (raw instanceof Boolean) {
            enabled = (Boolean) raw;
        } else if (raw instanceof Number) {
            enabled = ((Number) raw).intValue() != 0;
        } else {
            throw invalidParams("enabled must be a boolean");
        }
        this.autoApproveMediumHigh = enabled;
        return Map.of(
                "ok", true,
                "enabled", enabled,
                "autoApprovedCount", autoApprovedCount.get(),
                "autoApprovedElevatedCount", autoApprovedElevatedCount.get()
        );
    }

    /**
     * increment the auto-approved counter +
     * emit the notification. Called by
     * {@link org.aethercode.protocol.permissions.JsonRpcPermissionPrompter}
     * immediately after returning Allow. Returns
     * the new count (so the prompter doesn't need
     * its own AtomicLong read after the call).
     *
     * <p>R126: the {@code riskLevel} parameter routes
     * the increment to the right counter —"low"
     * bumps {@link #autoApprovedCount}, anything
     * else bumps {@link #autoApprovedElevatedCount}.
     * The notification payload always carries the
     * riskLevel so a StatusBar badge can colour-code
     * ("✓ auto-allow: 12" vs "⚠ auto-allow high: 3").
     */
    public long recordAutoApproved(String toolName, Map<String, Object> input, String reason, String riskLevel) {
        boolean elevated = !"low".equals(riskLevel);
        java.util.concurrent.atomic.AtomicLong counter = elevated ? autoApprovedElevatedCount : autoApprovedCount;
        long n = counter.incrementAndGet();
        if (notifier != null) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("requestId", "auto-" + java.util.UUID.randomUUID());
            payload.put("tool", toolName);
            payload.put("input", input);
            payload.put("reason", reason);
            payload.put("riskLevel", riskLevel);
            payload.put("atMs", System.currentTimeMillis());
            payload.put("autoApprovedCount", autoApprovedCount.get());
            payload.put("autoApprovedElevatedCount", autoApprovedElevatedCount.get());
            notifyCustom(NOTIFY_PERMISSION_AUTO_APPROVED, payload);
        }
        return n;
    }

    /**
     * legacy 3-arg overload retained for the
     * existing test suite (which does not know about
     * risk levels). Defaults {@code riskLevel} to
     * {@code "low"} so the test counter still bumps
     * the right bucket.
     */
    public long recordAutoApproved(String toolName, Map<String, Object> input, String reason) {
        return recordAutoApproved(toolName, input, reason, "low");
    }

    private static int intOrThrow(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) throw invalidParams("missing required field: " + key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt(((String) v).trim()); }
            catch (NumberFormatException e) { throw invalidParams(key + " must be an integer"); }
        }
        throw invalidParams(key + " must be an integer");
    }

    @SuppressWarnings("unchecked")
    public Object setSystemPrompt(Object params) {
        Map<String, Object> p = asMap(params);
        String prompt = stringOrThrow(p, "prompt");
        engine.queryEngine().setSystemPrompt(prompt);
        return Map.of("ok", true, "length", prompt.length());
    }

    @SuppressWarnings("unchecked")
    public Object query(Object params) {
        // per-RPC sessionId routing. When the caller passes
        // a sessionId and a SessionManager is wired, dispatch to
        // the engine registered for that id. Otherwise (the
        // legacy / single-engine path) route to the active engine
        // via currentEngine() (which falls back to the constructor
        // engine when no manager is wired).
        Map<String, Object> _qp = asMap(params);
        String _qsid = _qp.get("sessionId") instanceof String _qs ? _qs : null;
        final AetherCodeEngine target;
        if (_qsid != null && !_qsid.isBlank()) {
            org.aethercode.sdk.SessionManager _sm = effectiveSessionManager();
            if (_sm == null) {
                return Map.of("ok", false, "error",
                        "session manager not configured (cannot route query to sessionId)");
            }
            org.aethercode.sdk.SessionManager.EngineHandle _h = _sm.get(_qsid);
            if (_h == null) {
                return Map.of("ok", false, "error", "no such sessionId: " + _qsid);
            }
            target = _h.engine;
        } else {
            target = currentEngine();
        }

        Map<String, Object> p = asMap(params);
        String prompt = stringOrThrow(p, "prompt");
        String _targetSid = target.appState().sessionId();
        // a fresh user prompt (1) clears the
        // "auto-continue stopped" flag, AND (2) cancels
        // any PENDING continuation countdown, AND (3)
        // acquires the session-level run lock.
        //
        // Without this fix, the user-typed "Continue" races
        // with the boulder hook's 2s auto-continue, and
        // the two queries overlap. The TUI's transcript
        // mixes messages from both runs, the WS sees
        // interleaved events, and one of the runs often
        // fails because the session's in-flight lock is
        // already held. The user reads this as
        // "connection broken" even though the daemon is
        // fine.
        if (continuationDispatcher != null && _targetSid != null) {
            continuationDispatcher.setContinuationStopped(_targetSid, false);
            // Cancel any scheduled continuation dispatch
            // (the 2s countdown timer). Without this the
            // countdown can fire mid-user-query and we
            // get the race.
            cancelPendingContinuationCountdown(_targetSid);
        }
        // session-level run lock. If a previous
        // run for this session is still in flight
        // (either the user's previous turn or the
        // auto-continue dispatch), refuse the new query
        // and tell the renderer to wait. Without this
        // gate, two engine.query() streams can be open
        // for the same session simultaneously, and the
        // engine's internal state machine gets confused
        // (transcript appends from both, tool_use_start
        // events arrive interleaved, and the user sees
        // a non-deterministic "lag" feel).
        //
        // We mint the runId FIRST and register it as
        // the lock owner, so the dispatcher (and
        // anyone inspecting sessionRunLock) can see
        // which run holds the slot.
        String runId = "run-" + runCounter.incrementAndGet();
        if (_targetSid != null) {
            String existing = sessionRunLock.putIfAbsent(_targetSid, runId);
            if (existing != null) {
                // Don't reject outright —the user might
                // have hit Enter twice. Wait briefly for
                // the previous run to finish, then try
                // again. 800ms is a soft cap; if the
                // previous run is still streaming, the
                // caller's sendMessage will surface the
                // rejection and the user can hit Enter
                // again once the run settles.
                long deadline = System.currentTimeMillis() + 800;
                while (existing != null && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(40); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    existing = sessionRunLock.get(_targetSid);
                }
                if (existing != null) {
                    return Map.of("ok", false, "error",
                            "session is busy with run " + existing
                                    + "; please wait for it to finish (or press Esc to cancel)",
                            "busyRunId", existing);
                }
                // Got the lock; register our runId.
                String still = sessionRunLock.putIfAbsent(_targetSid, runId);
                if (still != null) {
                    return Map.of("ok", false, "error",
                            "session is busy with run " + still,
                            "busyRunId", still);
                }
            }
        }
        // Each query gets a run id so the client can correlate
        // stream_event notifications with the call that triggered
        // them. The engine's own Task ID is the model-side handle.
        // (legacy: runId is minted ABOVE so the session-level
        // run lock can register it as the owner before any
        // events flow.)

        CompletableFuture<Void> done = new CompletableFuture<>();
        inFlight.put(runId, done);

        Thread t = new Thread(() -> {
            // open a root span for the whole query. We close it
            // on RunEnd (ok) or in the catch block (error). R79:
            // tool spans are now children-of-root, so we pass the
            // root's traceId to startChildSpan and the TUI can
            // render a tree with `query` at the top and `tool.*`
            // nested underneath.
            String queryTraceId = target.traces().startSpan("query", Map.of(
                    "runId", runId,
                    "promptLen", prompt.length()));
            // each tool_use_start opens a child span that
            // is closed by the matching tool_result. We key the
            // open-span map by the toolUse id so the result event
            // can find its span even if events arrive out of order.
            java.util.Map<String, String> openToolSpans = new ConcurrentHashMap<>();
            // track the actual last stop reason so the SESSION_IDLE
            // event we fire below carries the real value (e.g. "end_turn"
            // / "loop_detected" / "max_iterations") instead of the
            // hard-coded "end_turn" we used to ship. The boulder hook
            // refuses to continue on suspicious stop reasons like
            // "error" / "loop_detected" so this matters.
            final String[] lastStopReason = { "end_turn" };
            try {
                // increment the metrics collector as events flow
                // through. The collector is per-engine; the TUI
                // surfaces its snapshot via getMetrics.
                target.metrics().incTurnStarted();
                target.query(prompt).forEach(ev -> {
                    // count tool calls + errors as they happen.
                    if (ev instanceof org.aethercode.core.stream.StreamEvent.ToolUseStart tu) {
                        target.metrics().incToolCall();
                        // open a child tool span; close on
                        // the matching tool_result event. R79: the
                        // parentSpanId links the tool back to the
                        // root query so the TUI can render a tree.
                        // also stash a compact summary of
                        // the tool input so the desktop trace panel
                        // can show "what did the tool do?" without
                        // having to keep the input around separately.
                        Map<String, Object> toolAttrs = new java.util.LinkedHashMap<>();
                        toolAttrs.put("toolId", tu.id());
                        toolAttrs.put("runId",  runId);
                        if (tu.input() != null && !tu.input().isEmpty()) {
                            // Keep it tiny —large inputs blow up the
                            // trace's serialized form and the panel.
                            // Prefer file_path / command / pattern /
                            // path / query —the common "what did
                            // the user ask the tool to do?" fields.
                            String compact = compactToolInput(tu.name(), tu.input());
                            if (compact != null) toolAttrs.put("inputSummary", compact);
                        }
                        String toolTraceId = target.traces().startChildSpan(
                                queryTraceId,
                                "tool." + tu.name(),
                                toolAttrs);
                        openToolSpans.put(tu.id(), toolTraceId);
                    } else if (ev instanceof org.aethercode.core.stream.StreamEvent.ToolResult tr) {
                        if (tr.isError()) target.metrics().incToolError();
                        // close the matching tool span.
                        String toolTraceId = openToolSpans.remove(tr.id());
                        if (toolTraceId != null) {
                            target.traces().endSpan(toolTraceId, tr.isError() ? "error" : "ok");
                        }
                    } else if (ev instanceof org.aethercode.core.stream.StreamEvent.RunEnd re) {
                        target.metrics().incTurnCompleted();
                        if (re.stopReason() != null && re.stopReason().startsWith("loop")) {
                            target.metrics().incLoopStop();
                        }
                        // capture the actual last stop reason
                        // for the SESSION_IDLE event below.
                        if (re.stopReason() != null && !re.stopReason().isBlank()) {
                            lastStopReason[0] = re.stopReason();
                        }
                        // close the root query span. Any still-open
                        // tool spans (shouldn't happen, but defensive)
                        // are also closed with "error" so the recorder
                        // doesn't leak.
                        target.traces().endSpan(queryTraceId, re.stopReason() != null && re.stopReason().startsWith("error") ? "error" : "ok");
                        for (String leftover : openToolSpans.values()) {
                            target.traces().endSpan(leftover, "error");
                        }
                        openToolSpans.clear();
                    }
                    Map<String, Object> evWrap = new LinkedHashMap<>();
                    evWrap.put("runId", runId);
                    evWrap.put("event", eventToMap(ev));
                    notifier.accept(new JsonRpcNotification(
                            org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                            NOTIFY_STREAM_EVENT, evWrap));
                });
                // fire the SESSION_IDLE event so TodoContinuationHook
                // (and any future "boulder" hooks) get a chance to
                // auto-continue the session. The forEach above is a
                // terminal stream consumer —once it returns the run
                // is fully drained and the session is genuinely idle.
                // We carry the last stop reason + the current todo
                // list snapshot in the event payload so listeners
                // don't need to re-query AppState (the snapshot is
                // a list copy and safe to read).
                try {
                    org.aethercode.core.app.AppState.SessionIdleEvent idleEv =
                            new org.aethercode.core.app.AppState.SessionIdleEvent(
                                    runId, lastStopReason[0], target.appState().todoList());
                    target.appState().fireSessionIdle(idleEv);
                } catch (Exception idleEx) {
                    LOG.warn("session-idle fan-out failed for run {}: {}", runId, idleEx.getMessage());
                }
            } catch (Throwable th) {
                LOG.error("query {} failed: {}", runId, th.getMessage(), th);
                // surface backpressure as a structured
                // notification so the desktop can render a
                // "backpressure" pill with a "retry" affordance
                // affordance. The general catch (Throwable) also
                // handles it; the dedicated branch keeps the
                // JSON shape stable for the UI.
                //
                // also emit a synthetic RunEnd
                // stream_event so the renderer's
                // isStreaming flips back to false. Without
                // it, an exception inside target.query()
                // (e.g. the chat client hits a 4xx that
                // aborts the stream mid-flight) leaves
                // isStreaming=true forever —the input
                // box stays disabled and the user reads
                // it as "the task died silently". The
                // synthetic event carries stopReason
                // "error: <message>" so the renderer's
                // run_end handler can surface a system
                // line; the matching NOTIFY_LOG is still
                // sent for the chat-history record.
                if (th instanceof org.aethercode.sdk.BackpressureException bp) {
                    Map<String, Object> bpWrap = new LinkedHashMap<>();
                    bpWrap.put("runId", runId);
                    bpWrap.put("kind", "backpressure");
                    bpWrap.put("level", "warn");
                    bpWrap.put("error", bp.getMessage());
                    bpWrap.put("engineStats", bp.stats() == null ? Map.of() : bp.stats().toMap());
                    notifier.accept(new JsonRpcNotification(
                            org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                            NOTIFY_LOG, bpWrap));
                } else {
                    Map<String, Object> errWrap = new LinkedHashMap<>();
                    errWrap.put("runId", runId);
                    errWrap.put("level", "error");
                    errWrap.put("error", th.getMessage() != null ? th.getMessage() : th.getClass().getName());
                    notifier.accept(new JsonRpcNotification(
                            org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                            NOTIFY_LOG, errWrap));
                }
                // synthetic run_end so the renderer
                // un-sticks its input box. The shape mirrors
                // eventToMap(StreamEvent.RunEnd) plus an
                // `error` field the renderer can surface.
                Map<String, Object> endWrap = new LinkedHashMap<>();
                endWrap.put("runId", runId);
                Map<String, Object> endEvent = new LinkedHashMap<>();
                endEvent.put("type", "run_end");
                endEvent.put("stopReason", th.getMessage() != null && !th.getMessage().isBlank()
                        ? "error: " + th.getMessage()
                        : "error");
                endEvent.put("error", th.getMessage() != null ? th.getMessage() : th.getClass().getName());
                endEvent.put("isError", true);
                endWrap.put("event", endEvent);
                notifier.accept(new JsonRpcNotification(
                        org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                        NOTIFY_STREAM_EVENT, endWrap));
                // close the root span and any still-open tool
                // spans with an error status. The recorder is
                // best-effort; we never propagate from here.
                try { target.traces().endSpan(queryTraceId, "error"); } catch (Exception ignored) {}
                for (String leftover : openToolSpans.values()) {
                    try { target.traces().endSpan(leftover, "error"); } catch (Exception ignored) {}
                }
                openToolSpans.clear();
            } finally {
                inFlight.remove(runId);
                // release the session-level run
                // lock so the user's next query (or the
                // next auto-continue) can proceed. We
                // compare against `runId` so a stale
                // holder doesn't free a newer owner's
                // lock.
                if (_targetSid != null) {
                    sessionRunLock.remove(_targetSid, runId);
                }
                done.complete(null);
            }
        }, "aethercode-query-" + runId);
        t.setDaemon(true);
        t.start();

        // Return immediately so the caller can subscribe to events
        // without blocking. A real synchronous mode would await
        // `done` here, but the TUI prefers streaming.
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", runId);
        r.put("accepted", true);
        return r;
    }

    // ------------------------------------------------------------------
    // Retry a previously-failed sub-task.
    //
    // The desktop SubTaskCard "Retry" button calls this with the
    // sub-task's goal text. We synthesize a new user prompt that
    // asks the model to retry the sub-task and re-issue it through
    // the normal query() path. The full transcript is preserved, so
    // the model has all prior context (failed tool calls, errors,
    // partial progress) when it picks the work back up.
    //
    // Shape:
    //   in:  { "goal": "...", "hint": "..." (optional) }
    //   out: { "runId": "run-N", "accepted": true }
    //
    // Trade-off: this is the simplest possible retry —same prompt
    // format the user would have typed. A future R90+ could
    // inspect the sub-task's prior failed tool calls and craft a
    // smarter prompt; for R89 the model is smart enough on its own.
    // ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Object retrySubTask(Object params) {
        Map<String, Object> p = asMap(params);
        String goal = stringOrThrow(p, "goal");
        String hint = String.valueOf(p.getOrDefault("hint", ""));

        // Build a retry prompt. The bracketed prefix is what the
        // model sees in the transcript and what the desktop can
        // grep for in messages[] to group retry attempts in the UI.
        // We strip newlines from the goal so the prompt stays
        // single-line in the message list.
        String oneLineGoal = goal.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLineGoal.length() > 200) {
            oneLineGoal = oneLineGoal.substring(0, 197) + "...";
        }
        StringBuilder sb = new StringBuilder("[R89 闁插秷鐦€涙劒鎹㈤崝顡?");
        sb.append(oneLineGoal);
        if (hint != null && !hint.isBlank()) {
            sb.append("\n\n閹绘劗銇? ").append(hint.trim());
        }

        // Delegate to query() so the runId / metrics / trace / WS
        // notification pipeline is reused. A direct call here
        // would duplicate ~50 lines of stream-event handling.
        return query(Map.of("prompt", sb.toString()));
    }

    // ------------------------------------------------------------------
    // Install a per-(tool, target) permission policy override
    // for the current session. Mirrors the desktop "Always for this project"
    // <tool>" affordance exposed by the R86 PermissionList.
    //
    // Shape:
    //   in:  { "tool": "bash", "target": "npm test" (optional),
    //          "decision": "allow" | "deny",
    //          "scope":    "session" (only session is supported in R90) }
    //   out: { "ok": true, "tool": "...", "target": "...",
    //          "decision": "..." }
    //
    // The override is appended to the ProjectPermissionPolicy's
    // allow/deny rules. We rebuild the policy in place —the
    // existing rules are preserved (additive).
    // ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Object permissionPolicyOverride(Object params) {
        Map<String, Object> p = asMap(params);
        String tool      = stringOrThrow(p, "tool");
        // the desktop's `permissionPolicyOverride`
        // wrapper sets `target: opts?.target ?? null` when
        // no target was supplied. `String.valueOf(null)`
        // yields the literal string "null" — which then
        // turns into a non-blank `target`, gets regex-quoted
        // as `\Qnull\E`, and the rule never matches anything
        // (the bash prompt is an empty string, the file path
        // is a real path, neither equals "null"). The user
        // reported "I clicked 'Always for this project' on bash and it
        // STILL prompts on the next bash call." The fix is
        // two-sided: also handle null in the Java side, so
        // the rule is correctly treated as "matches all
        // invocations of this tool".
        Object targetRaw = p.get("target");
        String target = (targetRaw == null || targetRaw instanceof String s && s.isBlank()) ? "" : String.valueOf(targetRaw);
        // Defensive: the literal string "null" should be
        // treated as "no target" too, in case some legacy
        // code path produced it via JSON.stringify(null).
        if ("null".equals(target)) target = "";
        String decision  = stringOrThrow(p, "decision");
        String scope     = String.valueOf(p.getOrDefault("scope", "session"));

        if (!"allow".equals(decision) && !"deny".equals(decision)) {
            return Map.of("ok", false, "reason", "decision must be 'allow' or 'deny'");
        }
        // support session (volatile), project (per-project
        // file under <cwd>/.aethercode/), and user (cross-project,
        // under the user's home directory).
        if (!"session".equals(scope) && !"project".equals(scope) && !"user".equals(scope)) {
            return Map.of("ok", false, "reason",
                    "scope must be 'session', 'project', or 'user'");
        }

        // Look up the live policy. If it's not a ProjectPermissionPolicy
        // (e.g. a test stub), we can't mutate it —fail loudly so the
        // desktop surfaces the error rather than silently dropping the
        // override.
        var policy = engine.policy();
        if (!(policy instanceof org.aethercode.permission.ProjectPermissionPolicy ppp)) {
            return Map.of("ok", false, "reason",
                    "policy is " + policy.getClass().getSimpleName() + ", not ProjectPermissionPolicy");
        }

        // Build a Rule. For bash we use a regex-escaped substring
        // match on the command; for file_* / web_* we use the
        // file_path / url. The matcher is a regex —we escape any
        // regex metacharacters in the target so a user input like
        // "rm -rf" doesn't accidentally become a regex.
        String matcher = target == null || target.isBlank()
                ? null  // matches all invocations of this tool
                : java.util.regex.Pattern.quote(target);

        org.aethercode.permission.Rule rule = new org.aethercode.permission.Rule(
                tool, matcher, "R86 TUI override (" + scope + ")");

        // for project / user scopes, PERSIST the rule to a
        // file on disk so the next daemon restart picks it up
        // automatically. The file is loaded at engine startup
        // (see AetherCodeEngine.init) so the rules are merged
        // with the static settings before the first query.
        if ("project".equals(scope) || "user".equals(scope)) {
            try {
                java.nio.file.Path file = persistedRulesFile(scope);
                java.util.List<org.aethercode.permission.Rule> existing =
                        loadPersistedRules(file);
                existing.add(rule);
                savePersistedRules(file, existing);
                LOG.info("R86 {} rule persisted to {}", scope, file);
            } catch (Exception e) {
                LOG.warn("R86 failed to persist {} rule for {} {}: {}",
                        scope, tool, target, e.toString());
                return Map.of("ok", false, "reason",
                        "failed to persist rule: " + e.getMessage());
            }
        }

        // Mutate the underlying SettingsPermissions in place. We
        // need to read the existing lists, append, and replace.
        // ProjectPermissionPolicy doesn't expose a setter for the
        // rules; we have to rebuild it. Future: add a public
        // appendRule() method on ProjectPermissionPolicy.
        var existing = readRulesFrom(ppp);
        var updated = new org.aethercode.permission.SettingsPermissions();
        updated.allow = existing.allow;
        updated.deny  = existing.deny;
        updated.ask   = existing.ask;
        if ("allow".equals(decision)) {
            updated.allow = new java.util.ArrayList<>(existing.allow);
            updated.allow.add(rule);
        } else {
            updated.deny = new java.util.ArrayList<>(existing.deny);
            updated.deny.add(rule);
        }
        var newPolicy = new org.aethercode.permission.ProjectPermissionPolicy(
                updated,
                ((org.aethercode.permission.ProjectPermissionPolicy) policy).mode(),
                ppp.prompter());
        engine.swapPolicy(newPolicy);
        LOG.info("R86 policy override installed: tool={} target={} decision={} scope={}",
                tool, target, decision, scope);
        return Map.of("ok", true, "tool", tool, "target", target,
                "decision", decision, "scope", scope);
    }

    /**
     * where to persist a project- or user-scope rule.
     *   - project: {@code <cwd>/.aethercode/permissions.json}
     *   - user:    {@code ~/.aethercode/permissions.json}
     *
     * <p>The user-scope file is shared across all projects for the
     * current OS user —it's the "always allow this for me, on
     * this machine" choice. The project-scope file is checked
     * in to the repo (or .gitignored, by user preference) and
     * applies only to that project.
     */
    private static java.nio.file.Path persistedRulesFile(String scope) {
        if ("project".equals(scope)) {
            java.nio.file.Path cwd = java.nio.file.Paths.get(System.getProperty("user.dir"));
            return cwd.resolve(".aethercode").resolve("permissions.json");
        }
        // user
        String home = System.getProperty("user.home");
        return java.nio.file.Paths.get(home, ".aethercode", "permissions.json");
    }

    /**
     * load existing rules from a persisted file. Returns an
     * empty list if the file doesn't exist or is unreadable —     * permissions.json is best-effort, not load-bearing for
     * the rest of the engine.
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<org.aethercode.permission.Rule> loadPersistedRules(java.nio.file.Path file) {
        if (!java.nio.file.Files.exists(file)) return new java.util.ArrayList<>();
        try {
            String body = java.nio.file.Files.readString(file);
            java.util.Map<String, Object> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, java.util.Map.class);
            Object arr = raw.get("rules");
            if (!(arr instanceof java.util.List<?> list)) return new java.util.ArrayList<>();
            java.util.List<org.aethercode.permission.Rule> out = new java.util.ArrayList<>();
            for (Object e : list) {
                if (e instanceof java.util.Map) {
                    out.add(org.aethercode.permission.Rule.fromMap((java.util.Map<String, Object>) e));
                }
            }
            return out;
        } catch (Exception ex) {
            LOG.warn("R86 failed to load persisted rules from {}: {}", file, ex.toString());
            return new java.util.ArrayList<>();
        }
    }

    /**
     * save the rule list back to a persisted file. We
     * write the list under a top-level "rules" key so future
     * additions (e.g. expiry timestamps, comments) can sit
     * alongside without breaking older readers. The file is
     * created if missing; the parent directory is created
     * too (idempotent).
     */
    private static void savePersistedRules(
            java.nio.file.Path file,
            java.util.List<org.aethercode.permission.Rule> rules) throws java.io.IOException {
        java.nio.file.Files.createDirectories(file.getParent());
        java.util.List<java.util.Map<String, Object>> arr = new java.util.ArrayList<>();
        for (org.aethercode.permission.Rule r : rules) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("tool", r.tool());
            entry.put("prompt", r.promptMatcher());
            entry.put("reason", r.reason());
            arr.add(entry);
        }
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("rules", arr);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), doc);
    }

    /** pull the live allow/deny/ask lists out of a
     *  ProjectPermissionPolicy. We use reflection on the private
     *  `rules` field rather than adding a public accessor, because
     *  the policy is constructed once and read-only by design. This
     *  is the only place we touch its internals. */
    @SuppressWarnings("unchecked")
    private static org.aethercode.permission.SettingsPermissions readRulesFrom(
            org.aethercode.permission.ProjectPermissionPolicy ppp) {
        try {
            var f = org.aethercode.permission.ProjectPermissionPolicy.class
                    .getDeclaredField("rules");
            f.setAccessible(true);
            return (org.aethercode.permission.SettingsPermissions) f.get(ppp);
        } catch (ReflectiveOperationException e) {
            // Should never happen —the field is final and present.
            // If it does, we have to fail loudly.
            throw new RuntimeException("R90: cannot read ProjectPermissionPolicy.rules", e);
        }
    }

    // ------------------------------------------------------------------
    // Loop guard escape hatch.
    //
    // The desktop LoopGuardBanner shows a soft warning (kind
    // "loop-warn-1" or "loop-warn-2") and offers the user a
    // "Continue" button. Clicking it calls this RPC to reset the
    // detector's tier back to 0, so the same pattern that just
    // fired can fire again on the next batch without immediately
    // escalating to "loop_detected" (hard stop). This is the
    // "yes, this is intentional -- keep going" affordance the
    // legacy hard-stop lacked.
    //
    // Shape:
    //   in:  { "kind": "all" | "long_output" | "same_fingerprint"
    //          | "same_error" | "user_interrupt" (optional) }
    //   out: { "ok": true, "tier": 0, "kind": "..." }
    //
    // The `kind` param is informational only —the current
    // detector has one tier counter, not one per-kind. A future
    // R102+ could maintain per-kind tier state and let the user
    // ack only one of them; for R101 we ack all. We accept the
    // param so the desktop's call site can be forward-compatible.
    //
    // No-op (returns ok=true, tier=0) when no query is in
    // flight or the detector was disabled. The desktop's
    // LoopGuardBanner clears itself either way.
    // ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Object loopAck(Object params) {
        String kind = "all";
        if (params instanceof Map) {
            Object k = ((Map<String, Object>) params).get("kind");
            if (k instanceof String s && !s.isBlank()) kind = s;
        }
        org.aethercode.core.engine.ProgressLoopDetector d =
                engine.currentLoopDetector();
        if (d == null) {
            // No active query, or detector was disabled. Treat as
            // a no-op success so the desktop's banner can still
            // self-dismiss.
            LOG.debug("loopAck: no active detector (kind={})", kind);
            return Map.of("ok", true, "tier", 0, "kind", kind,
                    "reason", "no active query");
        }
        int before = d.currentTier();
        d.acknowledge();
        LOG.info("loopAck: tier {} -> 0 (kind={})", before, kind);
        return Map.of("ok", true, "tier", 0, "kind", kind, "wasTier", before);
    }

    // ------------------------------------------------------------------
    // Workflow picker RPCs.
    //
    // The desktop input bar lets the user pick a workflow from
    // `<cwd>/.aethercode/workflows/*.yaml` and the engine emits
    // `workflow_step` side notes as each step runs. R102 ships
    // the file-side plumbing (list / read); the executor lands
    // in R103. For now, `runWorkflow` is a stub that emits a
    // single side note so the desktop can see the wiring work
    // end-to-end; the actual step-walker is the agent that
    // loads the `workflow-system` skill.
    //
    // Shape:
    //   listWorkflows() -> { workflows: [{name, description,
    //                                       inputs[], steps[]}],
    //                          count, dir }
    //   getWorkflow({name}) -> { name, description, inputs[],
    //                             steps[], raw }
    //   runWorkflow({name, inputs}) -> { runId, accepted, name,
    //                                    stepCount }
    // ------------------------------------------------------------------

    /**
     * list files under {@code <cwd>/} for the input
     * bar's {@code @}-mention autocomplete. Returns up to
     * {@code max} entries (default 50) sorted by path.
     * Common noise dirs ({@code node_modules}, {@code .git},
     * {@code target}, {@code build}, {@code .idea},
     * {@code dist}) are skipped to keep the dropdown useful
     * on real projects. The {@code query} field is a
     * case-insensitive substring filter (the user types
     * after {@code @} to narrow).
     *
     * <p>Shape: {@code listCwdFiles({query?, max?}) -> {files,
     * count, dir}}. Each entry is a string with the path
     * relative to cwd, with forward slashes (Windows
     * file-system paths are normalised to forward slashes
     * for the JSON payload so the model sees a stable
     * representation).
     */
    @SuppressWarnings("unchecked")
    public Object listCwdFiles(Object params) {
        String query = "";
        int max = 50;
        if (params instanceof Map) {
            Object q = ((Map<String, Object>) params).get("query");
            if (q instanceof String s) query = s;
            Object m = ((Map<String, Object>) params).get("max");
            if (m instanceof Number n) {
                int v = n.intValue();
                if (v > 0 && v <= 200) max = v;
            }
        }
        java.nio.file.Path cwd = java.nio.file.Paths.get(
                System.getProperty("user.dir"));
        java.util.List<String> out = new java.util.ArrayList<>();
        // Hard cap on depth to keep the walk bounded; 6 levels
        // covers the vast majority of real project layouts
        // (src/main/java/com/foo/Bar.java is 5).
        int maxDepth = 6;
        // Skip these common noise dirs. R104 doesn't take
        // a user-supplied include/exclude list yet —the
        // default is good enough for the desktop picker.
        java.util.Set<String> skip = java.util.Set.of(
                "node_modules", ".git", "target", "build",
                ".idea", "dist", "out", "__pycache__", ".next");
        try (var stream = java.nio.file.Files.walk(cwd, maxDepth)) {
            // Capture-by-value for the lambdas: Java's
            // effectively-final rule means we can't reassign
            // query / max / cwd inside the stream. We don't,
            // but the compiler still complains because the
            // locals are mutables; renaming to final-ish
            // locals here keeps the lambdas happy.
            final String fQuery = query;
            final int fMax = max;
            final java.nio.file.Path fCwd = cwd;
            stream
                .filter((p) -> java.nio.file.Files.isRegularFile(p))
                .filter((p) -> {
                    for (var part : p) {
                        if (skip.contains(part.toString())) return false;
                    }
                    return true;
                })
                .map((p) -> fCwd.relativize(p).toString()
                        .replace(java.io.File.separatorChar, '/'))
                .filter((s) -> fQuery.isEmpty()
                        || s.toLowerCase().contains(fQuery.toLowerCase()))
                .sorted()
                .limit(fMax)
                .forEach(out::add);
        } catch (Exception e) {
            return Map.of("error", "walk failed: " + e.getMessage(),
                    "files", java.util.List.of(), "count", 0, "dir", cwd.toString());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("files", out);
        r.put("count", out.size());
        r.put("dir", cwd.toString());
        return r;
    }

    @SuppressWarnings("unchecked")
    public Object listWorkflows(Object params) {
        java.nio.file.Path cwd = java.nio.file.Paths.get(
                System.getProperty("user.dir"));
        java.util.List<org.aethercode.core.workflow.WorkflowReader.WorkflowDoc> docs =
                org.aethercode.core.workflow.WorkflowReader.list(cwd);
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var d : docs) out.add(d.toMap());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("workflows", out);
        r.put("count", out.size());
        r.put("dir", org.aethercode.core.workflow.WorkflowPaths.workflowDir(cwd).toString());
        return r;
    }

    @SuppressWarnings("unchecked")
    public Object getWorkflow(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        java.nio.file.Path cwd = java.nio.file.Paths.get(
                System.getProperty("user.dir"));
        java.nio.file.Path file = org.aethercode.core.workflow.WorkflowPaths.workflowFile(cwd, name);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return Map.of("ok", false, "reason", "workflow not found: " + name);
        }
        try {
            var doc = org.aethercode.core.workflow.WorkflowReader.read(file);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.putAll(doc.toMap());
            r.put("raw", doc.raw());
            // R234 (daemon parity fix): also surface `content` so
            // desktop clients that follow the writeWorkflow wire
            // shape (which uses `content` for the YAML body) can
            // read it back the same way. Without this alias the
            // editor has to know the legacy `raw` field which
            // is undocumented on the public wire.
            r.put("content", doc.raw());
            return r;
        } catch (Exception e) {
            return Map.of("ok", false, "reason", "read failed: " + e.getMessage());
        }
    }

    /**
     * legacy: kick off a workflow run. Reads the YAML,
     * builds a {@link org.aethercode.core.workflow.WorkflowExecutor},
     * and runs it on a daemon thread so the RPC can return
     * immediately. The executor emits one
     * {@code workflow_step} SideNote per state transition
     * (pending —running —ok / error); the desktop's
     * {@code WorkflowProgressBar} reads these.
     *
     * <p>Shape: {@code runWorkflow({name, inputs}) —{ok, runId,
     * name, stepCount, accepted}}. The actual step outcomes land
     * in the SideNote stream, not in the return value (the
     * desktop's store reads them off the stream).
     *
     * <p>The executor is thread-confined —each {@code runWorkflow}
     * call spawns a fresh daemon thread so concurrent workflows
     * don't share state. The engine's main turn loop is not
     * blocked.
     */
    @SuppressWarnings("unchecked")
    public Object runWorkflow(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        Map<String, Object> inputs = p.get("inputs") instanceof Map
                ? (Map<String, Object>) p.get("inputs")
                : Map.of();
        java.nio.file.Path cwd = java.nio.file.Paths.get(
                System.getProperty("user.dir"));
        java.nio.file.Path file = org.aethercode.core.workflow.WorkflowPaths.workflowFile(cwd, name);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return Map.of("ok", false, "reason", "workflow not found: " + name);
        }
        org.aethercode.core.workflow.WorkflowReader.WorkflowDoc doc;
        try {
            doc = org.aethercode.core.workflow.WorkflowReader.read(file);
        } catch (Exception e) {
            return Map.of("ok", false, "reason", "read failed: " + e.getMessage());
        }
        String runId = "wf-" + runCounter.incrementAndGet();
        // Build a stream-event consumer that re-wraps the
        // SideNote in the same envelope the engine uses for
        // per-turn events. The desktop already knows how to
        // route these to runningWorkflow.
        java.util.function.Consumer<org.aethercode.core.stream.StreamEvent> sink = (ev) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "side_note");
            payload.put("event", eventToMap(ev));
            payload.put("runId", runId);
            try {
                notifier.accept(new JsonRpcNotification(
                        org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
                        NOTIFY_STREAM_EVENT, payload));
            } catch (Exception ignored) {}
        };
        // Spawn the executor on a daemon thread. The desktop
        // gets the run id back immediately; the progress
        // bar updates as SideNotes arrive.
        // wire the SkillInvoker so `skill` and `agent`
        // step types run on a child session via
        // engine.queryInChildSession. Without an engine
        // reference (the executor is in aethercode-core and
        // can't import the SDK), the steps fall back to the
        // R103 stub path.
        //
        // use the 4-arg overload that accepts a
        // sink, so the child session's StreamEvents fan out
        // through the workflow's existing `sink` to every
        // connected client. The WorkflowExecutor wraps
        // each forwarded event in a `child_session_event`
        // SideNote; the renderer's existing stream_event
        // handler routes these to the workflow progress
        // bar.
        //
        // use the 5-arg overload that also
        // accepts a modelOverride. The override is
        // the agent's frontmatter {@code model:}
        // field (e.g. {@code "glm/glm-4-flash"})
        // which the executor looked up via the
        // agentModelLookup callback we pass to the
        // constructor below. Here we resolve the
        // string to a {@code ChatClient} via the
        // {@code ProviderRegistry} (the registry
        // is on the methods layer, not the
        // executor). The new ChatClient is
        // passed to the 6-arg
        // {@code queryInChildSession} so the
        // child session uses the per-agent
        // model. When the override is null/blank
        // we fall back to the engine's default
        // client (the existing behaviour).
        org.aethercode.core.workflow.WorkflowExecutor.SkillInvoker invoker =
                (kind, skillName, prompt, modelOverride, eventSink) -> {
                    org.aethercode.core.llm.ChatClient override = null;
                    if (modelOverride != null && !modelOverride.isBlank()
                            && providerRegistry != null) {
                        try {
                            override = resolveChatClient(modelOverride);
                        } catch (Exception e) {
                            LOG.warn("resolveChatClient({}) failed, falling back to engine default: {}",
                                    modelOverride, e.getMessage());
                        }
                    }
                    return engine.queryInChildSession(
                            engine.appState().sessionId(), kind, skillName, prompt, eventSink, override);
                };
        // wire the agentModelLookup callback so
        // the executor can read the agent's frontmatter
        // {@code model:} field for {@code kind: agent}
        // steps. The callback returns the model string
        // (e.g. "glm/glm-4-flash") or null when the
        // agent has no model field. The string is
        // forwarded to the SkillInvoker (see above) for
        // resolution to a ChatClient. When the agent
        // registry is not wired (no --agents-dir at
        // startup) the callback returns null and every
        // agent step uses the engine's default model.
        java.util.function.Function<String, String> agentModelLookup =
                (agentName) -> engine.getAgentMeta(agentName)
                        .map(m -> m.model())
                        .filter(s -> !s.isBlank())
                        .orElse(null);
        org.aethercode.core.workflow.WorkflowExecutor exec =
                new org.aethercode.core.workflow.WorkflowExecutor(
                        doc, inputs, runId, sink, invoker, agentModelLookup);
        Thread t = new Thread(() -> {
            try {
                String status = exec.run();
                // Emit a final workflow-done SideNote so the
                // desktop can show "✓ all done" / "✗ failed"
                // in the progress bar without watching the
                // last step's status.
                try {
                    sink.accept(new org.aethercode.core.stream.StreamEvent.SideNote(
                            "workflow_done", "workflow " + name + " " + status
                                    + " (" + exec.results().size() + " steps)"));
                } catch (Exception ignored) {}
            } catch (Throwable th) {
                LOG.error("workflow {} failed: {}", name, th.getMessage(), th);
                try {
                    sink.accept(new org.aethercode.core.stream.StreamEvent.SideNote(
                            "workflow_error", th.getMessage() == null ? String.valueOf(th) : th.getMessage()));
                } catch (Exception ignored) {}
            }
        }, "aethercode-workflow-" + runId);
        t.setDaemon(true);
        t.start();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("runId", runId);
        r.put("name", doc.name());
        r.put("stepCount", doc.steps().size());
        r.put("accepted", true);
        r.put("note", "R103 executor running on background thread");
        return r;
    }

    /** write (or overwrite) a workflow YAML. The
     *  path-scope check in {@code WorkflowPaths.workflowFile}
     *  already prevents the file from escaping the workflow
     *  dir; we add a "must end in .yaml / .yml" check here for
     *  defence in depth. The caller's `name` is the filename
     *  without extension; the daemon adds the extension
     *  (matching the read side). If the file already exists,
     *  the daemon still writes —`/workflow modify` needs that
     *  to be idempotent. Returns the absolute path on success
     *  so the desktop can refresh the list and pick the new
     *  entry immediately.
     *
     *  <p>Shape: {@code writeWorkflow({name, content}) -> {ok, path}}.
     *  Refuses empty content (legacy defensive —the user
     *  could otherwise wipe a workflow by clicking save on a
     *  blank edit buffer). */
    @SuppressWarnings("unchecked")
    public Object writeWorkflow(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        Object contentObj = p.get("content");
        if (!(contentObj instanceof String content)) {
            return Map.of("ok", false, "reason", "content must be a string");
        }
        if (content.isBlank()) {
            return Map.of("ok", false, "reason", "content is empty");
        }
        java.nio.file.Path cwd = java.nio.file.Paths.get(
                System.getProperty("user.dir"));
        java.nio.file.Path file;
        try {
            file = org.aethercode.core.workflow.WorkflowPaths.workflowFile(cwd, name);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "reason", "invalid name: " + e.getMessage());
        }
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, content);
        } catch (Exception e) {
            return Map.of("ok", false, "reason", "write failed: " + e.getMessage());
        }
        return Map.of("ok", true, "path", file.toString(), "name",
                org.aethercode.core.workflow.WorkflowReader.stripExt(
                        file.getFileName().toString()));
    }

    /** delete a workflow YAML. Idempotent —deleting a
     *  missing file returns {@code ok=true, removed=false} so
     *  the desktop can safely retry without surfacing an
     *  error. The path-scope check prevents accidental
     *  deletes outside the workflow dir. */
    @SuppressWarnings("unchecked")
    public Object deleteWorkflow(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        java.nio.file.Path cwd = java.nio.file.Paths.get(
                System.getProperty("user.dir"));
        java.nio.file.Path file;
        try {
            file = org.aethercode.core.workflow.WorkflowPaths.workflowFile(cwd, name);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "reason", "invalid name: " + e.getMessage());
        }
        boolean removed = false;
        try {
            removed = java.nio.file.Files.deleteIfExists(file);
        } catch (Exception e) {
            return Map.of("ok", false, "reason", "delete failed: " + e.getMessage());
        }
        return Map.of("ok", true, "removed", removed, "name",
                org.aethercode.core.workflow.WorkflowReader.stripExt(
                        file.getFileName().toString()));
    }

    // ------------------------------------------------------------------
    // Memory browser/editor RPCs.
    //
    // The engine reads memory files (USER / PROJECT / LOCAL scopes)
    // via org.aethercode.memory.MemoryPaths and the model uses
    // them as soft context. previously, the user had no UI to
    // inspect or edit them; they could only ask the model to
    // read / write via the file_read / file_write tools. These
    // four RPCs give the desktop a direct file-system surface,
    // gated by path-scope checks so a misbehaving client can't
    // read or write outside the engine's memory dirs.
    //
    // R127 renamed the R92 list/delete RPCs to
    // {@code listMemoryFiles} / {@code deleteMemoryFile} to
    // make room for the new entry-based listMemory /
    // deleteMemory (the 3-layer memory surface). The
    // underlying behaviour is unchanged.
    //
    // Shape:
    //   listMemoryFiles({ scope, agentType })
    //     -> [{ name, path, size, mtime, isEntry, scope }]
    //   readMemoryFile({ scope, agentType, name })
    //     -> "string content"
    //   writeMemoryFile({ scope, agentType, name, content })
    //     -> { ok, path }
    //   deleteMemoryFile({ scope, agentType, name })
    //     -> { ok }
    //
    // The daemon's cwd is taken from System.getProperty("user.dir")
    // (the JVM's working dir, which the Tauri Rust process sets
    // via --cwd when spawning). USER scope uses the engine's
    // memoryBase() which honours AETHERCODE_MEMORY_DIR.
    // ------------------------------------------------------------------

    /** list memory files in a scope. */
    @SuppressWarnings("unchecked")
    public Object listMemoryFiles(Object params) {
        Map<String, Object> p = asMap(params);
        String scope     = stringOrThrow(p, "scope");     // "USER" | "PROJECT" | "LOCAL"
        String agentType = String.valueOf(p.getOrDefault("agentType",
                engine.appState().mainLoopModel() == null ? "default" : engine.appState().mainLoopModel()));
        java.nio.file.Path dir = org.aethercode.memory.MemoryPaths.agentMemoryDir(
                agentType,
                org.aethercode.memory.MemoryScope.valueOf(scope),
                java.nio.file.Paths.get(System.getProperty("user.dir")));
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var stream = java.nio.file.Files.list(dir)) {
                for (var p2 : (Iterable<java.nio.file.Path>) stream::iterator) {
                    if (!java.nio.file.Files.isRegularFile(p2)) continue;
                    String name = p2.getFileName().toString();
                    long size = 0; long mtime = 0;
                    try {
                        var meta = java.nio.file.Files.readAttributes(p2, java.nio.file.attribute.BasicFileAttributes.class);
                        size  = meta.size();
                        mtime = meta.lastModifiedTime().toMillis();
                    } catch (Exception ignored) {}
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name",    name);
                    entry.put("path",    p2.toString());
                    entry.put("size",    size);
                    entry.put("mtime",   mtime);
                    entry.put("isEntry", "MEMORY.md".equals(name));
                    entry.put("scope",   scope);
                    out.add(entry);
                }
            } catch (Exception e) {
                return Map.of("error", "list failed: " + e.getMessage());
            }
        }
        // Sort: MEMORY.md first, then by mtime desc.
        out.sort((a, b) -> {
            boolean ae = (boolean) a.get("isEntry");
            boolean be = (boolean) b.get("isEntry");
            if (ae != be) return ae ? -1 : 1;
            return Long.compare((long) b.get("mtime"), (long) a.get("mtime"));
        });
        return Map.of("files", out, "count", out.size());
    }

    /** read a memory file's content. */
    @SuppressWarnings("unchecked")
    public Object readMemoryFile(Object params) {
        Map<String, Object> p = asMap(params);
        String scope     = stringOrThrow(p, "scope");
        String agentType = String.valueOf(p.getOrDefault("agentType",
                engine.appState().mainLoopModel() == null ? "default" : engine.appState().mainLoopModel()));
        String name      = stringOrThrow(p, "name");
        java.nio.file.Path dir = org.aethercode.memory.MemoryPaths.agentMemoryDir(
                agentType,
                org.aethercode.memory.MemoryScope.valueOf(scope),
                java.nio.file.Paths.get(System.getProperty("user.dir")));
        java.nio.file.Path target = dir.resolve(name).normalize();
        // Path-scope check: refuse to read anything that escapes the
        // resolved memory dir (defence against .. or symlink shenanigans).
        if (!target.startsWith(dir)) {
            return Map.of("error", "path outside memory dir: " + target);
        }
        try {
            return Map.of("content", java.nio.file.Files.readString(target));
        } catch (Exception e) {
            return Map.of("error", "read failed: " + e.getMessage());
        }
    }

    /** write a memory file's content. Creates the parent dir
     *  if it doesn't exist (first write to an empty scope). */
    @SuppressWarnings("unchecked")
    public Object writeMemoryFile(Object params) {
        Map<String, Object> p = asMap(params);
        String scope     = stringOrThrow(p, "scope");
        String agentType = String.valueOf(p.getOrDefault("agentType",
                engine.appState().mainLoopModel() == null ? "default" : engine.appState().mainLoopModel()));
        String name      = stringOrThrow(p, "name");
        String content   = String.valueOf(p.getOrDefault("content", ""));
        java.nio.file.Path dir = org.aethercode.memory.MemoryPaths.agentMemoryDir(
                agentType,
                org.aethercode.memory.MemoryScope.valueOf(scope),
                java.nio.file.Paths.get(System.getProperty("user.dir")));
        java.nio.file.Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir)) {
            return Map.of("ok", false, "error", "path outside memory dir: " + target);
        }
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(target, content);
            return Map.of("ok", true, "path", target.toString());
        } catch (Exception e) {
            return Map.of("ok", false, "error", "write failed: " + e.getMessage());
        }
    }

    /** delete a memory file. Idempotent —non-existent files
     *  return ok=true. */
    @SuppressWarnings("unchecked")
    public Object deleteMemoryFile(Object params) {
        Map<String, Object> p = asMap(params);
        String scope     = stringOrThrow(p, "scope");
        String agentType = String.valueOf(p.getOrDefault("agentType",
                engine.appState().mainLoopModel() == null ? "default" : engine.appState().mainLoopModel()));
        String name      = stringOrThrow(p, "name");
        java.nio.file.Path dir = org.aethercode.memory.MemoryPaths.agentMemoryDir(
                agentType,
                org.aethercode.memory.MemoryScope.valueOf(scope),
                java.nio.file.Paths.get(System.getProperty("user.dir")));
        java.nio.file.Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir)) {
            return Map.of("ok", false, "error", "path outside memory dir: " + target);
        }
        try {
            java.nio.file.Files.deleteIfExists(target);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "delete failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Object cancel(Object params) {
        Map<String, Object> p = asMap(params);
        String runId = stringOrThrow(p, "runId");
        // the sessionId hint is accepted for
        // caller clarity (the renderer / TUI may pass
        // it for consistency with the query call) but
        // is NOT used to route —the runId is the
        // single source of truth because it's
        // generated by query() and added to the
        // global inFlight map. The hint is logged at
        // DEBUG so an operator can correlate "I sent
        // cancel to session X" with the originating
        // session.
        String sessionIdHint = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        if (sessionIdHint != null) {
            LOG.debug("对应历史 round: cancel({}) received with sessionId hint={}", runId, sessionIdHint);
        }
        CompletableFuture<Void> done = inFlight.get(runId);
        if (done == null) {
            return Map.of("cancelled", false, "reason", "no such runId");
        }
        done.cancel(true);
        return Map.of("cancelled", true, "runId", runId);
    }

    /** cancel a running background subagent by jobId.
     *  Thin wrapper over {@link
     *  org.aethercode.tools.task.SubagentRegistry#cancel(String)}
     *  —the registry's cancel() interrupts the worker
     *  thread and flips the status, which fires a
     *  {@code subagent_event} notification the renderers
     *  consume. The response shape mirrors the registry's
     *  {@code CancelResult} so the TUI/desktop can show a
     *  different toast for "cancelled live" vs "was already
     *  finished".
     *
     *  <p>Always returns {@code ok: true} unless the params
     *  are malformed (missing or non-string jobId). The
     *  {@code cancelled} / {@code alreadyFinished} booleans
     *  carry the actual outcome. */
    public Object subagentCancel(Object params) {
        Map<String, Object> p = asMap(params);
        String jobId = stringOrThrow(p, "jobId");
        // optional `reason` field. Forwarded to the
        // registry so the cancel audit log + the
        // subagent_event notification both carry the
        // user-supplied reason. Treated as empty when
        // absent (matches the legacy-D wire shape).
        String reason = "";
        Object reasonObj = p.get("reason");
        if (reasonObj instanceof String s) {
            reason = s;
        }
        org.aethercode.tools.task.SubagentRegistry.CancelResult r =
                org.aethercode.tools.task.SubagentRegistry.instance().cancel(jobId, reason);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("jobId", jobId);
        out.put("cancelled", r.cancelled());
        out.put("alreadyFinished", r.alreadyFinished());
        return out;
    }

    public Object listSessions(Object params) {
        // when a SessionStore is wired (the default for the
        // daemon and the CLI), list the persisted sessions. Each
        // entry maps to the wire format the desktop expects:
        //   { id, name?, cwd, lastUsedAt (ms epoch), messageCount, createdAt? }
        // `name` is left undefined so the desktop falls back to
        // "Session <id-tail>" (matches R88's rendering). Falls
        // back to the legacy empty list when no store is wired.
        // parse the params for `limit` (cap on rows) and
        // `withPreview` (whether to extract a preview text from
        // the first user message — a per-file IO cost that the
        // TUI's session picker wants to render session titles).
        // also surface the session's bound cwd (read from
        // the memory store, which tracks session→cwd via
        // switchProject + createSession) so the LeftPanel can
        // group sessions under their project folder.
        Map<String, Object> p = asMap(params);
        int limit = 50; // sane default
        Object l = p.get("limit");
        if (l instanceof Number n && n.intValue() > 0 && n.intValue() <= 500) {
            limit = n.intValue();
        }
        // R266-desktop-session-title (2026-09-13): withPreview
        // now defaults to TRUE. Prior round the default was
        // false (legacy wire format from R198). The desktop
        // LeftPanel's SessionListRow renders session title as
        //   s.title -> s.preview -> "新会话"
        // and the user reported "task finished, left panel
        // still says '新会话'" — the desktop was calling
        // `listSessions()` without `withPreview` so the preview
        // field came back empty for sessions that had a
        // transcript. Defaulting withPreview to true means the
        // TUI's first paint shows the first user prompt as the
        // title without each call site having to remember the
        // opt-in. Cost is ~one 4 KB JSONL read per row (the
        // SessionStore's `list()` already returned a size
        // summary); for a typical 10-50 sessions this is well
        // under 1 ms on a warm page cache.
        boolean withPreview = !Boolean.FALSE.equals(p.get("withPreview"));
        var store = engine.sessionStore();
        if (store == null) {
            return Map.of("sessions", List.of(), "current", engine.appState().sessionId());
        }
        try {
            List<org.aethercode.core.transcript.SessionStore.SessionInfo> raw = store.list();
            List<Map<String, Object>> out = new ArrayList<>(Math.min(raw.size(), limit));
            int count = 0;
            for (var info : raw) {
                if (count++ >= limit) break;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", info.id());
                m.put("lastUsedAt", info.lastModified());
                m.put("sizeBytes", info.sizeBytes());
                // The file size gives a rough message-count proxy
                // for the LeftPanel's `n msg` label. A more
                // accurate count would require reading the JSONL
                // lines; we keep the cheap proxy for now and let
                // the user click into the session to see the real
                // count.
                m.put("messageCount", Math.max(1, (int) (info.sizeBytes() / 800L)));
                // legacy: pull the session's bound cwd.
                //
                // Two sources, in priority order:
                //
                //   1. The per-session `.cwd` sidecar file
                //      (`<sessions-dir>/<id>.cwd`). Written
                //      by `AetherCodeEngine.createSession(cwd)`
                //      and `AetherCodeEngine.setCwd(cwd)` —
                //      so it's always present for any session
                //      that ever had a cwd bound. This is the
                //      PRIMARY source because the sidecar lives
                //      in the SAME directory as the session's
                //      JSONL file, so it can't disagree with
                //      where the session was actually written.
                //
                //   2. The memory store's session_info table.
                //      R201 added the write side (createSession
                //      calls upsertSession) but the table only
                //      has rows for sessions created AFTER the
                //      upgrade — every legacy session in the
                //      store is missing. We keep the memory
                //      store as a fallback for completeness
                //      (e.g. a session imported from another
                //      daemon that wrote the row but not the
                //      sidecar) but we never trust it as the
                //      sole source.
                //
                // Without the sidecar path, the desktop's
                // LeftPanel would show "0 sessions" because
                // every legacy session has no memory-store
                // row, and ProjectGroupList groups nothing.
                String cwd = null;
                java.nio.file.Path sidecar = info.file() != null
                        ? info.file().getParent().resolve(info.id() + ".cwd")
                        : null;
                if (sidecar != null && java.nio.file.Files.exists(sidecar)) {
                    try {
                        String content = java.nio.file.Files.readString(sidecar).trim();
                        if (!content.isEmpty()) cwd = content;
                    } catch (Exception ignore) { /* fall through to memory store */ }
                }
                if (cwd == null && memoryStore != null) {
                    org.aethercode.memory.SessionMemoryStore.SessionInfo ms =
                            memoryStore.sessionStore().getSession(info.id());
                    if (ms != null && ms.cwd() != null && !ms.cwd().isBlank()) {
                        cwd = ms.cwd();
                    }
                }
                if (cwd != null) {
                    m.put("cwd", cwd);
                }
                // optional preview extraction. The TUI
                // session picker uses this to render a one-line
                // title for each row ("Summarize README.md" /
                // "Fix Cwe252 unchecked return" / ...). The
                // extraction is bounded: we read at most the
                // first 4 KB of the file and stop as soon as we
                // see the first user-role JSONL line. A session
                // that hasn't been loaded yet (empty file)
                // returns an empty preview, which the TUI
                // renders as "Untitled session".
                if (withPreview) {
                    // try the live transcript first; if
                    // it's empty (the session was just created
                    // via lazy-create and the user message
                    // hasn't been written to disk yet), fall
                    // back to session_info.first_prompt that
                    // createSession captured at the same time
                    // the user submitted the prompt. This way
                    // a brand-new session shows up with a real
                    // title in the LeftPanel — not "New Session" /
                    // "Untitled session" / `Session xxx`.
                    String preview = extractSessionPreview(info);
                    if ((preview == null || preview.isBlank()) && memoryStore != null) {
                        try {
                            var msRow = memoryStore.sessionStore().loadSession(info.id());
                            if (msRow.isPresent() && msRow.get().firstPrompt() != null
                                    && !msRow.get().firstPrompt().isBlank()) {
                                preview = msRow.get().firstPrompt();
                            }
                        } catch (Exception msEx) {
                            LOG.debug("R224: loadSession({}) for preview fallback failed: {}",
                                    info.id(), msEx.getMessage());
                        }
                    }
                    m.put("preview", preview == null ? "" : preview);
                    // R270 (2026-09-15): also surface the most
                    // recent agent activity as `lastAgentEvent`
                    // so the desktop SessionListRow can render
                    // a Claude Code / OpenCode style two-line
                    // summary:
                    //   [first user prompt]
                    //   → last tool call or thinking snippet
                    // We deliberately keep this opt-out-able
                    // later — for now it's bundled with
                    // `withPreview` because both share the
                    // same transcript scan and we don't want
                    // to do two passes.
                    try {
                        String lastEvent = extractLastAgentEvent(info);
                        m.put("lastAgentEvent", lastEvent == null ? "" : lastEvent);
                    } catch (Exception lastEx) {
                        // best-effort — never fail listSessions
                        // because the last-event scan errored.
                        LOG.debug("R270: extractLastAgentEvent({}) failed: {}",
                                info.id(), lastEx.getMessage());
                        m.put("lastAgentEvent", "");
                    }
                    // R266-desktop-session-title (2026-09-13):
                    // also surface the same text as `title` so
                    // the desktop's SessionListRow gets a real
                    // title out of the box. R204 wired the row
                    // to fall back from `title` -> `preview` ->
                    // "新会话" but the daemon never actually
                    // populated the `title` field, so every
                    // session rendered as "新会话" until the
                    // user manually renamed it. We treat the
                    // first user prompt as the canonical title
                    // (capped at 200 chars to match the preview
                    // field); the desktop will further truncate
                    // to 60 chars on render. When the user
                    // renames a session via setTitle (TODO
                    // R267), that explicit name wins because the
                    // frontend takes the `title` field verbatim.
                    m.put("title", preview == null ? "" : preview);
                }
                out.add(m);
            }
            return Map.of("sessions", out, "current", engine.appState().sessionId(),
                    "total", raw.size(), "returned", out.size());
        } catch (java.io.IOException e) {
            LOG.warn("listSessions failed: {}", e.getMessage());
            return Map.of("sessions", List.of(), "current", engine.appState().sessionId(),
                    "error", e.getMessage());
        }
    }

    /** extract a one-line preview from a session
     *  transcript. The transcript is JSONL; we read up to
     *  4 KB and stop at the first line whose
     *  {@code "role":"user"} entry has a non-empty
     *  {@code content[]} of {@code TextBlock} items.
     *  Returns the first 200 chars of that text, trimmed,
     *  or {@code ""} if the file is empty / malformed /
     *  has no user message yet. The extraction is
     *  deliberately cheap (no full JSON parse) — we
     *  just look for a text block in the first user
     *  line and string-trim it. A 4 KB cap keeps the
     *  cost bounded even for huge sessions. */
    private static String extractSessionPreview(
            org.aethercode.core.transcript.SessionStore.SessionInfo info) {
        if (info == null || info.file() == null
                || !java.nio.file.Files.exists(info.file())) {
            return "";
        }
        try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(
                info.file(), java.nio.charset.StandardCharsets.UTF_8)) {
            // The transcript lines look like:
            // {"id":"...","role":"user","content":[{"type":"text","text":"hi"}],...}
            // We don't need to parse the full JSON — a cheap
            // regex pulls out the first "text":"..." value
            // from any line that contains role":"user".
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.length() > 4096) {
                    // first 4 KB is enough
                    line = line.substring(0, 4096);
                }
                if (line.contains("\"role\":\"user\"")) {
                    int textIdx = line.indexOf("\"text\"");
                    if (textIdx < 0) return "";
                    int colonIdx = line.indexOf(':', textIdx);
                    if (colonIdx < 0) return "";
                    int quoteIdx = line.indexOf('"', colonIdx + 1);
                    if (quoteIdx < 0) return "";
                    int endQuote = line.indexOf('"', quoteIdx + 1);
                    if (endQuote < 0) return "";
                    String text = line.substring(quoteIdx + 1, endQuote);
                    // Trim and cap to 200 chars for the TUI
                    // picker.
                    text = text.replaceAll("\\s+", " ").trim();
                    if (text.length() > 200) text = text.substring(0, 197) + "…";
                    return text;
                }
            }
        } catch (Exception ignored) {
            // best-effort preview; never fail the list
        }
        return "";
    }

    /** R270 (2026-09-15) — extract the most recent agent
     *  activity from a session transcript. Unlike
     *  {@link #extractSessionPreview} which scans forward to
     *  the FIRST user message, this walks the file backwards
     *  looking for the LAST assistant-role line and renders
     *  it as a one-liner:
     *
     *    tool_use block  → "file_write D:\tmp\abc_1\HeapSortTest.java"
     *    plain text      → "💭 thinking…" (first 80 chars)
     *
     *  Used by the desktop's SessionListRow so each row reads
     *  like a Claude Code / OpenCode summary — first user
     *  prompt up top, what the agent was just doing below.
     *  Transcripts are JSONL; the regex scan is bounded by a
     *  4 KB per-line cap so a giant tool input doesn't slow
     *  the list. Returns {@code ""} when the transcript has
     *  no assistant message yet (brand-new session).
     *
     *  <p>Implementation notes:
     *  <ul>
     *    <li>Iterating from the back is O(n) in the number of
     *        transcript lines, not the file size. Sessions
     *        typically have hundreds of lines max.</li>
     *    <li>We prefer {@code tool_use} over {@code text}
     *        because the user wants to know "what did the
     *        agent DO", not "what did the agent THINK".</li>
     *    <li>For the input summary we look at common key
     *        names first (file_path / path / filePath /
     *        cmd / command / url / pattern) and finally fall
     *        back to the first string field we find.</li>
     *  </ul> */
    private static String extractLastAgentEvent(
            org.aethercode.core.transcript.SessionStore.SessionInfo info) {
        if (info == null || info.file() == null
                || !java.nio.file.Files.exists(info.file())) {
            return "";
        }
        try {
            // Read all lines — transcripts are < 1 MB even
            // for long sessions; the per-line 4 KB cap below
            // keeps the regex scan bounded.
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    info.file(), java.nio.charset.StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (line == null || line.isEmpty()) continue;
                if (line.length() > 4096) line = line.substring(0, 4096);
                if (!line.contains("\"role\":\"assistant\"")) continue;

                // 1) Prefer tool_use blocks — they tell the
                //    user "what the agent just did". A single
                //    assistant message may contain BOTH text
                //    (the thinking) and tool_use (the action);
                //    we want the action.
                String toolLabel = extractFirstToolUseLabel(line);
                if (toolLabel != null && !toolLabel.isEmpty()) {
                    return capForList(toolLabel, 80);
                }

                // 2) Fall back to plain text — the assistant
                //    emitted only a thinking block, no tool.
                String text = extractFirstTextValue(line);
                if (text != null && !text.isEmpty()) {
                    return capForList(text, 80);
                }
                // 3) Empty / malformed assistant line — keep
                //    walking backwards, there might be a
                //    useful earlier message.
            }
        } catch (Exception ignored) {
            // best-effort; an unreadable transcript never
            // fails listSessions.
        }
        return "";
    }

    /** Find the first {@code tool_use} block in a line and
     *  render it as {@code "<tool_name> <first string arg>"}.
     *  Returns {@code null} when no tool_use is present. */
    private static String extractFirstToolUseLabel(String line) {
        // Cheap probe: look for the "type":"tool_use" marker
        // and grab the following "name" + "input" fields. We
        // accept either ordering since wire format sometimes
        // emits name before input, sometimes interleaved.
        int toolUseIdx = line.indexOf("\"type\":\"tool_use\"");
        if (toolUseIdx < 0) return null;

        // Find the "name" string. Tool names like file_read
        // sit adjacent to the tool_use marker in practice,
        // so a forward search from toolUseIdx is enough.
        int nameKey = line.indexOf("\"name\"", toolUseIdx);
        if (nameKey < 0) return null;
        String toolName = readQuotedValue(line, nameKey);
        if (toolName.isEmpty()) return null;

        // Find the first string value inside the SAME
        // tool_use block's "input" object. We bound the
        // search to the next "type":"tool_use" (or end of
        // line) so we don't bleed across blocks.
        int inputKey = line.indexOf("\"input\"", toolUseIdx);
        if (inputKey < 0) {
            return toolName;
        }
        int inputEnd = line.indexOf("\"type\":\"tool_use\"", inputKey + 1);
        if (inputEnd < 0) inputEnd = line.length();
        String inputSlice = line.substring(inputKey, inputEnd);
        String inputSummary = extractFirstStringField(inputSlice);
        return inputSummary.isEmpty() ? toolName : (toolName + " " + inputSummary);
    }

    /** Pull a string field's first value from a JSON object
     *  slice. Prefers well-known short keys (file_path / path
     *  / filePath / command / url / pattern) so the result
     *  is human-readable; falls back to the first string-typed
     *  field we find. */
    private static String extractFirstStringField(String slice) {
        String[] preferred = {"file_path", "path", "filePath",
                "command", "cmd", "url", "pattern", "query",
                "prompt", "name", "target", "destination"};
        for (String key : preferred) {
            int idx = slice.indexOf("\"" + key + "\"");
            if (idx >= 0) {
                String v = readQuotedValue(slice, idx);
                if (!v.isEmpty()) return trimToPathTail(v);
            }
        }
        // Fall back: first string-shaped field anywhere.
        int i = 0;
        while (i < slice.length()) {
            int key = slice.indexOf('"', i);
            if (key < 0) break;
            int keyEnd = slice.indexOf('"', key + 1);
            if (keyEnd < 0) break;
            int colon = slice.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            // skip whitespace
            int p = colon + 1;
            while (p < slice.length() && Character.isWhitespace(slice.charAt(p))) p++;
            if (p >= slice.length() || slice.charAt(p) != '"') {
                i = keyEnd + 1;
                continue;
            }
            String v = readQuotedValue(slice, key);
            if (!v.isEmpty()) return trimToPathTail(v);
            i = keyEnd + 1;
        }
        return "";
    }

    /** Read the value of a JSON string field whose KEY
     *  starts at {@code keyStart}. Returns "" if the key is
     *  malformed or the value isn't a string. */
    private static String readQuotedValue(String s, int keyStart) {
        int colon = s.indexOf(':', keyStart);
        if (colon < 0) return "";
        int p = colon + 1;
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        if (p >= s.length() || s.charAt(p) != '"') return "";
        int q1 = p + 1;
        int q2 = q1;
        while (q2 < s.length()) {
            char c = s.charAt(q2);
            if (c == '\\' && q2 + 1 < s.length()) { q2 += 2; continue; }
            if (c == '"') break;
            q2++;
        }
        if (q2 >= s.length()) return "";
        return s.substring(q1, q2).replaceAll("\\s+", " ").trim();
    }

    /** Same as {@link #readQuotedValue} but specifically for
     *  a {@code "text"} field — returns the first 80-char
     *  window of any text block (used for thinking-only
     *  assistant messages). */
    private static String extractFirstTextValue(String line) {
        int textIdx = line.indexOf("\"text\"");
        if (textIdx < 0) return null;
        return readQuotedValue(line, textIdx);
    }

    /** Strip a Windows / POSIX path down to the tail so
     *  "file_write D:\tmp\abc_1\src\...\HeapSortTest.java"
     *  reads as "file_write HeapSortTest.java" without
     *  losing information. Falls back to the original on
     *  weird inputs. */
    private static String trimToPathTail(String s) {
        if (s.isEmpty()) return s;
        // Replace Windows backslashes so split-by-separator
        // works on both platforms.
        String norm = s.replace('\\', '/');
        int lastSlash = norm.lastIndexOf('/');
        String tail = lastSlash < 0 ? s : s.substring(lastSlash + 1);
        return tail.isEmpty() ? s : tail;
    }

    /** Cap a one-line summary to {@code max} chars with an
     *  ellipsis. Used by both {@link #extractSessionPreview}
     *  and {@link #extractLastAgentEvent}; duplicated here to
     *  avoid leaking the cap choice into a shared helper. */
    private static String capForList(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    @SuppressWarnings("unchecked")
    public Object loadSession(Object params) {
        Map<String, Object> p = asMap(params);
        String sessionId = stringOrThrow(p, "sessionId");
        // real implementation —calls
        // engine.loadSession(id) which swaps the in-memory
        // transcript for the on-disk file. Returns the loaded
        // message count so the desktop can decide whether to
        // hydrate localStorage with the same messages.
        try {
            var t = engine.loadSession(sessionId);
            // R268e (2026-09-15): pre-populate the write-existing-
            // file guard's readBySession map from the freshly-
            // loaded transcript. Without this, a daemon restart
            // wipes the in-memory "paths the model has read"
            // cache, and the LLM hits "blocked by pre-hook" the
            // moment it tries to update any file it had previously
            // written or edited in the same session (the user-
            // visible symptom is silent retry-loops on file_write
            // immediately after desktop reconnect). The transcript
            // itself records every file_write / file_edit this
            // session ever did, so we replay those paths into the
            // guard. file_read paths are also included because
            // reading + writing the same path is the canonical
            // model loop and we want both arms of it to be pre-
            // approved.
            try {
                prePopulateWriteGuardFromTranscript(sessionId, t);
            } catch (Exception guardEx) {
                // best-effort — a failed pre-populate is strictly
                // worse UX (LLM gets blocked once, retries with
                // overwrite=true) but never a correctness bug.
                LOG.warn("R268e: prePopulateWriteGuard failed: {}",
                        guardEx.getMessage());
            }
            return Map.of("ok", true, "sessionId", sessionId,
                    "messageCount", t.messages().size());
        } catch (UnsupportedOperationException e) {
            throw new JsonRpcProtocolException(
                    "loadSession unavailable: SessionStore is not wired",
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR,
                            "loadSession requires SessionStore; the daemon must be started with a sessions dir"));
        } catch (java.io.IOException e) {
            throw new JsonRpcProtocolException(
                    "loadSession failed: " + e.getMessage(),
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        }
    }

    /**
     * R268e (2026-09-15): replay every {@code file_read} /
     * {@code file_write} / {@code file_edit} tool call from
     * the freshly-loaded transcript into the
     * {@link org.aethercode.hooks.builtin.WriteExistingFileGuardHook}'s
     * {@code readBySession} map, so the guard doesn't trap
     * the LLM with "blocked by pre-hook" on the next
     * update of a file the session had previously touched.
     *
     * <p>Walks every {@link org.aethercode.core.message.Message}
     * in {@code t.messages()}, drills into each
     * {@link org.aethercode.core.message.ContentBlock.ToolUseBlock},
     * and extracts the path field for the three file-mutating
     * tools. Other tools (bash, glob, grep, …) are skipped
     * because they don't update disk state. No-op when the
     * write guard isn't registered (legacy engines, test
     * fixtures) or when the engine exposes no hooks.
     */
    private void prePopulateWriteGuardFromTranscript(
            String sessionId,
            org.aethercode.core.transcript.Transcript t) {
        // pull every ToolUseBlock from the loaded transcript.
        java.util.List<String> paths = new java.util.ArrayList<>();
        if (t != null) {
            for (org.aethercode.core.message.Message m : t.messages()) {
                if (m == null || m.content() == null) continue;
                for (org.aethercode.core.message.ContentBlock b : m.content()) {
                    if (!(b instanceof org.aethercode.core.message.ContentBlock.ToolUseBlock tu))
                        continue;
                    String name = tu.name();
                    if (!"file_read".equals(name)
                            && !"file_write".equals(name)
                            && !"file_edit".equals(name)) continue;
                    if (tu.input() == null) continue;
                    Object p = tu.input().get("file_path");
                    if (p == null) p = tu.input().get("path");
                    if (p == null) p = tu.input().get("filePath");
                    if (p == null) continue;
                    String s = p.toString();
                    if (!s.isBlank()) paths.add(s);
                }
            }
        }
        if (paths.isEmpty()) return;
        // find the WriteExistingFileGuardHook in the engine's
        // hook registry. The class-keyed dedup in
        // AetherCodeEngine.Builder.registerBuiltinHookIfAbsent
        // means there's exactly one instance per engine.
        org.aethercode.hooks.HookRegistry reg = engine.hookRegistry();
        if (reg == null) return;
        for (org.aethercode.hooks.Hook h : reg.snapshot()) {
            if (h instanceof org.aethercode.hooks.builtin.WriteExistingFileGuardHook guard) {
                guard.prePopulateFromSession(sessionId, paths);
                return;
            }
        }
    }

    /** mint a new session, start an empty transcript in
     *  the store, AND switch the engine to it. Returns the
     *  new session id and message count. The desktop's
     *  `createNewSession` action calls this; the previous
     *  design had the caller call `loadSession` separately,
     *  but that exposed a window where the in-memory
     *  transcript was out of sync with the on-disk file.
     *  Doing it as a single RPC keeps the engine and store
     *  consistent. */
    @SuppressWarnings("unchecked")
    public Object createSession(Object params) {
        Map<String, Object> p = asMap(params);
        // legacy .1: optional per-session cwd. legacy-M
        // callers (TUI before this R) pass no cwd and
        // get the daemon's default. The new TUI
        // (post-M) passes {sessionId, cwd} to
        // create a session bound to a specific project.
        String cwd = null;
        if (p.get("cwd") instanceof String s && !s.isBlank()) {
            cwd = s;
        }
        // optional firstPrompt. The desktop's
        // lazy-create flow calls createSession at the
        // moment the user submits their first prompt, so
        // the session row gets a real title from the
        // first line instead of showing up as
        // "Untitled session" / "New Session" until the
        // transcript is back-filled. Persisted to
        // session_info.first_prompt; surfaced via
        // listSessions as `preview` (or as a fallback
        // when extractSessionPreview can't read a
        // user-role line from the empty transcript).
        // Capped at 200 chars to keep the DB row small
        // and the eventual UI title manageable.
        String firstPrompt = null;
        if (p.get("firstPrompt") instanceof String s && !s.isBlank()) {
            firstPrompt = s.length() > 200 ? s.substring(0, 200) : s;
        }
        // optional per-session worktree. The
        // daemon resolves the worktree's path via
        // the WorktreeManager and switches the
        // engine's cwd to it. Mutually exclusive with
        // cwd (the SessionSpec contract).
        String worktree = null;
        if (p.get("worktree") instanceof String s && !s.isBlank()) {
            worktree = s;
        }
        if (cwd != null && worktree != null) {
            throw new JsonRpcProtocolException(
                    "createSession: cwd and worktree are mutually exclusive",
                    JsonRpcError.of(JsonRpcError.INVALID_PARAMS,
                            "specify exactly one of cwd / worktree"));
        }
        try {
            // resolve the worktree path
            // before creating the session. If the
            // worktree doesn't exist (e.g. the
            // user is asking for an existing one
            // that the daemon already created via
            // addWorktree), we use its path; if
            // not, we create a fresh stub
            // directory at the worktree root so
            // the session has a valid cwd to bind
            // to.
            String resolvedCwd = cwd;
            if (worktree != null) {
                org.aethercode.sdk.WorktreeManager.WorktreeState wt =
                        worktreeManager.getWorktree(worktree);
                if (wt != null) {
                    resolvedCwd = wt.path().toString();
                } else {
                    // No registered worktree — try
                    // git worktree list --porcelain
                    // for an existing one. If still
                    // nothing, create a stub
                    // directory at the worktree
                    // root so the session can
                    // proceed.
                    java.util.List<org.aethercode.sdk.WorktreeManager.WorktreeState> existing =
                            worktreeManager.listGitWorktrees();
                    boolean found = false;
                    for (var s2 : existing) {
                        if (worktree.equals(s2.name())) {
                            resolvedCwd = s2.path().toString();
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // Auto-create a stub so
                        // the session can proceed.
                        // Real git integration
                        // requires the user to
                        // call addWorktree first
                        // (with a source repo).
                        org.aethercode.sdk.WorktreeManager.WorktreeState created =
                                worktreeManager.addWorktree(worktree);
                        resolvedCwd = created.path().toString();
                    }
                }
            }
            String newId = engine.createSession(resolvedCwd);
            // Switch to the new session immediately so the
            // engine's currentTranscript / appState.transcript
            // are coherent with the on-disk file. The two-step
            // pattern (create then load) leaves a brief window
            // where the engine is on the OLD session's
            // transcript but the store has the NEW file.
            var t = engine.loadSession(newId);
            // when the new session is
            // bound to a worktree, also update
            // the engine's active cwd so the
            // model sees the worktree's files
            // (not the daemon's cwd). The
            // setCwd call is idempotent and
            // fans out the new cwd to the TUI
            // via the existing transcript_push
            // listener.
            if (resolvedCwd != null && !resolvedCwd.isBlank()) {
                try {
                    engine.setCwd(java.nio.file.Path.of(resolvedCwd));
                } catch (Exception cwdEx) {
                    LOG.warn("R152a: setCwd({}) failed: {}",
                            resolvedCwd, cwdEx.getMessage());
                }
            }
            // persist the (sessionId → cwd) binding to the
            // memory store so listSessions can surface cwd for
            // every row. legacy, switchProject wrote the
            // binding (so sessions created via the old
            // switchProject path had cwd), but the createSession
            // RPC did NOT — the engine's cwd was set but the
            // SQLite session_info table stayed empty. The
            // desktop's LeftPanel then grouped all sessions
            // under "Unlinked Project" because the lookup missed, and
            // the session list (R200 #5) looked empty. Writing
            // the row here closes the gap. The row is
            // idempotent: upsertSession is a no-op if the
            // session already has cwd recorded.
            if (memoryStore != null && resolvedCwd != null && !resolvedCwd.isBlank()) {
                try {
                    // pass the user-supplied firstPrompt
                    // through to the session_info row. The legacy version
                    // this was always null; the desktop's lazy
                    // create flow now hands the first user
                    // prompt to createSession, so the session
                    // shows up with a real title instead of
                    // "New Session" until the first transcript line
                    // is written.
                    memoryStore.sessionStore().upsertSession(
                            newId, resolvedCwd, firstPrompt);
                } catch (Exception msEx) {
                    LOG.warn("R201: upsertSession({}) failed: {}",
                            newId, msEx.getMessage());
                }
            }
            return Map.of("ok", true, "sessionId", newId,
                    "cwd", resolvedCwd == null ? "" : resolvedCwd,
                    "worktree", worktree == null ? "" : worktree,
                    "messageCount", t.messages().size(),
                    "active", newId);
        } catch (UnsupportedOperationException e) {
            throw new JsonRpcProtocolException(
                    "createSession unavailable: SessionStore is not wired",
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        } catch (java.io.IOException e) {
            throw new JsonRpcProtocolException(
                    "createSession failed: " + e.getMessage(),
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        }
    }

    /** remove a session file from the store. Refuses to
     *  delete the active session (the user must load another
     *  one first). Returns whether the file was actually
     *  removed. */
    @SuppressWarnings("unchecked")
    public Object deleteSession(Object params) {
        Map<String, Object> p = asMap(params);
        String sessionId = stringOrThrow(p, "sessionId");
        try {
            boolean removed = engine.deleteSession(sessionId);
            return Map.of("ok", true, "sessionId", sessionId, "removed", removed);
        } catch (UnsupportedOperationException e) {
            throw new JsonRpcProtocolException(
                    "deleteSession unavailable: SessionStore is not wired",
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        } catch (IllegalStateException e) {
            throw new JsonRpcProtocolException(
                    "deleteSession refused: " + e.getMessage(),
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        } catch (java.io.IOException e) {
            throw new JsonRpcProtocolException(
                    "deleteSession failed: " + e.getMessage(),
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 3-layer memory RPCs (USER / PROJECT / SESSION)
    // ------------------------------------------------------------------
    //
    // The brief asks for a unified memory surface with three
    // visibility scopes. The wire shape is consistent across
    // all three:
    //   getMemory({scope, key, sessionId?})
    //   setMemory({scope, key, value, sessionId?})
    //   listMemory({scope, sessionId?})
    //   deleteMemory({scope, key, sessionId?})
    //   compressProjectMemory({cwd, force?})
    //
    // The sessionId parameter is only meaningful for
    // scope=SESSION. For USER / PROJECT the engine's active
    // session's cwd is used (or the explicit cwd parameter
    // on compressProjectMemory).
    //
    // All four CRUD handlers return
    //   { ok: false, reason: "memory not configured" }
    // when the daemon was started without a memory base
    // (e.g. a test fixture). The desktop treats this as
    // "the user hasn't created a memory file yet" rather
    // than an error.
    // ------------------------------------------------------------------

    private Object requireMemory() {
        if (memoryStore == null) {
            return Map.of("ok", false, "reason", "memory not configured");
        }
        return null;
    }

    /** resolve a memory request's scope to a concrete
     *  path-or-key. Returns null on error (the caller wraps
     *  the error into the JSON-RPC response). */
    private org.aethercode.memory.MemoryScope parseScope(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        try { return org.aethercode.memory.MemoryScope.valueOf(s.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** resolve the cwd for a memory request.
     *  Priority: explicit {@code cwd} param > active engine's
     *  appState.cwd() > current process cwd. */
    private java.nio.file.Path resolveCwd(Map<String, Object> p, AetherCodeEngine target) {
        Object cwdRaw = p.get("cwd");
        if (cwdRaw instanceof String s && !s.isBlank()) {
            return java.nio.file.Paths.get(s).toAbsolutePath().normalize();
        }
        if (target != null && target.appState().cwd() != null) {
            return target.appState().cwd();
        }
        return java.nio.file.Paths.get("").toAbsolutePath();
    }

    public Object getMemory(Object params) {
        Object err = requireMemory(); if (err != null) return err;
        @SuppressWarnings("unchecked")
        Map<String, Object> p = asMap(params);
        org.aethercode.memory.MemoryScope scope = parseScope(p.get("scope"));
        String key = p.get("key") instanceof String s ? s : null;
        if (scope == null) {
            return Map.of("ok", false, "reason", "scope must be one of USER/PROJECT/SESSION");
        }
        if (scope == org.aethercode.memory.MemoryScope.SESSION) {
            String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s
                    : (engine != null ? engine.appState().sessionId() : null);
            if (sid == null) {
                return Map.of("ok", false, "reason", "sessionId required for SESSION scope");
            }
            if (key == null) {
                return Map.of("ok", false, "reason", "key required");
            }
            return memoryStore.getSession(sid, key)
                    .map(e -> Map.of("ok", true, "scope", "SESSION",
                            "sessionId", sid, "key", e.key(),
                            "value", e.value(),
                            "createdAtMs", e.createdAtMs(),
                            "updatedAtMs", e.updatedAtMs()))
                    .orElse(Map.of("ok", false, "reason", "not found"));
        }
        if (scope == org.aethercode.memory.MemoryScope.USER) {
            if (key == null) {
                return Map.of("ok", false, "reason", "key required");
            }
            return memoryStore.getUser(key)
                    .map(i -> Map.of("ok", true, "scope", "USER",
                            "key", i.id(),
                            "content", i.content(),
                            "tags", i.tags(),
                            "createdAt", i.createdAt().toString(),
                            "updatedAt", i.updatedAt().toString()))
                    .orElse(Map.of("ok", false, "reason", "not found"));
        }
        if (scope == org.aethercode.memory.MemoryScope.PROJECT) {
            AetherCodeEngine target = resolveRpcTarget(
                    p.get("sessionId") instanceof String s ? s : null);
            java.nio.file.Path cwd = resolveCwd(p, target);
            if (key == null) {
                return Map.of("ok", false, "reason", "key required");
            }
            return memoryStore.getProject(cwd.toString(), key)
                    .map(i -> Map.of("ok", true, "scope", "PROJECT",
                            "cwd", cwd.toString(),
                            "key", i.id(),
                            "content", i.content(),
                            "tags", i.tags(),
                            "createdAt", i.createdAt().toString(),
                            "updatedAt", i.updatedAt().toString()))
                    .orElse(Map.of("ok", false, "reason", "not found"));
        }
        // LOCAL —older scope, not part of the 3-layer brief.
        return Map.of("ok", false, "reason", "scope LOCAL not supported via this RPC");
    }

    public Object setMemory(Object params) {
        Object err = requireMemory(); if (err != null) return err;
        @SuppressWarnings("unchecked")
        Map<String, Object> p = asMap(params);
        org.aethercode.memory.MemoryScope scope = parseScope(p.get("scope"));
        if (scope == null) {
            return Map.of("ok", false, "reason", "scope must be one of USER/PROJECT/SESSION");
        }
        if (scope == org.aethercode.memory.MemoryScope.SESSION) {
            String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s
                    : (engine != null ? engine.appState().sessionId() : null);
            String key = p.get("key") instanceof String s ? s : null;
            String value = p.get("value") instanceof String s ? s : null;
            if (sid == null || key == null || value == null) {
                return Map.of("ok", false, "reason", "sessionId, key, value required");
            }
            memoryStore.putSession(sid, key, value);
            return Map.of("ok", true, "scope", "SESSION", "sessionId", sid, "key", key);
        }
        if (scope == org.aethercode.memory.MemoryScope.USER) {
            String content = p.get("content") instanceof String s ? s
                    : p.get("value") instanceof String s ? s : null;
            if (content == null) {
                return Map.of("ok", false, "reason", "content required");
            }
            @SuppressWarnings("unchecked")
            List<String> tags = p.get("tags") instanceof List<?> l
                    ? l.stream().map(Object::toString).toList() : List.of();
            var item = memoryStore.putUser(content, tags);
            return Map.of("ok", true, "scope", "USER",
                    "key", item.id(), "content", item.content());
        }
        if (scope == org.aethercode.memory.MemoryScope.PROJECT) {
            // project memory is an append-only
            // change-log. setMemory adds a timestamped
            // entry; the LLM compression (if enabled)
            // summarises the oldest block when the
            // threshold trips. Callers that want to
            // replace a specific entry by id should
            // use FileBackedMemory.update directly —
            // not exposed on the wire yet.
            AetherCodeEngine target = resolveRpcTarget(
                    p.get("sessionId") instanceof String s ? s : null);
            java.nio.file.Path cwd = resolveCwd(p, target);
            String content = p.get("content") instanceof String s ? s
                    : p.get("value") instanceof String s ? s : null;
            if (content == null) {
                return Map.of("ok", false, "reason", "content required");
            }
            var item = memoryStore.appendProjectChange(cwd.toString(), content);
            return Map.of("ok", true, "scope", "PROJECT",
                    "cwd", cwd.toString(),
                    "key", item.id(), "content", item.content(),
                    "autoCompress", memoryStore.autoCompress(),
                    "projectCompressThreshold", memoryStore.projectCompressThreshold(),
                    "currentSize", memoryStore.listProject(cwd.toString()).size());
        }
        return Map.of("ok", false, "reason", "scope LOCAL not supported via this RPC");
    }

    public Object listMemory(Object params) {
        Object err = requireMemory(); if (err != null) return err;
        @SuppressWarnings("unchecked")
        Map<String, Object> p = asMap(params);
        org.aethercode.memory.MemoryScope scope = parseScope(p.get("scope"));
        if (scope == null) {
            return Map.of("ok", false, "reason", "scope must be one of USER/PROJECT/SESSION");
        }
        if (scope == org.aethercode.memory.MemoryScope.SESSION) {
            String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s
                    : (engine != null ? engine.appState().sessionId() : null);
            if (sid == null) {
                return Map.of("ok", false, "reason", "sessionId required for SESSION scope");
            }
            List<org.aethercode.memory.SessionMemoryStore.MemoryEntry> entries =
                    memoryStore.listSession(sid);
            List<Map<String, Object>> out = new java.util.ArrayList<>(entries.size());
            for (var e : entries) {
                out.add(Map.of("key", e.key(), "value", e.value(),
                        "createdAtMs", e.createdAtMs(),
                        "updatedAtMs", e.updatedAtMs()));
            }
            return Map.of("ok", true, "scope", "SESSION",
                    "sessionId", sid, "count", out.size(), "entries", out);
        }
        if (scope == org.aethercode.memory.MemoryScope.USER) {
            List<org.aethercode.memory.FileBackedMemory.MemoryItem> items = memoryStore.listUser();
            List<Map<String, Object>> out = new java.util.ArrayList<>(items.size());
            for (var i : items) {
                out.add(Map.of("key", i.id(), "content", i.content(),
                        "tags", i.tags(),
                        "createdAt", i.createdAt().toString(),
                        "updatedAt", i.updatedAt().toString()));
            }
            return Map.of("ok", true, "scope", "USER",
                    "count", out.size(), "entries", out);
        }
        if (scope == org.aethercode.memory.MemoryScope.PROJECT) {
            AetherCodeEngine target = resolveRpcTarget(
                    p.get("sessionId") instanceof String s ? s : null);
            java.nio.file.Path cwd = resolveCwd(p, target);
            List<org.aethercode.memory.FileBackedMemory.MemoryItem> items =
                    memoryStore.listProject(cwd.toString());
            List<Map<String, Object>> out = new java.util.ArrayList<>(items.size());
            for (var i : items) {
                out.add(Map.of("key", i.id(), "content", i.content(),
                        "tags", i.tags(),
                        "createdAt", i.createdAt().toString(),
                        "updatedAt", i.updatedAt().toString()));
            }
            return Map.of("ok", true, "scope", "PROJECT",
                    "cwd", cwd.toString(),
                    "count", out.size(),
                    "autoCompress", memoryStore.autoCompress(),
                    "projectCompressThreshold", memoryStore.projectCompressThreshold(),
                    "keepRecent", memoryStore.keepRecent(),
                    "entries", out);
        }
        return Map.of("ok", false, "reason", "scope LOCAL not supported via this RPC");
    }

    public Object deleteMemory(Object params) {
        Object err = requireMemory(); if (err != null) return err;
        @SuppressWarnings("unchecked")
        Map<String, Object> p = asMap(params);
        org.aethercode.memory.MemoryScope scope = parseScope(p.get("scope"));
        String key = p.get("key") instanceof String s ? s : null;
        if (scope == null || key == null) {
            return Map.of("ok", false, "reason", "scope and key required");
        }
        if (scope == org.aethercode.memory.MemoryScope.SESSION) {
            String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s
                    : (engine != null ? engine.appState().sessionId() : null);
            if (sid == null) {
                return Map.of("ok", false, "reason", "sessionId required for SESSION scope");
            }
            boolean ok = memoryStore.deleteSession(sid, key);
            return Map.of("ok", ok, "scope", "SESSION", "sessionId", sid, "key", key);
        }
        if (scope == org.aethercode.memory.MemoryScope.USER) {
            // FileBackedMemory uses id-as-key; the
            // desktop's "delete entry" affordance
            // passes the entry's id directly.
            boolean ok = memoryStore.userStore() == null
                    ? false
                    : memoryStore.userStore().get(key).isPresent()
                        && memoryStore.userStore().remove(key);
            return Map.of("ok", ok, "scope", "USER", "key", key);
        }
        if (scope == org.aethercode.memory.MemoryScope.PROJECT) {
            AetherCodeEngine target = resolveRpcTarget(
                    p.get("sessionId") instanceof String s ? s : null);
            java.nio.file.Path cwd = resolveCwd(p, target);
            boolean ok = memoryStore.deleteProject(cwd.toString(), key);
            return Map.of("ok", ok, "scope", "PROJECT",
                    "cwd", cwd.toString(), "key", key);
        }
        return Map.of("ok", false, "reason", "scope LOCAL not supported via this RPC");
    }

    public Object compressProjectMemory(Object params) {
        Object err = requireMemory(); if (err != null) return err;
        @SuppressWarnings("unchecked")
        Map<String, Object> p = asMap(params);
        AetherCodeEngine target = resolveRpcTarget(
                p.get("sessionId") instanceof String s ? s : null);
        java.nio.file.Path cwd = resolveCwd(p, target);
        // R127 brief: "record 20-50 times, configurable... keep the most recent
        // 10 modifications". The default threshold + keepRecent
        // come from .aethercode/config.json (or 50/10 if
        // absent). The user can pass overrides here for
        // an out-of-band "force a compress now" call.
        int threshold = p.get("threshold") instanceof Number n
                ? Math.max(2, n.intValue()) : memoryStore.projectCompressThreshold();
        int keepRecent = p.get("keepRecent") instanceof Number n
                ? Math.max(1, n.intValue()) : memoryStore.keepRecent();
        java.nio.file.Path file = org.aethercode.memory.MemoryPaths.entrypoint(
                org.aethercode.memory.MemoryPaths.agentMemoryDir(
                        memoryStore.agentType(),
                        org.aethercode.memory.MemoryScope.PROJECT,
                        cwd));
        // The compressor takes the per-file lock
        // internally. We re-use the daemon's ProjectMemoryCompressor
        // (already wired at startup with the chat client).
        // The simplest path: write a temporary
        // "always compress" flag and re-call maybeCompress.
        // For now we just call maybeCompress with the
        // (threshold, keepRecent) pair and let the
        // compressor decide. force=true lowers the
        // threshold to current size.
        boolean force = Boolean.TRUE.equals(p.get("force"));
        int effectiveThreshold = force
                ? memoryStore.listProject(cwd.toString()).size() - 1
                : threshold;
        if (force && effectiveThreshold < 1) effectiveThreshold = 1;
        ProjectMemoryCompressor.ChatClient client = compressorChatClient();
        var compressor = new ProjectMemoryCompressor(client);
        var res = compressor.maybeCompress(file, effectiveThreshold, keepRecent);
        return Map.of(
                "ok", res.compressed() || !res.reason().equals("under threshold"),
                "compressed", res.compressed(),
                "beforeCount", res.beforeCount(),
                "afterCount", res.afterCount(),
                "reason", res.reason(),
                "cwd", cwd.toString(),
                "file", file.toString());
    }

    /** the chat client the compressor should use.
     *  Falls back to NOOP if no chat client is wired —
     *  the compressor then produces the tag-only
     *  fallback ("[compressed: N entries]") and the
     *  caller knows to expect a brief summary. */
    private ProjectMemoryCompressor.ChatClient compressorChatClient() {
        if (chatClientResolver == null) return ProjectMemoryCompressor.NOOP;
        // We don't have a "current provider" hook in
        // ProjectMemoryCompressor (it just takes a
        // single-shot prompt). For now use the current
        // provider+model and ask the resolver.
        String providerModel = currentProviderName == null || currentModelId == null
                ? ""
                : currentProviderName + "/" + currentModelId;
        return prompt -> {
            try {
                var client = chatClientResolver.apply(providerModel);
                if (client == null) return java.util.Optional.empty();
                // The core ChatClient interface has a
                // stream(messages, systemPrompt, tools) method
                // — no single-shot complete(prompt). We can't
                // bridge that here without bringing the
                // streaming engine into the memory module
                // (a chicken-and-egg: the compressor runs in
                // the middle of a daemon turn, the chat
                // client is busy driving the model). The
                // ProjectMemoryCompressor falls back to the
                // tag-only "[compressed: N entries]" line in
                // this case — the user can plumb a richer
                // completion path by passing a custom
                // ChatClient at the LayeredMemoryStore
                // constructor (the SpringAiChatClient
                // resolver is the default).
                LOG.debug("compressor chat client lacks complete() — using tag-only fallback");
                return java.util.Optional.empty();
            } catch (Exception e) {
                LOG.warn("compressor chat failed: {}", e.getMessage());
                return java.util.Optional.empty();
            }
        };
    }

    // ------------------------------------------------------------------
    // skill registry RPCs
    // ------------------------------------------------------------------
    //
    // The desktop's SkillsPanel (RightPanel) calls these to discover
    // available skills and to fetch a skill's full body on demand.
    // The body's user-facing target is the prompt template a user
    // types when invoking a skill from the chat bar.
    //
    // Wire shapes:
    //   listSkills() -> { ok, count, skills: [{name, description,
    //                       descriptionZhHans, displayName,
    //                       displayNameZhHans, source, lastModifiedMs}] }
    //   getSkillBody({name}) -> { ok, name, body, path, lastModifiedMs }
    //   reloadSkills() -> { ok, count, reloadedAt }
    //   addSkill({name, scope, body}) -> { ok, name, scope, path,
    //                          count, reloadedAt }      (legacy)
    //   addSkill({name, scope}) -> { ok: false, error }    (missing fields)
    // ------------------------------------------------------------------

    public Object listSkills(Object params) {
        List<org.aethercode.core.skill.SkillRegistry.SkillMeta> all = engine.listSkills();
        List<Map<String, Object>> out = new java.util.ArrayList<>(all.size());
        for (var m : all) {
            out.add(Map.of(
                    "name", m.name(),
                    "description", m.description(),
                    "descriptionZhHans", m.descriptionZhHans(),
                    "displayName", m.displayName(),
                    "displayNameZhHans", m.displayNameZhHans(),
                    "source", m.source(),
                    "lastModifiedMs", m.lastModifiedMs()));
        }
        return Map.of("ok", true, "count", out.size(), "skills", out);
    }

    public Object getSkillBody(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        var opt = engine.getSkillBody(name);
        if (opt.isEmpty()) {
            return Map.of("ok", false, "name", name, "error", "skill not found");
        }
        // The desktop uses path for the "open in editor" affordance
        // and lastModifiedMs for a "stale since" badge.
        var meta = engine.listSkills().stream()
                .filter(m -> m.name().equals(name))
                .findFirst().orElse(null);
        return Map.of(
                "ok", true,
                "name", name,
                "body", opt.get(),
                "path", meta == null ? "" : meta.path().toString(),
                "lastModifiedMs", meta == null ? 0L : meta.lastModifiedMs());
    }

    public Object reloadSkills(Object params) {
        int count = engine.reloadSkills();
        return Map.of("ok", true, "count", count, "reloadedAt", System.currentTimeMillis());
    }

    /**
     * install a SKILL.md into the user- or project-tier root,
     * then {@link #reloadSkills()}. The desktop's
     * {@code /skill add <name> --scope global|project} slash
     * command (and any external marketplace tool) calls this
     * with the skill's frontmatter + body. The name is
     * constrained to {@code [A-Za-z0-9._-]+} on the engine side
     * (no {@code ../} escape, no leading slash); an unsafe name
     * returns {@code ok: false, error: "..."} without touching
     * the filesystem. The default {@code scope} is {@code PROJECT}
     * (the user picked a cwd on launch; project-local is the
     * "do the right thing" tier).
     *
     * <p>Side effects: the new SKILL.md is on disk by the time
     * this method returns, and the {@code skillRegistry} is
     * reloaded in the same call so the new skill is immediately
     * visible to {@code listSkills} / {@code getSkillBody} on
     * the very next RPC. The daemon's file-watcher also fires
     * (legacy) but the explicit reload here closes the
     * 1-2 second debounce gap.
     */
    public Object addSkill(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        // scope defaults to PROJECT (cwd-local is the
        // safer tier; users opt INTO global explicitly).
        String scopeStr = p.get("scope") instanceof String s && !s.isBlank()
                ? s.toUpperCase(java.util.Locale.ROOT) : "PROJECT";
        org.aethercode.core.skill.SkillRegistry.Scope scope;
        try {
            scope = org.aethercode.core.skill.SkillRegistry.Scope.valueOf(scopeStr);
        } catch (IllegalArgumentException iae) {
            return Map.of("ok", false, "error",
                    "unknown scope: " + scopeStr + " (expected GLOBAL or PROJECT)");
        }
        // body is required. We don't auto-template a
        // SKILL.md skeleton here — the caller (chat slash
        // command or marketplace) owns the content shape.
        String body = p.get("body") instanceof String s ? s : null;
        if (body == null) {
            return Map.of("ok", false, "error", "missing required field: body");
        }
        // name safety. The SkillRegistry.addSkill also
        // checks this; we surface a friendlier error here
        // before the call so the desktop doesn't have to
        // parse the engine's stack trace. First char must
        // be a letter or digit (rejects `..`, `.`, leading
        // dot / underscore / dash).
        if (!name.matches("^[A-Za-z0-9][A-Za-z0-9._-]*$")) {
            return Map.of("ok", false, "error",
                    "invalid name \"" + name + "\" (allowed: must start with a letter or digit, then letters / digits / '.', '_', '-')");
        }
        if (engine.skillRegistry() == null) {
            return Map.of("ok", false, "error",
                    "skill discovery is disabled (--no-skills)");
        }
        boolean ok = engine.skillRegistry().addSkill(name, scope, body);
        if (!ok) {
            return Map.of("ok", false, "error",
                    "addSkill failed (no " + scope + " root configured, or name is unsafe)");
        }
        // count + reloadedAt round-tripped from the
        // registry's post-write reload(), so the caller
        // doesn't need a separate RPC.
        return Map.of(
                "ok", true,
                "name", name,
                "scope", scope.name(),
                "path", engine.skillRegistry().list().stream()
                        .filter(m -> m.name().equals(name))
                        .findFirst()
                        .map(m -> m.path().toString())
                        .orElse(""),
                "count", engine.listSkills().size(),
                "reloadedAt", System.currentTimeMillis());
    }

    /** legacy: unified registry reload. Triggers one (or
     *  every) registry reloader and broadcasts the result
     *  to every connected client via {@code NOTIFY_REGISTRY_RELOADED}.
     *  When the engine has no {@code RegistryReloadService}
     *  wired (unit tests, the stdio daemon), this falls back
     *  to the per-registry {@code reloadSkills} /
     *  {@code reloadAgents} paths so the wire contract is
     *  the same. */
    public Object reloadRegistries(Object params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = asMap(params);
        String kind = p.get("kind") instanceof String s ? s.toUpperCase(java.util.Locale.ROOT) : "ALL";
        long atMs = System.currentTimeMillis();
        // Run the reloaders. We catch per-kind so a single
        // broken registry doesn't fail the whole call.
        int skillsCount = 0, agentsCount = 0;
        int mcpAdded = 0, mcpRemoved = 0, mcpChanged = 0;
        boolean mcpReloaded = false;
        String mcpError = null;
        java.util.List<String> errors = new java.util.ArrayList<>();
        if ("ALL".equals(kind) || "SKILLS".equals(kind)) {
            try { skillsCount = engine.reloadSkills(); }
            catch (Exception e) { errors.add("skills: " + e.getMessage()); }
        }
        if ("ALL".equals(kind) || "AGENTS".equals(kind)) {
            try {
                org.aethercode.core.agent.AgentRegistry reg = engine.agentRegistry();
                if (reg != null) { reg.reload(); agentsCount = reg.list().size(); }
            } catch (Exception e) { errors.add("agents: " + e.getMessage()); }
        }
        if ("ALL".equals(kind) || "MCP".equals(kind)) {
            // MCP reload is now real. We delegate
            // to the engine's McpManager which diffs
            // the new config against the live set,
            // closes removed servers, starts new ones,
            // and returns the new tool list. The
            // engine then swaps the tool pool atomically.
            org.aethercode.mcp.McpManager mgr = engine.mcpManager();
            if (mgr != null) {
                // The reload needs a path. We prefer the
                // project mcp.json when present (the
                // path the user is most likely editing);
                // fall back to the user mcp.json. The
                // DaemonRunner's watcher picks the same
                // target so the RPC and the watcher stay
                // consistent.
                java.nio.file.Path projectMcp = engine.appState().cwd()
                        .resolve(".aethercode").resolve("mcp.json");
                java.nio.file.Path userMcp = java.nio.file.Path.of(
                        System.getProperty("user.home"), ".aethercode", "mcp.json");
                java.nio.file.Path target = java.nio.file.Files.isRegularFile(projectMcp)
                        ? projectMcp : (java.nio.file.Files.isRegularFile(userMcp) ? userMcp : null);
                if (target != null) {
                    var res = mgr.reload(target);
                    engine.replaceMcpTools(res.tools());
                    mcpReloaded = true;
                    mcpAdded = res.added();
                    mcpRemoved = res.removed();
                    mcpChanged = res.changed();
                    if (res.hasErrors()) {
                        mcpError = String.join("; ", res.errors());
                    }
                } else {
                    mcpReloaded = true;
                    mcpError = "no mcp.json in user or project dir";
                }
            } else {
                mcpReloaded = true;
                mcpError = "MCP manager not installed; engine was built without MCP support";
            }
        }
        // Broadcast to every connected client so the
        // renderer's right-rail cards can re-fetch the
        // affected list. The notification carries the
        // kind so the renderer can pick the cheapest
        // refresh path (e.g. "all" re-fetches everything;
        // "skills" re-fetches just the skill list).
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("kind", kind);
        payload.put("atMs", atMs);
        payload.put("skillsCount", skillsCount);
        payload.put("agentsCount", agentsCount);
        payload.put("mcpReloaded", mcpReloaded);
        payload.put("mcpAdded", mcpAdded);
        payload.put("mcpRemoved", mcpRemoved);
        payload.put("mcpChanged", mcpChanged);
        if (mcpError != null) payload.put("mcpNote", mcpError);
        if (!errors.isEmpty()) payload.put("errors", errors);
        notifier.accept(new org.aethercode.protocol.jsonrpc.JsonRpcNotification(
                "2.0", "registry_reloaded", payload));
        return Map.of(
                "ok", true,
                "kind", kind,
                "atMs", atMs,
                "skillsCount", skillsCount,
                "agentsCount", agentsCount,
                "mcpReloaded", mcpReloaded,
                "mcpAdded", mcpAdded,
                "mcpRemoved", mcpRemoved,
                "mcpChanged", mcpChanged,
                "errors", errors);
    }

    // ------------------------------------------------------------------
    // Mavis agent registry RPCs
    // ------------------------------------------------------------------
    //
    // AetherCode does not own agent definitions; Mavis does. We
    // surface the Mavis agents so the desktop can render an
    // "available agents" picker (and so a workflow editor can pick
    // an agent for a `kind: agent` step).
    //
    // Wire shapes:
    //   listAgents() -> { ok, count, agents: [{name, description,
    //                       displayName, lastModifiedMs}] }
    //   getAgentBody({name}) -> { ok, name, body, path, lastModifiedMs }
    // ------------------------------------------------------------------

    public Object listAgents(Object params) {
        List<org.aethercode.core.agent.AgentRegistry.AgentMeta> all = engine.listAgents();
        List<Map<String, Object>> out = new java.util.ArrayList<>(all.size());
        for (var m : all) {
            // include the agent's frontmatter
            // {@code model:} field so the Settings
            // panel's Agents tab can show "this agent
            // uses glm/glm-4-flash" without an extra
            // getAgentBody round-trip per row. The
            // field is empty when the agent has no
            // model binding (legacy legacy agents
            // written pre-3 land).
            out.add(Map.of(
                    "name", m.name(),
                    "description", m.description(),
                    "displayName", m.displayName(),
                    "model", m.model() == null ? "" : m.model(),
                    "lastModifiedMs", m.lastModifiedMs()));
        }
        return Map.of("ok", true, "count", out.size(), "agents", out);
    }

    public Object getAgentBody(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        var opt = engine.getAgentBody(name);
        if (opt.isEmpty()) {
            return Map.of("ok", false, "name", name, "error", "agent not found");
        }
        // getAgentBody now returns the full
        // AgentMeta so the editor can prefill the
        // description / displayName / model fields
        // when opening an existing agent for edit.
        // The legacy shape (just name + body + path
        // + lastModifiedMs) is preserved by reading
        // from the meta when available.
        var meta = engine.getAgentMeta(name).orElse(null);
        return Map.of(
                "ok", true,
                "name", name,
                "body", opt.get(),
                "path", meta == null ? "" : meta.path().toString(),
                "description", meta == null ? "" : meta.description(),
                "displayName", meta == null ? "" : meta.displayName(),
                "model", meta == null ? "" : meta.model(),
                "lastModifiedMs", meta == null ? 0L : meta.lastModifiedMs());
    }

    // All three take the same params shape:
    //   {name, description?, displayName?, model?, body}
    // where body is the raw markdown (no
    // frontmatter —the daemon assembles the
    // frontmatter from the named params).
    // The registry reloads after every write
    // so listAgents sees the new state
    // without a manual refresh.

    public Object createAgent(Object params) {
        return writeAgent(params, /* requireExists */ false);
    }
    public Object updateAgent(Object params) {
        return writeAgent(params, /* requireExists */ true);
    }

    @SuppressWarnings("unchecked")
    private Object writeAgent(Object params, boolean requireExists) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        String description = p.get("description") instanceof String d ? d : "";
        String displayName = p.get("displayName") instanceof String d ? d : null;
        String model = p.get("model") instanceof String m ? m : null;
        String body = p.get("body") instanceof String b ? b : "";
        try {
            org.aethercode.core.agent.AgentRegistry reg = engine.agentRegistry();
            if (reg == null) {
                throw new JsonRpcProtocolException(
                        "agent registry not wired",
                        JsonRpcError.of(JsonRpcError.ENGINE_ERROR,
                                "start the daemon with --agents-dir or agentsDir in the engine builder"));
            }
            if (requireExists) {
                reg.update(name, description, displayName, model, body);
            } else {
                reg.create(name, description, displayName, model, body);
            }
            return Map.of("ok", true, "name", name);
        } catch (IllegalArgumentException e) {
            // Validation failure (bad name, etc.)
            throw invalidParams(e.getMessage());
        } catch (java.io.IOException e) {
            throw new JsonRpcProtocolException(
                    "agent write failed: " + e.getMessage(),
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        }
    }

    public Object deleteAgent(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "name");
        try {
            org.aethercode.core.agent.AgentRegistry reg = engine.agentRegistry();
            if (reg == null) {
                throw new JsonRpcProtocolException(
                        "agent registry not wired",
                        JsonRpcError.of(JsonRpcError.ENGINE_ERROR,
                                "agent registry missing"));
            }
            reg.delete(name);
            return Map.of("ok", true, "name", name);
        } catch (IllegalArgumentException e) {
            throw invalidParams(e.getMessage());
        } catch (java.io.IOException e) {
            throw new JsonRpcProtocolException(
                    "agent delete failed: " + e.getMessage(),
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        }
    }

    public Object reloadAgents(Object params) {
        org.aethercode.core.agent.AgentRegistry reg = engine.agentRegistry();
        if (reg == null) {
            throw new JsonRpcProtocolException(
                    "agent registry not wired",
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR,
                            "agent registry missing"));
        }
        int count = reg.reload();
        return Map.of("ok", true, "count", count);
    }

    // ------------------------------------------------------------------
    // engine health + concurrency profile
    // ------------------------------------------------------------------
    //
    // The desktop's StatusBar polls {@code getEngineStats} every
    // 5s to render the memory badge. The Settings panel writes
    // {@code setConcurrencyProfile} when the user picks a
    // profile from the dropdown. The response shape is a
    // flattened {@link org.aethercode.core.concurrency.EngineStats#toMap()}
    // payload plus an {@code ok} flag.
    //
    // Wire shapes:
    //   getEngineStats() -> { ok, memUsedMb, memMaxMb, memPct,
    //                        throttled, backpressured, queriesInFlight,
    //                        maxConcurrentQueries, toolsInFlight,
    //                        maxConcurrentTools, branchesInFlight,
    //                        maxConcurrentBranches, concurrencyProfile,
    //                        sampledAtMs }
    //   setConcurrencyProfile({profile}) -> { ok, profile }
    // ------------------------------------------------------------------

    public Object getEngineStats(Object params) {
        var s = engine.getEngineStats();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(s.toMap());
        out.put("ok", true);
        return out;
    }

    public Object setConcurrencyProfile(Object params) {
        Map<String, Object> p = asMap(params);
        String name = stringOrThrow(p, "profile");
        try {
            engine.setConcurrencyProfile(name);
            return Map.of("ok", true, "profile", name.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new JsonRpcProtocolException(
                    "unknown concurrency profile: " + name,
                    JsonRpcError.of(JsonRpcError.INVALID_PARAMS,
                            "profile must be one of: low, normal, high"));
        }
    }

    /** return the engine's in-memory transcript for
     *  the currently-loaded session. Used by the desktop to
     *  back-fill its {@code messages} array after a WS
     *  reconnect (or on the very first launch) —the
     *  `transcript_event` subscriber is unreliable for
     *  events that fired before the subscriber attached.
     *  Returns the same shape as
     *  {@code AetherCodeEngine.loadSession} would push via
     *  a {@code transcript_event} with action "sync":
     *  {@code {ok, sessionId, messages[]}}. */
    public Object getTranscript(Object params) {
        String sid = engine.appState().sessionId();
        java.util.List<org.aethercode.core.message.Message> transcript = engine.appState().transcript();
        java.util.List<java.util.Map<String, Object>> msgs = new java.util.ArrayList<>(transcript.size());
        for (org.aethercode.core.message.Message m : transcript) msgs.add(m.toMap());
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", sid);
        out.put("messages", msgs);
        return out;
    }

    // The Settings panel uses listProviders to
    // populate the provider picker. Returns the
    // full registry as a flat list of {name, type,
    // baseUrl, apiKeyEnv, defaultModel, models[]};
    // the renderer can group by provider and show
    // each model as a sub-row. The "current" block
    // tells the Settings panel which row to mark
    // as the active one (so a user editing a
    // different model in the picker can see the
    // engine's current choice).
    public Object listProviders(Object params) {
        org.aethercode.core.providers.ProviderRegistry reg = providerRegistry;
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (reg != null) {
            for (org.aethercode.core.providers.ProviderSpec p : reg.list()) {
                java.util.Map<String, Object> pm = new java.util.LinkedHashMap<>();
                pm.put("name", p.name());
                pm.put("type", p.type());
                pm.put("baseUrl", p.baseUrl());
                pm.put("apiKeyEnv", p.apiKeyEnv());
                pm.put("defaultModel", p.defaultModel());
                java.util.List<java.util.Map<String, Object>> ms = new java.util.ArrayList<>();
                for (org.aethercode.core.providers.ModelSpec m : p.models()) {
                    java.util.Map<String, Object> mm = new java.util.LinkedHashMap<>();
                    mm.put("id", m.id());
                    mm.put("inputPer1k", m.inputPer1k());
                    mm.put("outputPer1k", m.outputPer1k());
                    mm.put("context", m.context());
                    mm.put("default", m.isDefault());
                    ms.add(mm);
                }
                pm.put("models", ms);
                out.add(pm);
            }
        }
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("providers", out);
        resp.put("currentProvider", currentProviderName);
        resp.put("currentModel", currentModelId);
        return resp;
    }

    /** resolve a {@code "provider/model"} string to a
     *  fresh {@link org.aethercode.core.llm.ChatClient}.
     *  Used by the workflow executor's SkillInvoker to
     *  build a per-agent ChatClient for child sessions
     *  (the agent's frontmatter {@code model:} field
     *  declares the binding). Also used by
     *  {@link #switchProvider} to hot-swap the engine's
     *  client.
     *
     *  <p>The format is {@code "provider/model"} where
     *  {@code provider} is a key in
     *  {@link #providerRegistry} and {@code model} is one
     *  of the provider's declared model ids. A model
     *  without a {@code "provider/"} prefix is treated as
     *  the current provider's model (handy for
     *  providers.yaml's
     *  {@code defaultModel: model-id} that doesn't carry
     *  the prefix). When the resolver is null, returns
     *  {@code null} (the caller falls back to the engine's
     *  default client). Throws on a malformed string
     *  (empty model). The actual ChatClient construction
     *  is delegated to the injected
     *  {@link #chatClientResolver} so this module does
     *  NOT need to depend on the engine-springai
     *  implementation. */
    org.aethercode.core.llm.ChatClient resolveChatClient(String providerModel) {
        if (providerModel == null || providerModel.isBlank()) return null;
        var resolver = chatClientResolver;
        if (resolver == null) return null;
        // A model without a "provider/" prefix is
        // ambiguous: it's resolved against the
        // CURRENT provider. The SkillInvoker in
        // runWorkflow usually passes the
        // already-prefixed "provider/model" string
        // (because the agent.md frontmatter uses
        // the same shape), so this branch is the
        // exception. The resolver knows how to
        // translate the bare model id into a
        // ChatClient when needed.
        int slash = providerModel.indexOf('/');
        if (slash < 0) {
            // No provider prefix; prepend the
            // current provider name so the
            // resolver gets a fully-qualified
            // "provider/model" string.
            String providerName = currentProviderName;
            if (providerName == null || providerName.isBlank()) {
                if (providerRegistry == null) return null;
                var def = providerRegistry.defaultProvider();
                if (def.isEmpty()) return null;
                providerName = def.get().name();
            }
            providerModel = providerName + "/" + providerModel;
        }
        // The model portion is required. An
        // empty model after the slash would
        // produce a malformed ChatClient.
        int lastSlash = providerModel.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == providerModel.length() - 1) {
            throw new IllegalArgumentException(
                    "model is empty in: " + providerModel);
        }
        return resolver.apply(providerModel);
    }

    /** switch the active provider + model
     *  at runtime. The engine rebuilds its
     *  ChatClient on the fly (the next query uses
     *  the new spec); an in-flight query keeps
     *  using the old ChatClient until it
     *  completes. Returns the new currentProvider
     *  + currentModel so the renderer can update
     *  its picker without a follow-up listProviders
     *  round-trip. */
    @SuppressWarnings("unchecked")
    public Object switchProvider(Object params) {
        Map<String, Object> p = asMap(params);
        String providerName = stringOrThrow(p, "provider");
        String modelId = p.get("model") instanceof String s && !s.isBlank() ? s : null;
        org.aethercode.core.providers.ProviderRegistry reg = providerRegistry;
        if (reg == null) {
            throw new JsonRpcProtocolException(
                    "switchProvider unavailable: provider registry not wired",
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR,
                            "no provider registry; restart with --provider or a providers.yaml"));
        }
        org.aethercode.core.providers.ProviderSpec spec = reg.get(providerName)
                .orElseThrow(() -> new JsonRpcProtocolException(
                        "unknown provider: " + providerName,
                        JsonRpcError.of(JsonRpcError.INVALID_PARAMS,
                                "available: " + reg.list().stream()
                                        .map(org.aethercode.core.providers.ProviderSpec::name)
                                        .toList())));
        if (modelId == null) modelId = spec.defaultModel();
        if (modelId == null || modelId.isBlank()) {
            throw new JsonRpcProtocolException(
                    "no model specified and provider has no default",
                    JsonRpcError.of(JsonRpcError.INVALID_PARAMS,
                            "provider " + providerName + " defaultModel is unset"));
        }
        // build the new ChatClient via
        // resolveChatClient and actually swap it
        // on the engine (the legacy stub only
        // updated the metadata fields —queries
        // still ran on the engine's original
        // client). the legacy version also enables this code
        // path for the per-agent child sessions
        // (the same helper resolves an agent's
        // frontmatter model into a ChatClient).
        try {
            org.aethercode.core.llm.ChatClient newClient =
                    resolveChatClient(providerName + "/" + modelId);
            if (newClient != null) {
                engine.setChatClient(newClient);
                engine.mainLoopModelName(modelId);
            }
        } catch (Exception e) {
            throw new JsonRpcProtocolException(
                    "switchProvider failed to build client: " + e.getMessage(),
                    JsonRpcError.of(JsonRpcError.ENGINE_ERROR, e.getMessage()));
        }
        this.currentProviderName = providerName;
        this.currentModelId = modelId;
        LOG.info("provider switched: {} / {}", providerName, modelId);
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("provider", providerName);
        resp.put("model", modelId);
        return resp;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> asMap(Object params) {
        // null params means "no params" per JSON-RPC 2.0.
        // the legacy dispatcher threw "params object is
        // required" for any no-arg RPC, which broke the TUI's
        // `getState` boot path (the TUI calls getState with
        // no params and got rpc error -32602). Return an
        // empty map so callers can read fields with the
        // usual `p.get(...)` and just see null/missing.
        // The legacy strict mode is preserved for explicit
        // empty-object calls ({}) — those still pass the
        // instanceof check below.
        if (params == null) {
            return java.util.Collections.emptyMap();
        }
        if (!(params instanceof Map)) {
            throw invalidParams("params must be an object, got " + params.getClass().getSimpleName());
        }
        return (Map<String, Object>) params;
    }

    private static String stringOrThrow(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) throw invalidParams("missing required string field: " + key);
        return v.toString();
    }

    private static JsonRpcProtocolException invalidParams(String detail) {
        return new JsonRpcProtocolException("invalid params", JsonRpcError.invalidParams(detail));
    }

    /** Render a {@link StreamEvent} as a plain Map for the wire. */
    private static Map<String, Object> eventToMap(StreamEvent ev) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (ev instanceof StreamEvent.RunStart rs) {
            r.put("type",  "run_start");
            r.put("runId", rs.runId());
            r.put("model", rs.model());
        } else if (ev instanceof StreamEvent.TextDelta td) {
            r.put("type", "text_delta");
            r.put("text", td.text());
        } else if (ev instanceof StreamEvent.ToolUseStart tu) {
            r.put("type",  "tool_use_start");
            r.put("id",    tu.id());
            r.put("name",  tu.name());
            r.put("input", tu.input());
        } else if (ev instanceof StreamEvent.ToolResult tr) {
            r.put("type",    "tool_result");
            r.put("id",      tr.id());
            r.put("content", tr.content());
            r.put("isError", tr.isError());
        } else if (ev instanceof StreamEvent.ToolOutputDelta tod) {
            // streamed tool output. Sent to the desktop so
            // the user sees the command's output live (e.g. an
            // `mvn --version` banner or a long-running `mvn
            // test` log) instead of waiting for the whole tool
            // to complete. The id matches ToolUseStart.id /
            // ToolResult.id of the same call.
            r.put("type", "tool_output_delta");
            r.put("id",   tod.id());
            r.put("text", tod.text());
        } else if (ev instanceof StreamEvent.RunEnd re) {
            r.put("type",       "run_end");
            r.put("stopReason", re.stopReason());
        } else if (ev instanceof StreamEvent.SideNote sn) {
            r.put("type",    "side_note");
            r.put("kind",    sn.kind());
            r.put("message", sn.message());
        } else if (ev instanceof StreamEvent.SubTaskStart sts) {
            r.put("type",     "sub_task_start");
            r.put("taskId",   sts.taskId());
            r.put("subTaskId", sts.subTaskId());
            r.put("content",  sts.content());
            r.put("status",   sts.status());
        } else if (ev instanceof StreamEvent.SubTaskEnd ste) {
            r.put("type",      "sub_task_end");
            r.put("taskId",    ste.taskId());
            r.put("subTaskId", ste.subTaskId());
            r.put("status",    ste.status());
            r.put("summary",   ste.summary());
        } else {
            r.put("type", "unknown");
            r.put("raw",  ev.toString());
        }
        return r;
    }

    /**
     * pick a one-line summary of a tool's input so the
     * trace panel can answer "what did the tool do?" without keeping
     * the whole input around. Returns a short, human-readable
     * string, or {@code null} if no good field is present.
     *
     * <p>Common "intent" fields are tried in tool-appropriate order:
     * file tools prefer {@code file_path} / {@code path};
     * shell-style tools prefer {@code command};
     * search tools prefer {@code pattern} / {@code query}.
     * Truncates to ~120 chars to keep the trace small.
     */
    static String compactToolInput(String toolName, Map<String, Object> input) {
        if (input == null || input.isEmpty()) return null;
        String[] preferred = switch (toolName == null ? "" : toolName) {
            case "read_file", "write_file", "edit_file", "glob", "list_directory" ->
                new String[]{"file_path", "path", "pattern", "directory"};
            case "bash", "shell" -> new String[]{"command", "cmd", "script"};
            case "grep", "search", "code_search" -> new String[]{"pattern", "query", "regex", "path"};
            case "web_fetch", "fetch" -> new String[]{"url", "uri"};
            case "web_search" -> new String[]{"query", "q"};
            default -> new String[]{"file_path", "path", "command", "query", "pattern", "url", "input"};
        };
        for (String k : preferred) {
            Object v = input.get(k);
            if (v instanceof String s && !s.isBlank()) {
                String one = s.replace('\n', ' ').replace('\r', ' ').trim();
                return one.length() > 120 ? one.substring(0, 117) + "..." : one;
            }
        }
        // Fallback: first string-valued entry, joined.
        for (Object v : input.values()) {
            if (v instanceof String s && !s.isBlank()) {
                String one = s.replace('\n', ' ').replace('\r', ' ').trim();
                return one.length() > 120 ? one.substring(0, 117) + "..." : one;
            }
        }
        return null;
    }

    // model context-window introspection.
    // Returns the current provider + model + their
    // context-window size + current usage. The TUI
    // uses this to display "200.0K / 200.0K (100%)"
    // or "200.0K / 1.0M (20%)" depending on the
    // actual model, instead of hardcoding 1M.
    // The endpoint is read-only — no side effects,
    // safe to call from any UI on a timer.
    public Object getContextInfo(Object params) {
        Map<String, Object> p = asMap(params);
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no engine");
        }
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("provider", currentProviderName);
        resp.put("model", currentModelId);
        // Look up the model spec for the context window
        // + max output. Fall back to the appState's
        // currentContextWindow (set via
        // AETHERCODE_MAX_CONTEXT_TOKENS env var on
        // daemon startup).
        int ctxWindow = target.appState().contextWindow();
        int maxOutput = 0;
        if (providerRegistry != null && currentModelId != null) {
            for (org.aethercode.core.providers.ProviderSpec ps : providerRegistry.list()) {
                if (currentProviderName != null && !currentProviderName.equals(ps.name())) continue;
                for (org.aethercode.core.providers.ModelSpec ms : ps.models()) {
                    if (currentModelId.equals(ms.id())) {
                        if (ctxWindow <= 0) ctxWindow = ms.context();
                        maxOutput = ms.maxOutput() > 0 ? ms.maxOutput() : ms.context();
                        break;
                    }
                }
            }
        }
        if (ctxWindow <= 0) ctxWindow = 200_000;  // safe default for unknown models
        resp.put("contextWindow", ctxWindow);
        resp.put("maxOutputTokens", maxOutput);
        // Current usage (best-effort). The transcript
        // length is a proxy for the actual context
        // size — exact char count vs. token count
        // diverges by model, but the TUI just needs
        // a percentage.
        int transcriptChars = 0;
        try {
            for (org.aethercode.core.message.Message m : target.appState().transcript()) {
                for (Object b : m.content()) {
                    if (b instanceof org.aethercode.core.message.ContentBlock.TextBlock t) {
                        transcriptChars += t.text() == null ? 0 : t.text().length();
                    }
                }
            }
        } catch (Exception ignored) {}
        // Rough char-to-token: ~4 chars per token for
        // English. The TUI uses this for the
        // percentage bar; exact is unnecessary.
        int estimatedTokens = transcriptChars / 4;
        resp.put("transcriptChars", transcriptChars);
        resp.put("estimatedTokens", estimatedTokens);
        resp.put("usagePercent", ctxWindow > 0
                ? Math.min(100, (int) (estimatedTokens * 100L / ctxWindow))
                : 0);
        return resp;
    }

    // per-session task summary. The user explicitly
    // asked for "a summary regardless of whether the task
    // ended correctly" — so this RPC always returns the
    // current SessionStats snapshot. Read-only, no side
    // effects. The TUI calls it (a) at the end of every
    // task (in the End-of-task panel) and (b) when the
    // LoopGuardBanner fires (so the user sees a summary
    // even when the engine stopped on a loop-detected).
    // Wire shape:
    //   { ok, sessionId, files_written, files_read,
    //     shell_calls, total_tool_calls, queries,
    //     state, last_error, by_tool: {tool -> count},
    //     summary_text, started_at_ms,
    //     last_activity_at_ms, duration_ms }
    public Object summary(Object params) {
        Map<String, Object> p = asMap(params);
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no engine");
        }
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("ok", true);
        snap.put("sessionId", target.appState().sessionId());
        org.aethercode.sdk.SessionStats stats = target.sessionStats();
        if (stats == null) {
            // Defensive: SessionStats is final on the
            // engine, but a future test could null it.
            // Surface an empty summary so the TUI
            // doesn't crash on undefined.
            snap.put("files_written", 0);
            snap.put("files_read", 0);
            snap.put("shell_calls", 0);
            snap.put("total_tool_calls", 0);
            snap.put("queries", 0);
            snap.put("state", "unknown");
            snap.put("last_error", "");
            snap.put("by_tool", java.util.Map.of());
            snap.put("summary_text", "Session stats not wired.");
            snap.put("started_at_ms", 0L);
            snap.put("last_activity_at_ms", 0L);
            snap.put("duration_ms", 0L);
            return snap;
        }
        // Spread the SessionStats wire snapshot into our
        // response (already a LinkedHashMap with stable
        // key order). The TUI JSON parser sees a flat
        // shape that mirrors SessionStats.toWireSnapshot.
        snap.putAll(stats.toWireSnapshot());
        return snap;
    }

    // explicit compact RPC. The TUI's
    // "Suggest Compact" button can call this to trigger
    // a synchronous compaction. Returns a summary
    // so the UI can show "compacted 50 → 12
    // messages". This wraps the existing
    // R140 pre-flight compact which already runs
    // on every query, but exposes it as a
    // manual trigger.
    public Object compactTranscript(Object params) {
        Map<String, Object> p = asMap(params);
        String sid = p.get("sessionId") instanceof String s && !s.isBlank() ? s : null;
        AetherCodeEngine target = resolveRpcTarget(sid);
        if (target == null) {
            return Map.of("ok", false, "error", "no engine");
        }
        try {
            int before = target.appState().transcript().size();
            boolean didCompact = target.runPreFlightCompact();
            int after = target.appState().transcript().size();
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("compactedFrom", before);
            resp.put("compactedTo", after);
            resp.put("messagesCompacted", before - after);
            resp.put("didCompact", didCompact);
            // Re-compute usage for the UI to update
            // the bar.
            int transcriptChars = 0;
            for (org.aethercode.core.message.Message m : target.appState().transcript()) {
                for (Object b : m.content()) {
                    if (b instanceof org.aethercode.core.message.ContentBlock.TextBlock t) {
                        transcriptChars += t.text() == null ? 0 : t.text().length();
                    }
                }
            }
            resp.put("transcriptChars", transcriptChars);
            return resp;
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // legacy .5 / R149: worktree lifecycle RPCs
    // (Option B). R149 replaces the legacy .5 stub
    // with real `git worktree add` / `git worktree
    // remove` / `git branch -D` invocations. The
    // RPCs now forward `sourceRepo` and `baseBranch`
    // parameters so the user can pick the source
    // repo at add time; `removeWorktree` also takes
    // a `deleteBranch` flag. The manager falls back
    // to the directory-only stub when no source
    // repo is set (logged at WARN so the user knows
    // the worktree isn't real git isolation).
    private final org.aethercode.sdk.WorktreeManager worktreeManager =
            new org.aethercode.sdk.WorktreeManager();

    public Object addWorktree(Object params) {
        Map<String, Object> p = asMap(params);
        String name = p.get("name") instanceof String s && !s.isBlank() ? s : null;
        if (name == null) {
            return Map.of("ok", false, "error", "name required");
        }
        // optional source repo + base
        // branch forwarded to the manager. The
        // nulls are passed through; the manager
        // falls back to the configured sourceRepo
        // (or the env var), and to HEAD when the
        // base branch is null/blank.
        String sourceRepo = p.get("sourceRepo") instanceof String s && !s.isBlank() ? s : null;
        String baseBranch = p.get("baseBranch") instanceof String s && !s.isBlank() ? s : null;
        try {
            org.aethercode.sdk.WorktreeManager.WorktreeState st =
                    worktreeManager.addWorktree(name, sourceRepo, baseBranch);
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("name", st.name());
            resp.put("path", st.path().toString());
            resp.put("branch", st.branch());
            resp.put("sourceRepo", st.sourceRepo());
            return resp;
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public Object removeWorktree(Object params) {
        Map<String, Object> p = asMap(params);
        String name = p.get("name") instanceof String s && !s.isBlank() ? s : null;
        if (name == null) {
            return Map.of("ok", false, "error", "name required");
        }
        // optional deleteBranch flag
        // (default true). When false, the worktree
        // is removed but the branch is left
        // around (useful when the user wants to
        // merge it manually later).
        boolean deleteBranch = true;
        if (p.get("deleteBranch") instanceof Boolean b) deleteBranch = b;
        boolean ok = worktreeManager.removeWorktree(name, deleteBranch);
        return Map.of("ok", ok, "name", name);
    }

    public Object listWorktrees(Object params) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (org.aethercode.sdk.WorktreeManager.WorktreeState st :
                worktreeManager.listWorktrees().values()) {
            out.add(st.toWireSnapshot());
        }
        // also surface the worktrees git
        // already knows about (via `git worktree
        // list --porcelain`). The user might
        // have created a worktree outside
        // AetherCode (e.g. via the git CLI
        // directly) and want to attach a
        // session to it.
        java.util.List<org.aethercode.sdk.WorktreeManager.WorktreeState> gitOnes =
                worktreeManager.listGitWorktrees();
        for (var st : gitOnes) {
            out.add(st.toWireSnapshot());
        }
        return Map.of("ok", true, "worktrees", out);
    }

    // legacy .7 / R150: supervisor RPCs (Option C).
    // legacy .7 shipped the registry stub; R150
    // replaces it with real subprocess management
    // + health-check loop + RPC forwarder. The
    // legacy .7 registerChild (externally-managed
    // child) is preserved as a no-spawn path; the
    // new spawnChild RPC starts a fresh
    // aethercode.jar process via ProcessBuilder
    // and gives the supervisor ownership of the
    // Process handle.
    public Object registerChild(Object params) {
        Map<String, Object> p = asMap(params);
        String childId = p.get("childId") instanceof String s && !s.isBlank() ? s : null;
        Object portObj = p.get("httpPort");
        String cwd = p.get("cwd") instanceof String s ? s : null;
        if (childId == null || !(portObj instanceof Number n)) {
            return Map.of("ok", false, "error", "childId and httpPort required");
        }
        // registerChild now also accepts
        // a `spawn: true` flag. When true, the
        // supervisor starts a real subprocess
        // (via spawnChild) using `jarPath` +
        // `cwd` + `httpPort` from the params.
        // When false or absent, the existing
        // legacy .7 register-by-port path is used.
        Boolean spawn = p.get("spawn") instanceof Boolean b ? b : null;
        if (Boolean.TRUE.equals(spawn)) {
            String jarPath = p.get("jarPath") instanceof String s && !s.isBlank() ? s : null;
            if (jarPath == null) {
                return Map.of("ok", false, "error", "jarPath required when spawn=true");
            }
            @SuppressWarnings("unchecked")
            Map<String, String> envVars = p.get("envVars") instanceof Map
                    ? (Map<String, String>) p.get("envVars")
                    : Map.of();
            try {
                org.aethercode.sdk.SupervisorMode.ChildInfo ci =
                        org.aethercode.sdk.SupervisorMode.instance().spawnChild(
                                childId, cwd, n.intValue(), jarPath, envVars);
                return Map.of("ok", true, "child", ci.toWireSnapshot());
            } catch (Exception e) {
                return Map.of("ok", false, "error", e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        org.aethercode.sdk.SupervisorMode.ChildInfo ci =
                org.aethercode.sdk.SupervisorMode.instance().registerChild(
                        childId, n.intValue(), cwd);
        return Map.of("ok", true, "child", ci.toWireSnapshot());
    }

    public Object unregisterChild(Object params) {
        Map<String, Object> p = asMap(params);
        String childId = p.get("childId") instanceof String s && !s.isBlank() ? s : null;
        if (childId == null) {
            return Map.of("ok", false, "error", "childId required");
        }
        // when `kill: true` (the
        // default for spawned children), the
        // supervisor first kills the
        // subprocess (if any), then
        // unregisters the entry. When
        // `kill: false`, the entry is
        // removed but the subprocess is
        // left running (the caller has
        // external lifecycle ownership).
        boolean kill = true;
        if (p.get("kill") instanceof Boolean b) kill = b;
        if (kill) {
            org.aethercode.sdk.SupervisorMode.instance().killChild(childId, 5_000L);
        }
        boolean ok = org.aethercode.sdk.SupervisorMode.instance().unregisterChild(childId);
        return Map.of("ok", ok, "childId", childId);
    }

    public Object listChildren(Object params) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (org.aethercode.sdk.SupervisorMode.ChildInfo ci :
                org.aethercode.sdk.SupervisorMode.instance().listChildren().values()) {
            out.add(ci.toWireSnapshot());
        }
        return Map.of("ok", true, "children", out);
    }

    // explicit health-check RPC. The
    // heartbeat thread runs in the background;
    // this RPC is a synchronous probe the TUI
    // can call to refresh the status without
    // waiting for the next tick. Returns the
    // updated ChildInfo snapshot.
    public Object healthCheckChild(Object params) {
        Map<String, Object> p = asMap(params);
        String childId = p.get("childId") instanceof String s && !s.isBlank() ? s : null;
        if (childId == null) {
            return Map.of("ok", false, "error", "childId required");
        }
        org.aethercode.sdk.SupervisorMode.ChildHealth h =
                org.aethercode.sdk.SupervisorMode.instance().healthCheck(childId);
        org.aethercode.sdk.SupervisorMode.ChildInfo ci =
                org.aethercode.sdk.SupervisorMode.instance().getChild(childId);
        if (ci == null) {
            return Map.of("ok", false, "error", "child not found: " + childId);
        }
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("health", h.name());
        resp.put("child", ci.toWireSnapshot());
        return resp;
    }

    // auto-restart on/off RPC. The
    // TUI can flip this on after a crash
    // (so the next child is auto-managed)
    // or off to debug the next failure
    // without restart-loop interference.
    public Object setAutoRestart(Object params) {
        Map<String, Object> p = asMap(params);
        Object on = p.get("enabled");
        if (!(on instanceof Boolean b)) {
            return Map.of("ok", false, "error", "enabled (bool) required");
        }
        org.aethercode.sdk.SupervisorMode.instance().setAutoRestart(b);
        return Map.of("ok", true, "enabled", b,
                "autoRestart", org.aethercode.sdk.SupervisorMode.instance().isAutoRestart());
    }

    // forward an RPC to a child daemon.
    // The TUI sends a JSON-RPC 2.0 call to the
    // supervisor; the supervisor forwards the
    // call to the named child's
    // /jsonrpc endpoint and returns the raw
    // response. This is the R150 substitute
    // for a full WS proxy (R150.1 follow-up)
    // — it works for any request/response
    // RPC, but the TUI's live streaming
    // notifications (e.g. transcript_event)
    // still need to be subscribed to the
    // child directly.
    public Object forwardRpc(Object params) {
        Map<String, Object> p = asMap(params);
        String childId = p.get("childId") instanceof String s && !s.isBlank() ? s : null;
        String method = p.get("method") instanceof String s && !s.isBlank() ? s : null;
        if (childId == null || method == null) {
            return Map.of("ok", false, "error", "childId and method required");
        }
        Object rpcParams = p.get("params");
        String resp = org.aethercode.sdk.SupervisorMode.instance().forwardRpc(
                childId, method, rpcParams);
        if (resp == null) {
            return Map.of("ok", false, "error", "child did not respond (network or unhealthy)");
        }
        // Wrap the raw response in an
        // envelope so the TUI can tell it
        // came from the child. The body is
        // the child's verbatim JSON-RPC
        // response — the TUI parses it
        // with the same JSON-RPC client.
        return Map.of("ok", true, "childId", childId, "method", method, "body", resp);
    }

    // proxy a JSON-RPC notification to a
    // child daemon. Notifications are
    // fire-and-forget (no id, no response per
    // the JSON-RPC 2.0 spec). The supervisor
    // POSTs the notification to the child's
    // /jsonrpc endpoint and returns
    // immediately. Used by the TUI for
    // permission_response, loop_ack, and
    // other fire-and-forget calls that need
    // to reach the right child when the TUI
    // itself is connected to the supervisor
    // (not directly to the child).
    //
    // <p>This is a half-step toward a full WS
    // proxy (R155.1 follow-up). Live
    // streaming events (transcript_event /
    // task_event) still require the TUI to
    // connect to the child's own WS — the
    // HTTP notification path doesn't carry
    // server-pushed events. For
    // request/response RPCs, the existing
    // {@link #forwardRpc} is the right call.
    public Object proxyNotification(Object params) {
        Map<String, Object> p = asMap(params);
        String childId = p.get("childId") instanceof String s && !s.isBlank() ? s : null;
        String method = p.get("method") instanceof String s && !s.isBlank() ? s : null;
        if (childId == null || method == null) {
            return Map.of("ok", false, "error", "childId and method required");
        }
        Object rpcParams = p.get("params");
        boolean ok = org.aethercode.sdk.SupervisorMode.instance().proxyNotification(
                childId, method, rpcParams);
        return Map.of("ok", ok, "childId", childId, "method", method);
    }
}

