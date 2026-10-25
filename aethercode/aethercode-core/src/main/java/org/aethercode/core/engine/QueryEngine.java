package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The agent's main loop — drives the conversation forward turn by turn and runs tool calls
 * through the orchestrator.
 *
 * <p>Modelled after the TS {@code query.ts} generator. The structure is:
 *
 * <ol>
 *   <li>Append the user's message to the transcript</li>
 *   <li>Open an LLM stream for the current transcript + system prompt + tool pool</li>
 *   <li>Pull events from the LLM, buffering text and tool_use blocks</li>
 *   <li>Once the LLM yields {@code RunEnd}, hand any tool_use blocks to {@link ToolOrchestrator}</li>
 *   <li>Append tool results to the transcript and loop, unless the LLM emitted no tool_use</li>
 * </ol>
 *
 * <p>Concurrency rule (matching the TS original): model streams run <em>serially</em>. Tools
 * within a single turn can run concurrently if the orchestrator says so, but a fresh LLM call
 * only starts after every tool result from the previous turn is in the transcript.
 */
public class QueryEngine {

    private static final Logger LOG = LoggerFactory.getLogger(QueryEngine.class);

    private final AppState appState;
    // was `private final ChatClient chatClient;`. Made non-final so
    // AetherCodeEngine.setChatClient() can propagate a hot-swap to the
    // query engine. The field is otherwise only mutated in the constructor
    // and from setChatClient; both paths run on the engine's single
    // thread, so the relaxed field is safe (the consumer thread reads
    // the field once per turn at line 459).
    private ChatClient chatClient;
    private final PermissionPolicy permissionPolicy;
    /** per-call token usage is forwarded here. The TUI / desktop
     *  UI reads the cumulative numbers via getMetrics. May be null in
     *  test contexts. */
    private final org.aethercode.core.metrics.MetricsCollector metricsCollector;
    /** per-call token usage is also billed here. May be null. */
    private final org.aethercode.core.cost.CostTracker costTracker;
    private volatile String systemPrompt;
    /** R379: the DEFAULT agent's system-prompt block. When the user
     *  marks an agent as the default, its body is prepended to every
     *  subsequent turn — that is what makes "default agent" a
     *  behaviour rather than a marker nobody reads. Empty when no
     *  default is set. */
    private volatile String defaultAgentBlock = "";
    private volatile String planModeSuffix = "";
    /** per-query memory section. The engine sets this before each
     *  {@link #query(String)} call (with the output of {@code MemoryRecall.render(...)})
     *  and resets it to empty after the stream ends. Combined with the
     *  planModeSuffix to form the full system prompt for the current turn.
     *  Volatile because the engine writes on the main thread and the
     *  QueryEngine Spliterator reads on the same thread (it just needs
     *  to be visible at tryAdvance time). */
    private volatile String memorySection = "";
    private final Consumer<Message> messageSink;
    private final StreamingToolExecutor streamingExecutor;
    private final org.aethercode.core.compact.Compactor compactor;
    private final Consumer<String> sideNoteSink;
    // R283: per-model compact config. The engine reads
    // this every turn to decide whether to compact. Both
    // fields are volatile because {@link #switchModel}
    // can be called from any thread (the Settings
    // picker's onChange is async). The legacy path —
    // reading {@code appState.contextWindow()} — still
    // works when the registry isn't wired (single-provider
    // legacy daemon).
    private volatile org.aethercode.core.providers.ProviderRegistry compactRegistry;
    private volatile String currentProviderName;
    private volatile String currentModelId;
    // R284: pre-compaction snapshot store. When wired
    // (typically by AetherCodeEngine), every runPreFlightCompact
    // writes a JSON snapshot of the live transcript BEFORE the
    // compactor runs so the user can later "View original
    // context" from the MessageList. Null disables snapshotting
    // (legacy / test paths).
    private volatile org.aethercode.core.compact.SnapshotStore snapshotStore;
    private volatile String currentSessionId;
    // hook fired on every user prompt BEFORE the LLM stream
    // starts. Defaults to a no-op so existing callers (TUI, headless
    // --print, tests) see no behavioural change. The AetherCodeEngine
    // installs a hook that detects "no confirmation needed for next N
    // rounds" patterns and arms the skip-confirmation counter.
    private volatile java.util.function.Consumer<String> onUserPrompt = s -> {};
    private final AtomicInteger runCounter = new AtomicInteger();
    /** R719: anchor turn stride. Every K turns the engine
     *  auto-appends a USER-role session-anchor message whose
     *  metadata carries {@code anchor=true}; L4 then
     *  guarantees this message survives any future strip.
     *  Default 5 — a balance between "anchor fires often
     *  enough to refresh project memory" and "anchor doesn't
     *  dominate the transcript budget". Tunable via
     *  {@code AETHERCODE_R719_ANCHOR_STRIDE}. */
    public static final int DEFAULT_ANCHOR_STRIDE = 5;
    private final AtomicInteger anchorTurnCounter = new AtomicInteger();
    /** hard turn cap is gone. Per-todo adaptive control in
     *  {@code TodoRunController} replaces both this cap and the
     *  high-risk tool counter. The field is kept for API compatibility
     *  with any caller that still reads it (returns the legacy value)
     *  but no longer gates the engine. */
    @SuppressWarnings("unused")
    private volatile int maxTurnsPerQuery = -1;
    /** loop detector config. Default window 8 / threshold 3
     *  (i.e. the same tool call fingerprint appearing 3 times in the
     *  last 8 turns is treated as a loop). Pass any non-positive value
     *  to disable the detector. */
    private volatile int loopDetectWindow = 8;
    private volatile int loopDetectThreshold = 3;
    /** per-query loop detector. Set at the start of each query
     *  so prior-session history doesn't bleed in. */
    private volatile ProgressLoopDetector currentLoopDetector;
    /** per-todo adaptive control. Reset at the start of each
     *  query. The engine consults this after every tool batch and
     *  injects synthetic user messages or escalates to the user
     *  when the soft threshold or max-bumps is hit. */
    private final TodoRunController todoController = new TodoRunController();
    /** when the TodoRunController's max-bumps limit is hit, the
     *  engine pauses and emits an {@code AwaitUserDecision} event.
     *  The next {@code query()} call is treated as the user's
     *  continuation decision and prepended with a system note so
     *  the LLM knows it's responding to a decision prompt. */
    private volatile boolean awaitingUserDecision = false;
    private volatile String pendingAwaitSummary = null;

    /** sub-task tracker state. The engine holds a per-query
     *  snapshot of the last-seen sub-task status so it can emit
     *  {@code SubTaskStart} / {@code SubTaskEnd} events only for
     *  transitions (not for every TodoWrite call). Keyed by
     *  {@code "taskIdx:subTaskId"} (both 0-based indices), value
     *  is the last-seen status string. The Spliterator resets this
     *  on every fresh {@link #query} so a new query starts from
     *  scratch. */
    private final java.util.Map<String, String> lastSubTaskStatus = new java.util.HashMap<>();

    /** the most recent "in_progress" sub-task id, formatted as
     *  {@code "taskIdx:subTaskId"} (the same key shape as
     *  {@link #lastSubTaskStatus}). Updated by
     *  {@link #emitSubTaskTransitions} so the StreamingToolExecutor
     *  can tag every tool call with the active sub-task for the
     *  ACCEPT_TASK permission mode. The executor's permission
     *  check then compares the call's subTaskId against this one
     *  to decide whether to ask. */
    private volatile String currentSubTaskKey;

    public QueryEngine(
            AppState appState,
            ChatClient chatClient,
            PermissionPolicy permissionPolicy,
            String systemPrompt,
            Consumer<Message> messageSink
    ) {
        this(appState, chatClient, permissionPolicy, systemPrompt, messageSink,
                new StreamingToolExecutor(permissionPolicy, 8), null, null);
    }

    public void setPlanModeSuffix(String suffix) { this.planModeSuffix = suffix == null ? "" : suffix; }

    /** install a hook fired on every user prompt BEFORE the LLM
     *  stream starts. The engine uses this to auto-detect
     *  "no confirmation needed for next N rounds" patterns. Pass
     *  {@code null} to clear. */
    public void setOnUserPrompt(java.util.function.Consumer<String> hook) {
        this.onUserPrompt = hook == null ? s -> {} : hook;
    }

    /** hard turn cap is no longer enforced. Per-todo adaptive control
     *  in {@code TodoRunController} replaces both this cap and the
     *  high-risk tool counter. The setter is kept as a no-op for
     *  backward compatibility with existing callers. */
    public void setMaxTurnsPerQuery(int max) { this.maxTurnsPerQuery = max; }

    /** hot-swap the LLM client. Used by
     *  {@code AetherCodeEngine.setChatClient} so the
     *  next query uses the new client without a
     *  full engine rebuild. Production callers
     *  should go through AetherCodeEngine —
     *  direct callers need to re-apply any
     *  per-client appState / subagentEngine wiring
     *  themselves. */
    public void setChatClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }
    /** R697: getter for the chat client. Eval module uses this to call
     *  the chat client directly without going through query() (which
     *  sends a 421KB system prompt that MiniMax rejects with 400). */
    public ChatClient chatClient() {
        return chatClient;
    }
    public int maxTurnsPerQuery() { return maxTurnsPerQuery; }
    public String planModeSuffix() { return planModeSuffix; }

    /** configure the loop detector. Pass 0 / negative to
     *  disable the detector. Default window 8 / threshold 3. */
    public void setLoopDetector(int window, int threshold) {
        if (window < 0 || threshold < 0) {
            this.loopDetectWindow = -1;
            this.loopDetectThreshold = -1;
        } else {
            this.loopDetectWindow = window;
            this.loopDetectThreshold = threshold;
        }
    }
    public int loopDetectWindow() { return loopDetectWindow; }
    public int loopDetectThreshold() { return loopDetectThreshold; }

    /** force a per-query complex-task loop detector. When
     *  {@code true}, the engine will use
     *  {@link ProgressLoopDetector#forComplexTask()} (window 20,
     *  fingerprint threshold 5, long-output threshold 5000,
     *  warn-before-stop 4) instead of the default
     *  window/threshold combo. The engine still auto-detects
     *  complex tasks from prompt length / file-write mentions,
     *  so most callers don't need to set this explicitly. The
     *  RAG end-to-end driver sets it to {@code true} via
     *  {@code AETHERCODE_LOOP_DETECTOR=complex} env var (see
     *  {@code Main.java}).
     *
     *  <p>R136.4: extended with a "max" mode. Set via
     *  {@code AETHERCODE_LOOP_DETECTOR=max} to get the
     *  1M-context factory
     *  ({@link ProgressLoopDetector#forMaxContext()}, window
     *  50, fingerprint 10, longOut 100K, longConsec 3,
     *  warnBeforeStop 8). Recommended for million-token
     *  models (MiniMax M3, Gemini 2.5 Pro) where a single
     *  thinking turn can legitimately produce 80-100K chars. */
    private volatile boolean forceComplexTaskDetector = false;
    /** R136.4: which detector mode {@link #forceComplexTaskDetector}
     *  picks. Values: "default" (engine heuristic), "complex"
     *  (R134 forComplexTask), "max" (R136.4 forMaxContext). */
    private volatile String forcedDetectorMode = "default";
    public void setForceComplexTaskDetector(boolean v) { this.forceComplexTaskDetector = v; }
    public boolean forceComplexTaskDetector() { return forceComplexTaskDetector; }

    /** pick the loop-detector configuration for this query.
     *  Returns the complex-task detector when the user prompt
     *  looks like a multi-file project OR the engine was
     *  configured to always use it. Otherwise returns the
     *  default window/threshold combo.
     *
     *  <p>The auto-detect rules are intentionally conservative:
     *  we'd rather scale up too often than scale up too late
     *  (a hard stop mid-file is much worse than a slightly
     *  less aggressive detector on a short prompt).
     *
     *  <p>Rules:
     *  <ul>
     *    <li>prompt length &gt; 1500 chars (covers the RAG
     *        "create N files" prompts which are 1300-1600 chars)
     *    <li>prompt contains "N files" or "N+ files" where N &gt;= 3
     *    <li>prompt contains the literal "RAG" or "module" or
     *        "project" (RAG driver heuristic)
     *    <li>{@code forceComplexTaskDetector} is set
     *  </ul>
     *
     *  <p>Package-private so {@code ProgressLoopDetectorR134Test}
     *  can exercise the heuristic directly without spinning up
     *  a full engine.
     */
    ProgressLoopDetector pickLoopDetector(String userInput) {
        // R136.4: forced mode (env var AETHERCODE_LOOP_DETECTOR
        // is wired in DaemonRunner + Main) wins over heuristic.
        if (forceComplexTaskDetector || "complex".equals(forcedDetectorMode)) {
            return ProgressLoopDetector.forComplexTask();
        }
        if ("max".equals(forcedDetectorMode)) {
            return ProgressLoopDetector.forMaxContext();
        }
        // R136.4: if the model's context window is >= 500K
        // (MiniMax M3, Gemini 2.5 Pro), auto-pick the max
        // detector. This is a safer default than "complex"
        // because 100K thinking turns are routine on a
        // 1M-context model and the forComplexTask thresholds
        // (10K longOut) would trip too often.
        if (currentContextWindow >= 500_000) {
            return ProgressLoopDetector.forMaxContext();
        }
        if (userInput != null && looksLikeComplexTask(userInput)) {
            return ProgressLoopDetector.forComplexTask();
        }
        return ProgressLoopDetector.builder()
                .window(loopDetectWindow)
                .fingerprintThreshold(loopDetectThreshold)
                .build();
    }
    /** R136.4: setter for the forced detector mode
     *  ({@code "default" / "complex" / "max"}). Wired
     *  by DaemonRunner from the
     *  {@code AETHERCODE_LOOP_DETECTOR} env var. */
    public void setForcedDetectorMode(String mode) {
        this.forcedDetectorMode = mode == null ? "default" : mode.trim().toLowerCase();
    }
    public String forcedDetectorMode() { return forcedDetectorMode; }
    /** R136.4: track the current model's context window
     *  so {@link #pickLoopDetector} can auto-select the
     *  right factory. */
    private volatile int currentContextWindow = 0;
    public void setContextWindow(int tokens) { this.currentContextWindow = tokens; }

    static boolean looksLikeComplexTask(String s) {
        if (s == null) return false;
        if (s.length() > 1500) return true;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("rag") || lower.contains("module") || lower.contains("project")) {
            // Only count if the prompt also mentions file/structure/utility
            // markers — a casual "module" reference shouldn't trigger.
            return lower.contains("file") || lower.contains("class")
                    || lower.contains("function") || lower.contains("import")
                    || lower.contains("test");
        }
        // Multi-file markers: "N files" or "N+ files" where N >= 3.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\+?\\s*files?")
                .matcher(lower);
        while (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 3) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    /** decide whether a tool result is a "progress signal"
     *  that should reset the loop detector's tier. Returns true
     *  for the well-known success patterns from the built-in
     *  tools that prove the model is making real progress
     *  (writing files, running commands successfully, etc.).
     *
     *  <p>False positives are harmless (reset tier to 0). False
     *  negatives miss an opportunity to extend the run. We
     *  err on the side of the call site to be conservative —
     *  a single tool_result of the right shape is enough.
     */
    /**
     * pull the streamed text out of a tool progress message.
     * BashTool emits {@code Message.assistantText("[out] line")} per
     * stdout/stderr line; the text is in the first TextBlock. Returns
     * {@code null} if the message is null or has no text content, so
     * the caller can early-out and avoid sending an empty
     * ToolOutputDelta (which the desktop would have to filter
     * again).
     */
    private static String progressText(Message m) {
        if (m == null || m.content() == null) return null;
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock tb) {
                String t = tb.text();
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return null;
    }

    private static boolean isProgressSignal(String toolName, Object output) {
        if (toolName == null || output == null) return false;
        String out = String.valueOf(output);
        // file_write / file_edit / file_create: "wrote N bytes" / "ok"
        if ("file_write".equals(toolName) || "file_edit".equals(toolName)
                || "file_create".equals(toolName) || "write_file".equals(toolName)) {
            return out.startsWith("wrote ") || out.startsWith("ok")
                    || out.contains(" bytes to ");
        }
        // shell / bash: R136.3 — tighten the "this is
        // progress" heuristic so a `bash echo hello`
        // / `bash cd /tmp` / `bash java -version` exit 0
        // doesn't reset the loop detector's tier. The
        // R134 behaviour was: any non-error bash counts
        // as progress if its output contains "wrote",
        // "ok", "test session starts", or "passed".
        // That's too permissive — `bash echo hello`
        // produces `(exit 0)\n--- stdout ---\nhello`
        // which contains none of those, so it actually
        // was already a no-op. But a real bash like
        // `mvn clean install` that just says "BUILD
        // SUCCESS" without "wrote" / "ok" would
        // silently bump the loop detector's tier via
        // the "ok" substring of the word. Be strict:
        // require a real semantic marker.
        if ("bash".equals(toolName) || "shell".equals(toolName)
                || "exec".equals(toolName) || "run_command".equals(toolName)) {
            // Real "I just did something" signals:
            // - "wrote N bytes" / "wrote ... to <path>"
            //   (file_write style bash heredoc, sed -i, etc.)
            // - "BUILD SUCCESS" (maven)
            // - "test session starts" (pytest collection)
            // - "passed" or "failed" (pytest final status)
            // - exit-0 + at least one line of non-trivial
            //   stdout (> 50 chars excluding the
            //   "(exit 0)\n--- stdout ---\n" wrapper).
            // The wrapper heuristic is what catches
            // `mvn clean install` (multi-KB output) but
            // still rejects `bash echo hello` (just "hello").
            if (out.contains("wrote ") || out.contains("BUILD SUCCESS")
                    || out.contains("test session starts") || out.contains("passed")
                    || out.contains("failed") || out.contains("BUILD FAILURE")) {
                return true;
            }
            // Generic "real work" heuristic: a non-trivial
            // stdout payload. The BashTool wrapper is
            // "(exit 0)\n--- stdout ---\n<body>\n--- stderr ---\n".
            // We count characters between "--- stdout ---\n"
            // and "\n--- stderr ---" (or end of output).
            int sIdx = out.indexOf("--- stdout ---\n");
            if (sIdx >= 0) {
                int eIdx = out.indexOf("--- stderr ---", sIdx);
                int bodyLen = (eIdx > sIdx ? eIdx : out.length()) - sIdx - "--- stdout ---\n".length();
                if (bodyLen > 100) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** high-risk tool set is gone. The old "sensitive command
     *  count limit" feature has been replaced by per-todo adaptive
     *  control. The setter is kept as a no-op for compatibility. */
    public void setHighRiskTools(java.util.Set<String> tools) { /* no-op */ }
    public java.util.Set<String> highRiskTools() { return java.util.Set.of(); }

    /** configure the per-todo adaptive control thresholds. */
    public void setTodoControl(int initialSoftThreshold, int bumpIncrement, int maxBumpsBeforeUser) {
        todoController.configure(initialSoftThreshold, bumpIncrement, maxBumpsBeforeUser);
    }
    public boolean isAwaitingUserDecision() { return awaitingUserDecision; }
    public String pendingAwaitSummary() { return pendingAwaitSummary; }
    /** clear the awaiting state. The renderer can call this
     *  to dismiss a stale prompt (e.g. after the user starts a
     *  fresh query while the old decision prompt is still up). */
    public void clearAwaitingUserDecision() {
        awaitingUserDecision = false;
        pendingAwaitSummary = null;
    }

    /** R283: install the provider registry used to
     *  look up per-model compact configs. Idempotent
     *  — safe to call repeatedly when the daemon
     *  reloads providers.yaml. */
    public void setCompactRegistry(
            org.aethercode.core.providers.ProviderRegistry registry,
            String currentProviderName,
            String currentModelId) {
        this.compactRegistry = registry;
        this.currentProviderName = currentProviderName;
        this.currentModelId = currentModelId;
    }

    /** R283: update just the (provider, model) the
     *  engine is currently driving. Called from
     *  {@code AetherCodeMethods.switchProvider} after
     *  a successful hot-swap so the next
     *  {@link #runPreFlightCompact()} consults the
     *  new model's compact config. */
    public void setCurrentModel(String providerName, String modelId) {
        this.currentProviderName = providerName;
        this.currentModelId = modelId;
    }

    /** R284: install the pre-compaction snapshot store and
     *  the session id it should attribute snapshots to.
     *  Idempotent — safe to call again on hot-reload.
     *  Pass {@code null} to disable snapshotting (used by
     *  tests that don't want disk side effects). */
    public void setSnapshotStore(
            org.aethercode.core.compact.SnapshotStore store,
            String sessionId) {
        this.snapshotStore = store;
        this.currentSessionId = sessionId;
    }

    /** R284: update the session id without rebuilding the
     *  store. Called when the engine switches sessions mid-run
     *  (e.g. {@code AetherCodeMethods.switchSession}). */
    public void setSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }

    /** R379: the session id token usage should be attributed to.
     *  {@code appState} is the live source of truth (it is updated
     *  by loadSession / createSession); the snapshot store's copy is
     *  the fallback for the boot window before a session is known.
     *  May be null — the tracker buckets unknown usage under a
     *  stable key rather than dropping it. */
    private String costSessionId() {
        String s = appState == null ? null : appState.sessionId();
        if (s == null || s.isBlank()) s = currentSessionId;
        return s;
    }

    /** R284: accessor used by the RPC layer so a
     *  {@code compact/getSnapshot} call can resolve the same
     *  store the engine itself wrote to. */
    public org.aethercode.core.compact.SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    /** R283: resolve the compact config for the
     *  currently-active model. Three-way lookup:
     *  <ol>
     *    <li>registry.compactFor(provider, model) —
     *        the per-model declaration</li>
     *    <li>appState.contextWindow() — the legacy
     *        CLI-flag path (when the daemon was booted
     *        with --context-window but no registry)</li>
     *    <li>null — neither set; caller skips</li>
     *  </ol> */
    private org.aethercode.core.compact.CompactConfig resolveCompactConfig() {
        if (compactRegistry != null
                && currentProviderName != null
                && currentModelId != null) {
            var spec = compactRegistry.get(currentProviderName);
            if (spec.isPresent()) {
                return spec.get().compactFor(currentModelId);
            }
        }
        // legacy boot path — fall back to the
        // --context-window flag value. R720-A2:
        // pre-R720 this returned null when the boot
        // path didn't set a window, which short-
        // circuited runPreFlightCompact() to a
        // silent no-op. The result: l5Compacts
        // stayed at 0 across every observed run
        // (RAG Day 1-3, ~663M input tokens, 0
        // L5 fires) — every other L1-L4 layer
        // was doing work, but L5 was completely
        // dormant because the gate config itself
        // was never resolved. Fall back to
        // AutoCompact.DEFAULT_EFFECTIVE_CAP_TOKENS
        // (200K) when the boot path didn't set
        // anything, so a vanilla daemon still
        // engages L5 at the default 0.4 fraction
        // (= 80K tokens). The matching log line
        // helps debugging: a "ctx=0" line on
        // every compact would otherwise be a
        // mystery.
        int ctx = appState.contextWindow();
        if (ctx > 0) {
            return org.aethercode.core.compact.CompactConfig.forContextWindow(ctx);
        }
        LOG.info("R720-A2: appState.contextWindow()=0 — falling back to "
                + "AutoCompact.DEFAULT_EFFECTIVE_CAP_TOKENS=200_000. "
                + "Set a per-model contextWindow on the boot path (CLI "
                + "AETHERCODE_MAX_CONTEXT_TOKENS) to override.");
        return org.aethercode.core.compact.CompactConfig.forContextWindow(200_000);
    }

/** pre-flight auto-compact. Run BEFORE the
     *  The default 90% is generous (the user has some
     *  room to keep typing) but tight enough to avoid
     *  the "context exceeded, request too large" 400
     *  from upstream APIs.
     *
     *  <p>Idempotent — safe to call on every query().
     *  When the compactor is null or the circuit is
     *  open, this is a no-op. The compact is
     *  synchronous (a single LLM call); the caller
     *  emits a SideNote so the TUI can show a
     *  "compacting..." spinner.
     *
     *  <p>R283: per-model configuration. The
     *  contextWindow / compactAt / preserveTail /
     *  strategy come from the {@link
     *  org.aethercode.core.providers.CompactConfig} of
     *  the currently-active model (looked up via
     *  {@link #setCompactRegistry}). When the user
     *  switches models mid-session, the new thresholds
     *  apply on the next query — no daemon restart
     *  needed.
     *
     *  <p>Env overrides:
     *  <ul>
     *    <li>{@code AETHERCODE_COMPACT_THRESHOLD} —
     *        fraction override on the model's
     *        {@code compactAt} (e.g. 0.7 to compact
     *        earlier). Default 1.0 (use the model's
     *        own value).</li>
     *    <li>{@code AETHERCODE_COMPACT_TAIL} —
     *        override the model's {@code preserveTail}.
     *        Default 0 (use the model's own value).</li>
     *  </ul>
     */
    public void runPreFlightCompact() {
        // R720-trace: forced stderr log so we can confirm the
        // hot path is reached regardless of the daemon's
        // SLF4J / JUL config. Stays in production — the
        // R720 / R719 lesson is that silent no-ops here are
        // the single biggest reason {@code l5Compacts} stays
        // at 0. The line is one tag + three fields, ~120
        // chars, well under any reasonable log-rate cap.
        System.err.println("R720-trace: runPreFlightCompact enter: "
                + "compactor=" + (compactor == null ? "null" : compactor.getClass().getSimpleName())
                + ", appState.contextWindow=" + appState.contextWindow()
                + ", transcriptSize=" + appState.transcript().size());
        if (compactor == null) return;
        // R283: pull the per-model config. Falls back to
        // appState.contextWindow() (legacy boot path) when
        // the engine has no provider registry wired.
        org.aethercode.core.compact.CompactConfig cfg =
                resolveCompactConfig();
        if (cfg == null) {
            LOG.warn("R720-debug: runPreFlightCompact: cfg=null "
                    + "(provider=" + currentProviderName + ", model="
                    + currentModelId + ", appState.contextWindow="
                    + appState.contextWindow() + ", transcriptSize="
                    + appState.transcript().size() + ") — gate not engaged");
            return;
        }
        int ctxWindow = cfg.contextWindow();
        if (ctxWindow <= 0) {
            LOG.warn("R720-debug: runPreFlightCompact: cfg.contextWindow="
                    + ctxWindow + " (provider=" + currentProviderName
                    + ", model=" + currentModelId + ") — gate not engaged");
            return;
        }
        // R720-A1: wire-side pre-flight gate UNIFICATION with
        // {@link org.aethercode.compact.AutoCompact#shouldCompact()}.
        //
        // Prior to this round, the pre-flight gate used the absolute
        // value {@code cfg.compactAt()} (default 184_000 tokens =
        // 92% of a 200K context window) while {@code AutoCompact}
        // used {@code effectiveCap * compactAtFraction} (R719
        // DEFAULT_COMPACT_AT = 0.4 → 80_000 tokens on a 200K window).
        // The two trigger bands were ~2.3x apart, and {@code
        // l5Compacts} stayed at 0 across 17 RAG-Day-2 turns even
        // though every turn was well past the intended 80K-token gate:
        // the pre-flight gate always returned false before the
        // compactor's own threshold had a chance to fire.
        //
        // Default fraction = 0.4 (200K * 0.4 = 80K tokens). Env
        // overrides (set before daemon boot):
        //   AETHERCODE_COMPACT_THRESHOLD        — full fraction override
        //                                         (e.g. 0.5 for "compact
        //                                         at 50% of context window")
        //   AETHERCODE_R720_COMPACT_FRACTION   — same shape, R720 explicit
        //                                         (so a future R-number
        //                                         can change defaults
        //                                         without breaking R283's
        //                                         contract)
        //   AETHERCODE_R720_USE_ABSOLUTE_GATE=1
        //                                       — legacy behaviour
        //                                         (use cfg.compactAt()
        //                                         directly). Off by
        //                                         default; flip on if
        //                                         a provider.yaml
        //                                         declares an
        //                                         off-band compactAt
        //                                         the user wants
        //                                         honoured as-is.
        long gate;
        String envThreshold = System.getenv("AETHERCODE_COMPACT_THRESHOLD");
        String envFraction = System.getenv("AETHERCODE_R720_COMPACT_FRACTION");
        String useAbsolute = System.getenv("AETHERCODE_R720_USE_ABSOLUTE_GATE");
        if ("1".equals(useAbsolute)) {
            gate = cfg.compactAt();
        } else if (envThreshold != null && !envThreshold.isBlank()) {
            double threshold;
            try { threshold = Double.parseDouble(envThreshold.trim()); }
            catch (NumberFormatException ignored) { threshold = -1; }
            if (threshold > 0) {
                gate = (long) (ctxWindow * threshold);
            } else {
                gate = (long) (ctxWindow * 0.4);
            }
        } else if (envFraction != null && !envFraction.isBlank()) {
            double fraction;
            try { fraction = Double.parseDouble(envFraction.trim()); }
            catch (NumberFormatException ignored) { fraction = 0.4; }
            if (fraction <= 0) fraction = 0.4;
            gate = (long) (ctxWindow * fraction);
        } else {
            // R720: default 0.4 = 80K tokens on a 200K window —
            // matches AutoCompact.DEFAULT_COMPACT_AT (R719). Both
            // backends fire at the same point now.
            gate = (long) (ctxWindow * 0.4);
        }
        long current = estimateTranscriptTokens();
        if (current < gate) return;
        var messages = new java.util.ArrayList<>(appState.transcript());
        if (!compactor.shouldCompact(messages)) return;
        // emit a structured user-role note so the
        // TUI transcript shows "compacting..." with
        // counts. We use messageSink (Consumer<Message>)
        // rather than the StreamEvent action so the
        // note is part of the durable transcript.
        try {
            // R283: the note now shows the model's tier
            // (summary8 / summary7 / sliding) and the
            // preserveTail count so the user can see
            // exactly which knobs fired.
            String note = "R283 pre-flight compact: " + messages.size() + " msgs / "
                    + current + " tokens (window=" + ctxWindow
                    + ", compactAt=" + gate
                    + ", strategy=" + cfg.strategy().wire()
                    + ", preserveTail=" + cfg.preserveTail() + ")";
            java.util.logging.Logger.getLogger(QueryEngine.class.getName())
                    .info(note);
            Message noteMsg = new Message(
                    "compact-" + System.currentTimeMillis(),
                    org.aethercode.core.message.Role.USER,
                    List.of(new ContentBlock.TextBlock("[compaction] " + note)),
                    null,
                    java.util.Map.of("kind", "compaction-side-note"));
            // Surface the note to the message sink (TUI
            // transcript) but DON'T append it to the
            // appState transcript — otherwise the next
            // run sees the note as another turn to
            // summarise.
            messageSink.accept(noteMsg);
        } catch (Exception ignored) {}
        var spliced = compactor.compact(messages);
        if (spliced != null && spliced != messages) {
            // R284: snapshot the pre-compaction context BEFORE
            // we mutate appState.transcript. We do this after
            // the compactor has produced the summary so the
            // snapshot carries the same summary text the model
            // saw — when the user clicks "View original" in
            // the MessageList, they get both the original
            // turns AND the summary that replaced them.
            org.aethercode.core.compact.SnapshotStore.Snapshot snap = null;
            try {
                if (snapshotStore != null && currentSessionId != null
                        && !currentSessionId.isBlank()) {
                    String summary = extractSummaryFromSpliced(spliced);
                    snap = snapshotStore.saveForSession(
                            currentSessionId,
                            messages.size(),
                            summary,
                            messages);
                    spliced = attachCompactionMetadata(spliced, snap);
                }
            } catch (Exception snapshotEx) {
                // Snapshotting is best-effort. A failed write
                // must not break the user's turn.
                java.util.logging.Logger.getLogger(QueryEngine.class.getName())
                        .warning("R284 snapshot save failed: " + snapshotEx.getMessage());
            }
            appState.transcript().clear();
            for (var mm : spliced) appState.appendMessage(mm);
            try {
                String done = "R140 compacted: " + messages.size() + " -> " + spliced.size() + " msgs";
                if (snap != null) {
                    done += " (snapshot=" + snap.sessionId() + "__" + snap.compactionIndex() + ")";
                }
                java.util.logging.Logger.getLogger(QueryEngine.class.getName())
                        .info(done);
                Message doneMsg = new Message(
                        "compact-done-" + System.currentTimeMillis(),
                        org.aethercode.core.message.Role.USER,
                        List.of(new ContentBlock.TextBlock("[compaction] " + done)),
                        null,
                        java.util.Map.of("kind", "compaction-side-note"));
                messageSink.accept(doneMsg);
            } catch (Exception ignored) {}
        }
    }

    /**
     * R284: pull the summary text out of the spliced list.
     * The synthetic summary message is the FIRST entry and
     * carries {@code metadata["summary"] = true}. We strip
     * the "[Conversation compacted — ...]" prefix
     * {@link org.aethercode.compact.CompactGate#spliceSummary}
     * prepends so the snapshot's {@code summary} field holds
     * the bare model output — useful for audit and for the
     * desktop "diff" view that compares the original context
     * with the summary that replaced it.
     */
    private static String extractSummaryFromSpliced(List<Message> spliced) {
        if (spliced == null || spliced.isEmpty()) return "";
        Message first = spliced.get(0);
        if (first.metadata() == null
                || !Boolean.TRUE.equals(first.metadata().get("summary"))) {
            return "";
        }
        String text = first.textContent();
        int sep = text.indexOf("\n\n");
        if (sep < 0) return text;
        return text.substring(sep + 2);
    }

    /**
     * R284: replace the spliced summary message's metadata
     * with the compaction bookkeeping (compactionIndex +
     * snapshotPath + originalCount). The renderer's MessageList
     * watches for {@code kind == "compaction-summary"} on a
     * message and renders a "View original (N msgs)" affordance
     * that calls {@code compact/getSnapshot} against the same
     * sessionId + index.
     */
    private static List<Message> attachCompactionMetadata(
            List<Message> spliced,
            org.aethercode.core.compact.SnapshotStore.Snapshot snap) {
        if (spliced == null || spliced.isEmpty() || snap == null) return spliced;
        Message first = spliced.get(0);
        java.util.Map<String, Object> base = first.metadata() != null
                ? new java.util.LinkedHashMap<>(first.metadata())
                : new java.util.LinkedHashMap<>();
        base.put("kind", "compaction-summary");
        base.put("compactionIndex", snap.compactionIndex());
        base.put("snapshotPath", snap.fileName());
        base.put("originalCount", snap.originalMessageCount());
        Message updated = new Message(
                first.id(), first.role(), first.content(),
                null, base);
        java.util.List<Message> out = new java.util.ArrayList<>(spliced);
        out.set(0, updated);
        return out;
    }

    /** rough token estimate of the live transcript
     *  using the same 4-chars-per-token heuristic the
     *  compactor uses. The estimate is for the
     *  pre-flight gate only; the compactor's own
     *  {@code shouldCompact} is the source of truth. */
    /**
     * R716: strip orphan tool results from a transcript before
     * handing it to a chat client. Some providers (notably
     * MiniMax-M3's OpenAI-compat endpoint) strictly validate that
     * every {@code role=TOOL_RESULT} message carries a
     * {@code tool_call_id} that appears in a prior assistant
     * message's {@code tool_calls}. The on-disk transcript can
     * drift out of sync with that invariant (e.g. an assistant
     * message's content blocks are truncated during schema-migration
     * recovery, or a session is restored after a content-block type
     * was renamed), at which point the LLM API returns
     * {@code 400 "tool result's tool id(call_X) not found"}.
     *
     * <p>This helper is intentionally a pure filter over the input
     * list — it does NOT mutate {@code appState.transcript()}. The
     * returned list is a new {@code ArrayList} when any orphan
     * entries are dropped, or the same {@code List} reference when
     * the transcript is already clean (the common case), so the
     * engine's snapshot / recovery / compaction paths see the
     * authoritative view.
     *
     * <p>An entry is "orphan" when:
     * <ul>
     *   <li>it is a {@code TOOL_RESULT} message, AND</li>
     *   <li>its {@code tool_use_id} (taken from
     *       {@code metadata.tool_use_id} for the {@code Message}
     *       side, or the {@code toolUseId} field of any
     *       {@code ToolResultBlock} content) is not present in the
     *       set of {@code id} fields from any assistant
     *       {@code ToolUseBlock} seen earlier in the same list.</li>
     * </ul>
     *
     * <p>Plain {@code user}/{@code assistant}/{@code system}
     * messages always pass through. The set of {@code tool_use_id}s
     * is built up scan-order, so a tool_use followed by a tool_result
     * is matched correctly, and a tool_result whose tool_use appears
     * later in the list is dropped (the API will reject the latter
     * case too — it requires strict ordering).
     */
    public static List<Message> stripOrphanToolResults(List<Message> transcript) {
        if (transcript == null || transcript.isEmpty()) return transcript;

        // R736 (rev2, 2026-10-20). The R716 rule ("drop a tool_result
        // whose tool_use never appeared") plus the first R736 attempt
        // ("drop a tool_use whose tool_result never appeared") were
        // both ID-EXISTENCE checks. The live provider's contract is
        // stronger: an assistant message that declares N tool_calls
        // must be followed by EXACTLY N tool_result messages whose
        // ids are precisely the declared set, in some order, with no
        // interleaving user/system messages and no off-batch
        // tool_results pulled in from anywhere else. We proved three
        // failure shapes against the live endpoint on 2026-10-20:
        //
        //   assistant(tc=[c1,c2]) + tool(c1)             -> 500 (c2 missing)
        //   assistant(tc=[c1,c2,c3]) + tool(c1)+tool(c3)  -> 500 (c2 missing)
        //   assistant(tc=[c1]) + tool(c1)+tool(c2)        -> 500 (c2 alien)
        //
        // The R716 / first-R736 pass let all three survive because
        // every tool_result present could be reverse-mapped to SOME
        // prior tool_use, and vice versa. The invisible defect in the
        // polluting RAG transcript turned out to be exactly this
        // adjacency shape: an assistant declared 4 calls but only 2
        // results landed contiguously; the batch boundary was at the
        // wrong place.
        //
        // Algorithm: scan forward in a single pass, treating an
        // assistant (with declared tool_use ids) as the START of a
        // "call batch". Collect every immediately-following tool_result
        // into a set, stopping at the first non-tool-result message.
        // At batch end:
        //   - declared ids absent from the answered set  -> dangling
        //   - answered ids absent from the declared set  -> alien
        // Strip dangling tool_use blocks from the assistant (preserving
        // its prose, dropping the whole message if the prose goes too);
        // emit alien tool_results as drops under the R716 banner.
        // Orphan tool_results (no preceding assistant at all) remain
        // the R716 case and are dropped with the existing warn.
        // Fast path: when no repair is needed, return the same list.

        int n = transcript.size();

        // Phase 1 — index declared ids per assistant index.
        // declaredIds[i] is non-empty ONLY for assistant messages that
        // carry tool_use blocks; empty for every other role.
        List<List<String>> declaredIds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Message m = transcript.get(i);
            List<String> ids = new ArrayList<>();
            if (m != null && m.role() == Role.ASSISTANT) {
                for (ContentBlock b : m.content()) {
                    if (b instanceof ContentBlock.ToolUseBlock tu
                            && tu.id() != null && !tu.id().isBlank()) {
                        ids.add(tu.id());
                    }
                }
            }
            declaredIds.add(ids);
        }

        // Phase 2 — for each batch, decide which declared ids were
        // ACTUALLY answered contiguously, and which tool_results were
        // ALIEN (id answers some other assistant earlier, or no
        // assistant at all). Per i we record:
        //   danglingIdxs[i] = list of ToolUseBlock positions inside the
        //                     assistant message at i whose id went
        //                     unanswered by this batch (or by anything).
        //   alienIdx[i]    = true when the message at i is a tool_result
        //                     whose id belongs to a DIFFERENT assistant
        //                     (not the one that started the current
        //                     batch), or to no assistant at all.
        // Forward pass: when we see an assistant with declared ids, we
        // open a batch; while we keep seeing tool_results immediately
        // after, we record them as answered-by-this-batch; the first
        // non-tool_result closes the batch. Then we mark any declared
        // id not collected as dangling, and any collected id not
        // declared as alien (these answered tool_results get dropped).
        List<List<Integer>> danglingIdxs = new ArrayList<>(n);
        boolean[] alienIdx = new boolean[n];
        for (int i = 0; i < n; i++) danglingIdxs.add(null);

        // ownerOf(id) = index of the assistant that DECLARED id; built
        // greedily with "nearest preceding" semantics — matches the
        // answerer's intent in any sane transcript and survives the
        // shape where an earlier assistant's tool_result bleeds past
        // a later tool_use declaration by being marked alien.
        Map<String, Integer> ownerOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<String> dec = declaredIds.get(i);
            if (dec.isEmpty()) continue;
            for (String id : dec) {
                ownerOf.putIfAbsent(id, i); // first declaration wins
            }
        }

        int i0 = 0;
        while (i0 < n) {
            List<String> dec = declaredIds.get(i0);
            if (dec.isEmpty()) { i0++; continue; }
            // Open batch at i0. Collect up to N tool_results (one per
            // declared id is the max sane; the helper allows exactly
            // dec.size()). Anything beyond is a separate batch or a
            // stray.
            List<String> batchAnswered = new ArrayList<>();
            Set<String> batchAnsweredSet = new HashSet<>();
            int j = i0 + 1;
            while (j < n && batchAnswered.size() < dec.size()) {
                Message mj = transcript.get(j);
                if (mj == null || mj.role() != Role.TOOL_RESULT) break;
                String id = toolUseIdOf(mj);
                if (id == null || id.isBlank()) break;
                batchAnswered.add(id);
                batchAnsweredSet.add(id);
                j++;
            }
            // Dangling ids: declared but not answered here.
            List<Integer> dangs = null;
            for (int c = 0; c < transcript.get(i0).content().size(); c++) {
                ContentBlock b = transcript.get(i0).content().get(c);
                if (!(b instanceof ContentBlock.ToolUseBlock tu)) continue;
                String id = tu.id();
                if (id == null || id.isBlank()) continue;
                if (!batchAnsweredSet.contains(id)) {
                    if (dangs == null) dangs = new ArrayList<>();
                    dangs.add(c);
                }
            }
            if (dangs != null) danglingIdxs.set(i0, dangs);
            // Alien tool_results: answered ids whose owner is NOT this
            // assistant, OR whose owner doesn't exist at all (the R716
            // orphan case — preserved exactly).
            //
            // Important: read the id from the ACTUAL transcript at
            // position k, not from `batchAnswered` — `batchAnswered`
            // is the (id, count) tally while we walked forward, but
            // any given k may have been added to the tally AND may
            // itself be dropped here if its id is alien. Re-derive
            // the id from `transcript.get(k)` to avoid a misleading
            // index→id lookup.
            for (int k = i0 + 1; k < j; k++) {
                String id = toolUseIdOf(transcript.get(k));
                Integer owner = id == null ? null : ownerOf.get(id);
                if (id == null || owner == null || owner != i0) {
                    alienIdx[k] = true;
                }
            }
            // Decide where to resume. If the batch closed BEFORE
            // exhausting dec.size() (not enough tool_results) we still
            // move to the next message; if we collected exactly
            // dec.size() and the next message IS a tool_result, that
            // next message is alien (handled above) AND we must not
            // consume it as the start of a fresh batch — but in
            // practice the alien marking already protects it: i0 jumps
            // to k+1 only for the k=j tool_results we just decided on,
            // not for the undecided ones after. Easier: advance i0 by
            // 1 each step regardless; dangling marks cover the rest.
            i0 = i0 + 1; // never re-enter as a batch start unless it's
                         // still an assistant with declared ids (rare
                         // nested case; harmless — just re-runs the
                         // same dangling recomputation, idempotent).
        }

        // Phase 3 — produce the repaired list. Drops = R716 orphan
        // tool_results (no owner at all) plus alien tool_results
        // PLUS tool_results whose owner self-stripped the matching
        // tool_use in this pass (a previously-valid id whose
        // declaration is gone). Rewrites = assistants whose dangling
        // tool_use blocks are stripped; assistants whose stripping
        // empties the message are dropped entirely.
        //
        // We need the post-rewrite "surviving tool_use ids" set per
        // assistant BEFORE we decide any tool_result's fate, so the
        // owner-self-stripped case above is visible. Build
        // `survivingUseIds[assistantIdx]` from `declaredIds` minus
        // `danglingIdxs[assistantIdx]`.
        Map<Integer, Set<String>> survivingUseIds = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<String> dec = declaredIds.get(i);
            if (dec.isEmpty()) continue;
            List<Integer> dangs = danglingIdxs.get(i);
            Set<String> keeps = new LinkedHashSet<>(dec);
            if (dangs != null) {
                // walk dangs positions and remove by id (not by index
                // — keeps is the id set, danglingIdxs positions might
                // be stale by now, safer to recompute)
                // actually: dangs is positions in m.content, and
                // m.content is unchanged between phase 1 and phase 3,
                // so positions are stable.
                for (int pos : dangs) {
                    if (pos < transcript.get(i).content().size()) {
                        ContentBlock b = transcript.get(i).content().get(pos);
                        if (b instanceof ContentBlock.ToolUseBlock tu) {
                            keeps.remove(tu.id());
                        }
                    }
                }
            }
            survivingUseIds.put(i, keeps);
        }

        List<Message> cleaned = null;
        int droppedResults = 0;
        int strippedToolCalls = 0;
        int emptiedAssistants = 0;

        for (int i = 0; i < n; i++) {
            Message m = transcript.get(i);
            if (m == null) continue;
            boolean drop = false;

            if (m.role() == Role.TOOL_RESULT) {
                String id = toolUseIdOf(m);
                Integer owner = id == null ? null : ownerOf.get(id);
                if (id == null || owner == null) {
                    // R716 case
                    drop = true;
                    droppedResults++;
                    LOG.warn("R736: dropping tool_result message at index {} (tool_use_id={}): "
                                    + "no preceding assistant declared this id",
                            i, id);
                } else if (alienIdx[i]) {
                    // cross-batch intruder
                    drop = true;
                    droppedResults++;
                    LOG.warn("R736: dropping tool_result message at index {} (tool_use_id={}): "
                                    + "id={} answered a DIFFERENT assistant (index {}) than its "
                                    + "batch — providers reject cross-batch tool_results with a "
                                    + "bare 500", i, id, id, owner);
                } else {
                    // Owner's batch is this batch; but the owner may
                    // have self-stripped this id in this very pass.
                    Set<String> keeps = survivingUseIds.get(owner);
                    if (keeps == null || !keeps.contains(id)) {
                        drop = true;
                        droppedResults++;
                        LOG.warn("R736: dropping tool_result message at index {} (tool_use_id={}): "
                                        + "owner assistant at index {} no longer carries a "
                                        + "tool_use for this id — its own pass stripped it as "
                                        + "dangling. Leaving the tool_result in place would "
                                        + "produce the same 400 the strip was meant to avoid.",
                                i, id, owner);
                    }
                }
            }

            List<Integer> dangs = danglingIdxs.get(i);
            if (!drop && dangs != null && !dangs.isEmpty()) {
                // Strip the dangling ToolUseBlocks. Preserve prose
                // (TextBlock) and other unrelated blocks.
                List<ContentBlock> rewritten = new ArrayList<>(m.content());
                for (int k = dangs.size() - 1; k >= 0; k--) {
                    rewritten.remove(dangs.get(k).intValue());
                }
                strippedToolCalls += dangs.size();
                if (rewritten.isEmpty()) {
                    drop = true;
                    emptiedAssistants++;
                    LOG.warn("R736: dropping assistant message at index {} — every declared "
                                    + "tool_call ({}) was either dangling or alien; an assistant "
                                    + "message with no content is rejected by the provider",
                            i, dangs.size());
                } else if (rewritten.size() != m.content().size()) {
                    if (cleaned == null) {
                        cleaned = new ArrayList<>(n);
                        for (int j = 0; j < i; j++) cleaned.add(transcript.get(j));
                    }
                    m = new Message(m.id(), m.role(), rewritten,
                            m.timestamp(), m.metadata());
                }
            }

            if (drop) {
                if (cleaned == null) {
                    cleaned = new ArrayList<>(n);
                    for (int j = 0; j < i; j++) cleaned.add(transcript.get(j));
                }
                // skip
            } else if (cleaned != null) {
                cleaned.add(m);
            }
        }
        if (cleaned == null) return transcript; // fast path
        if (LOG.isInfoEnabled()) {
            LOG.info("R736: stripOrphanToolResults repaired transcript of {} messages -> {}: "
                            + "{} tool_result(s) dropped (R716 orphan or R736 alien), "
                            + "{} dangling tool_call(s) stripped, "
                            + "{} assistant message(s) emptied. The provider rejects any shape "
                            + "where an assistant's declared tool_call ids ≠ the ids of the "
                            + "tool_results that immediately follow it, with a bare "
                            + "`500 unknown error (1000)`.",
                    n, cleaned.size(), droppedResults, strippedToolCalls, emptiedAssistants);
        }
        return cleaned;
    }

    /** R736: canonical {@code tool_use_id} for a TOOL_RESULT message. */
    private static String toolUseIdOf(Message m) {
        if (m == null || m.role() != Role.TOOL_RESULT) return null;
        if (m.metadata() != null && m.metadata().get("tool_use_id") instanceof String s) {
            return s;
        }
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.ToolResultBlock tr) {
                if (tr.toolUseId() != null && !tr.toolUseId().isBlank()) return tr.toolUseId();
            }
        }
        return null;
    }

    /** R736: canonical {@code tool_use_id} for a TOOL_RESULT message.
     *  Prefers the metadata field, falls back to the content block. */

    /**
     * R716 final: hard-cap the transcript so a long-running session
     * can't blow the model's per-call input limit (MiniMax-M3 in
     * particular rejects with {@code 400 "invalid params, context
     * window exceeds limit (2013)"} when system + tools + transcript
     * exceed its actual ceiling — which is well below the 1M tokens
     * the model declares in {@code providers.yaml}). The on-disk
     * transcript is NOT mutated; only the wire-side copy is trimmed.
     *
     * <p>Strategy: count the raw {@link Message} count + the sum of
     * text characters across TextBlock / ToolResultBlock content. If
     * either exceeds the cap, drop messages from the HEAD of the
     * list — never from the tail — so the most-recent context
     * (which the user actually cares about) survives intact. If
     * anything was dropped, prepend a single synthetic
     * {@code role=USER} note that explains what happened, so the
     * model doesn't silently lose the tail-anchored prefix.
     *
     * <p>Pure filter: returns the same {@code List} reference when
     * the transcript is already within bounds (the common case), or
     * a new {@code ArrayList} when a trim fires. Safe to call from
     * the engine loop without coordination.
     *
     * <p>Defaults (caller pass-through to the static fields):
     * {@link #DEFAULT_MAX_TRANSCRIPT_MESSAGES} = 300 messages,
     * {@link #DEFAULT_MAX_TRANSCRIPT_CHARS} = 800_000 characters
     * (~ 200K tokens at 4 chars/token, well under MiniMax-M3's
     * actual ceiling once you add system prompt + 35 tool defs).
     * Override with the
     * {@code AETHERCODE_R716_MAX_MSGS} / {@code AETHERCODE_R716_MAX_CHARS}
     * env vars for tighter or looser limits in field tests.
     */
    public static final int DEFAULT_MAX_TRANSCRIPT_MESSAGES = 300;
    public static final int DEFAULT_MAX_TRANSCRIPT_CHARS = 800_000;

    public static List<Message> trimHeadIfOversized(
            List<Message> transcript,
            int maxMessages,
            int maxChars) {
        if (transcript == null || transcript.isEmpty()) return transcript;
        // Resolve env-overrides once per call. The helper is invoked
        // per turn from stream(...), so the cost is negligible — and
        // reading env vars here means a daemon restart isn't needed
        // to pick up a field-test override.
        int envMsgMax = readIntEnv("AETHERCODE_R716_MAX_MSGS", -1);
        int envCharMax = readIntEnv("AETHERCODE_R716_MAX_CHARS", -1);
        if (envMsgMax > 0) maxMessages = envMsgMax;
        if (envCharMax > 0) maxChars = envCharMax;

        if (transcript.size() <= maxMessages) {
            // still possible to be char-oversized even when count is
            // small (one big tool_result block). Compute the total
            // only on this path so healthy transcripts stay fast.
            long totalChars = 0;
            for (Message m : transcript) {
                totalChars += messageCharLength(m);
            }
            if (totalChars <= maxChars) return transcript;
        }

        // Walk from the tail backwards, accumulating the most-recent
        // messages that fit inside the cap. Stop either when we hit
        // a message count ceiling OR a char ceiling — whichever
        // triggers first. We always preserve at least
        // preserveFloor (25) messages so the model has enough
        // back-and-forth to make sense of the latest user turn.
        int preserveFloor = Math.min(25, maxMessages);
        List<Message> tail = new ArrayList<>(Math.min(maxMessages, transcript.size()));
        int kept = 0;
        long chars = 0;
        int droppedFromHead = 0;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Message m = transcript.get(i);
            int msgChars = messageCharLength(m);
            boolean overCap = tail.size() >= maxMessages
                    || (chars + msgChars > maxChars && tail.size() >= preserveFloor);
            if (overCap) {
                // everything earlier than this index is going to be
                // dropped (i.e. transcript[0..i] inclusive is the
                // head). count the leftovers and break.
                droppedFromHead = i + 1;
                break;
            }
            tail.add(m);
            chars += msgChars;
        }
        if (droppedFromHead == 0) {
            // We never tripped the cap — usually because tail.size()
            // ran out before hitting maxMessages AND every message
            // fit under char-max. Return same reference (fast path).
            return transcript;
        }
        // Reverse tail so the surviving subset reads in original
        // order. Then prepend a synthetic note.
        java.util.Collections.reverse(tail);
        Message trimNote = new Message(
                "r716-trim-" + System.currentTimeMillis(),
                Role.USER,
                List.of(new ContentBlock.TextBlock(
                        "[R716 transcript trim] Earlier messages in this session were dropped to "
                                + "fit the model's per-call context limit. "
                                + droppedFromHead + " of " + transcript.size()
                                + " messages were trimmed from the head (preserved the most-recent "
                                + tail.size() + "). "
                                + "Continue the conversation from where the user left off; "
                                + "do NOT comment on the trim itself.")),
                null,
                java.util.Map.of("kind", "transcript-trim-side-note", "trimmed", droppedFromHead));
        tail.add(0, trimNote);
        LOG.warn("R716: trimHeadIfOversized dropped {} head message(s) of {} (kept {}, "
                        + "maxMessages={}, maxChars={})",
                droppedFromHead, transcript.size(), tail.size() - 1, // -1 for the trim note
                maxMessages, maxChars);
        return tail;
    }

    /** Env-reader helper that returns the parsed int or the default
     *  when the env var is unset, blank, or unparseable. Kept
     *  package-private so unit tests can shadow the env via a
     *  ProcessBuilder / test harness if needed. */
    static int readIntEnv(String key, int defaultValue) {
        String raw;
        try {
            raw = System.getenv(key);
        } catch (SecurityException ignored) {
            return defaultValue;
        }
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** Sum of {@code text} / {@code stringified tool-result content}
     *  across every ContentBlock of a single message. We don't try
     *  to be exact about tool-result JSON: a rough char count is
     *  good enough as a per-call budget gate, and the model isn't
     *  sensitive to off-by-one on this number. */
    private static int messageCharLength(Message m) {
        int total = 0;
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                total += t.text().length();
            } else if (b instanceof ContentBlock.ToolResultBlock r) {
                if (r.content() instanceof String s) total += s.length();
                else total += String.valueOf(r.content()).length();
            } else if (b instanceof ContentBlock.ToolUseBlock tu) {
                if (tu.name() != null) total += tu.name().length();
                if (tu.input() != null) total += String.valueOf(tu.input()).length();
            }
        }
        return Math.max(total, 1);
    }

    private long estimateTranscriptTokens() {
        long total = 0;
        for (Message m : appState.transcript()) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.TextBlock t) {
                    total += t.text().length();
                } else if (b instanceof ContentBlock.ToolResultBlock r) {
                    if (r.content() instanceof String s) total += s.length();
                }
            }
        }
        return total / 4;
    }

    /** notify the active loop detector that the user has
     *  pressed Ctrl-C / interrupted the run. The next batch check
     *  returns a {@code user_interrupt} loop info immediately. */
    public void notifyUserInterrupt() {
        ProgressLoopDetector d = this.currentLoopDetector;
        if (d != null) d.notifyUserInterrupt();
    }

    /** the loop detector for the current user query, or
     *  {@code null} if no query is in flight. The detector is
     *  rebuilt on every {@code query()} call (so prior-session
     *  history doesn't bleed in), so callers that hold a
     *  reference to it should re-read it on each new run.
     *  The {@code loopAck} RPC handler uses this to call
     *  {@link ProgressLoopDetector#acknowledge()} when the user
     *  clicks "Continue" in the LoopGuardBanner. */
    public ProgressLoopDetector currentLoopDetector() {
        return currentLoopDetector;
    }

    /**
     * Public accessor for the cost tracker so external code (eval
     * harness, dashboard) can read cumulative per-run token + USD
     * counts without going through reflection.
     *
     * <p>Returns the live {@link org.aethercode.core.cost.CostTracker}
     * — mutating it (e.g. calling {@code reset()}) will affect subsequent
     * runs. Read-only consumers should call {@code summary()} on the
     * returned object.
     *
     * <p>R694 follow-up: previously this field was private without a
     * getter, forcing the eval RPC to hardcode {@code costUsd=0}.
     */
    public org.aethercode.core.cost.CostTracker costTracker() {
        return costTracker;
    }

    /** walk the current todo list and emit {@code SubTaskStart}
     *  / {@code SubTaskEnd} events for any sub-task whose status
     *  changed since the last call. Called by the Spliterator
     *  after every tool batch (so the first call after a fresh
     *  {@link #query} reflects the model's initial plan, and
     *  subsequent calls reflect per-tool-call deltas). The diff
     *  uses {@link #lastSubTaskStatus} as the previous state.
     *
     * <p>Package-private so {@code QueryEngineSubTaskTest} can
     * exercise the state machine directly (one call per "todo
     * write" the model does) without standing up a full
     * mock-LLM flow. The internal call sites use the same
     * signature.
     */
    void emitSubTaskTransitions(java.util.function.Consumer<? super StreamEvent> action) {
        java.util.List<java.util.Map<String, Object>> todos = appState.todoList();
        if (todos == null || todos.isEmpty()) {
            // Plan was cleared. Close any tracked sub-tasks as skipped.
            if (!lastSubTaskStatus.isEmpty()) {
                for (var e : lastSubTaskStatus.entrySet()) {
                    String[] parts = e.getKey().split(":");
                    if (parts.length == 2) {
                        try {
                            int tIdx = Integer.parseInt(parts[0]);
                            action.accept(new StreamEvent.SubTaskEnd(
                                    tIdx, parts[1], "skipped", "todo list cleared"));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                lastSubTaskStatus.clear();
            }
            // with the todo list cleared, no sub-task is in
            // scope. Reset the executor's tracking so the next
            // permission check promotes to an ask.
            currentSubTaskKey = null;
            pushSubTaskKeyToExecutor();
            return;
        }
        // Track which keys we see this round so we can detect "removed" sub-tasks.
        java.util.Set<String> seen = new java.util.HashSet<>();
        // figure out which sub-task (if any) is currently
        // in_progress. We update the executor's tracking on every
        // emit so it can compare call-time subTaskId against the
        // "active" one. The active sub-task is the FIRST
        // in_progress one we see (top-down: top-level task first,
        // then sub-tasks in order).
        String activeKey = null;
        for (int t = 0; t < todos.size(); t++) {
            java.util.Map<String, Object> todo = todos.get(t);
            Object subs = todo.get("subtasks");
            // R740: a flat top-level todo (no `subtasks` field) is the
            // common shape the model uses via todo_write when it does
            // not break work down further. Pre-R740 we silently
            // skipped these (continue) and never fired SubTaskStart /
            // SubTaskEnd for them, so the MessageList SubTaskCard area
            // stayed empty even though the RightPanel "Plan" tab (which
            // has its own todo_write handler in the desktop store) was
            // showing every item as completed. Treat each flat todo as
            // a single sub-task under a synthetic task-id 0 with an
            // auto-generated stable id (`auto-{idx}`).
            if (subs instanceof java.util.List<?> subList) {
                for (Object se : subList) {
                    if (!(se instanceof java.util.Map<?, ?> sm)) continue;
                    String id = strOr(sm.get("id"), null);
                    if (id == null) continue;
                    String status = strOr(sm.get("status"), "pending");
                    String content = strOr(sm.get("content"), "");
                    String summary = strOr(sm.get("summary"), null);
                    String key = t + ":" + id;
                    seen.add(key);
                    String prev = lastSubTaskStatus.get(key);
                    boolean isTerminal = "completed".equals(status) || "failed".equals(status) || "skipped".equals(status);
                    if (prev == null) {
                        // First sight. Always emit Start so the UI can
                        // anchor the card; if the initial status is
                        // already terminal, follow up with an End so
                        // the renderer shows the summary card right
                        // away.
                        action.accept(new StreamEvent.SubTaskStart(t, id, content, status));
                        if (isTerminal) {
                            action.accept(new StreamEvent.SubTaskEnd(t, id, status, summary));
                        }
                    } else if (!prev.equals(status)) {
                        // Status changed. The interesting transitions:
                        //   any -> terminal   → emit End
                        //   * -> in_progress  → emit Start (re-open OR
                        //                          first activation; the
                        //                          UI needs the in_progress
                        //                          signal to switch the
                        //                          active card).
                        // The reverse (in_progress -> pending) is rare and
                        // treated as silent — the engine still updates
                        // lastSubTaskStatus so subsequent transitions are
                        // correct, but no event fires.
                        if (isTerminal) {
                            action.accept(new StreamEvent.SubTaskEnd(t, id, status, summary));
                        } else if ("in_progress".equals(status)) {
                            action.accept(new StreamEvent.SubTaskStart(t, id, content, status));
                        }
                    }
                    lastSubTaskStatus.put(key, status);
                    if (activeKey == null && "in_progress".equals(status)) {
                        activeKey = key;
                    }
                }
            } else if (subs == null) {
                // R740: flat-todo branch. Synthesize one sub-task entry
                // from the top-level todo itself. We only synthesize if
                // the todo has at least a `content` string — otherwise
                // there is nothing for the renderer to show, and we'd
                // rather emit nothing than an empty SubTaskCard.
                // R740 marker: emits a sub-task under id `auto-{t}`
                // for every flat top-level todo. Used as the deployment-
                // time verification marker so the daemon log can confirm
                // the new branch actually fired for a real transcript.
                String content = strOr(todo.get("content"), null);
                if (content == null) continue;
                String id = "auto-" + t;
                String status = strOr(todo.get("status"), "pending");
                String summary = strOr(todo.get("summary"), null);
                String key = t + ":" + id;
                seen.add(key);
                String prev = lastSubTaskStatus.get(key);
                boolean isTerminal = "completed".equals(status) || "failed".equals(status) || "skipped".equals(status);
                if (prev == null) {
                    action.accept(new StreamEvent.SubTaskStart(t, id, content, status));
                    if (isTerminal) {
                        action.accept(new StreamEvent.SubTaskEnd(t, id, status, summary));
                    }
                } else if (!prev.equals(status)) {
                    if (isTerminal) {
                        action.accept(new StreamEvent.SubTaskEnd(t, id, status, summary));
                    } else if ("in_progress".equals(status)) {
                        action.accept(new StreamEvent.SubTaskStart(t, id, content, status));
                    }
                }
                lastSubTaskStatus.put(key, status);
                if (activeKey == null && "in_progress".equals(status)) {
                    activeKey = key;
                }
                if (LOG.isInfoEnabled() && prev == null) {
                    LOG.info("R740: flat-todo synthesised SubTaskStart taskId={} subTaskId={} status={} content='{}'",
                            t, id, status, content);
                }
            }
            // subs is non-null but not a List (e.g. String) — silently
            // ignore, same as before R740.
        }
        // Sub-tasks that disappeared from the list (e.g. a
        // todo_write that didn't include them). Treat as skipped.
        for (var e : lastSubTaskStatus.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            String[] parts = e.getKey().split(":");
            if (parts.length != 2) continue;
            try {
                int tIdx = Integer.parseInt(parts[0]);
                action.accept(new StreamEvent.SubTaskEnd(tIdx, parts[1], "skipped",
                        "sub-task removed from todo list"));
            } catch (NumberFormatException ignored) {}
        }
        lastSubTaskStatus.keySet().retainAll(seen);
        // push the new active sub-task to the executor. The
        // next permission check with a different subTaskId (e.g.
        // a fresh in_progress sub-task just started) will be
        // promoted to an ask.
        if (!java.util.Objects.equals(activeKey, currentSubTaskKey)) {
            currentSubTaskKey = activeKey;
            pushSubTaskKeyToExecutor();
        }
    }

    /** forward {@link #currentSubTaskKey} to the streaming
     *  executor (which tags every CallContext with it). Best-effort:
     *  if the executor is null (very early init / test stub), we
     *  silently skip — the next emitSubTaskTransitions call will
     *  retry. */
    private void pushSubTaskKeyToExecutor() {
        if (streamingExecutor == null) return;
        streamingExecutor.setCurrentSubTaskId(currentSubTaskKey);
    }

    private static String strOr(Object o, String fallback) {
        if (o == null) return fallback;
        String s = o.toString();
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    private String effectiveSystem() {
        StringBuilder sb = new StringBuilder(systemPrompt);
        // R379: the default agent's block sits right after the base
        // prompt and BEFORE the memory section, because it is part of
        // "who you are", not part of "what you know right now".
        if (defaultAgentBlock != null && !defaultAgentBlock.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(defaultAgentBlock);
        }
        // append the per-query memory section if the engine set one.
        // The memory section is added BEFORE planModeSuffix so the plan
        // (which is the most recent instruction) is closest to the
        // model's "now" attention.
        if (memorySection != null && !memorySection.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(memorySection);
        }
        if (planModeSuffix != null && !planModeSuffix.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(planModeSuffix);
        }
        return sb.toString();
    }

    /** set the per-query memory section. Called by the engine
     *  before each {@link #query(String)} call with the output of
     *  {@code MemoryRecall.render(...)}. The QueryEngine resets it to
     *  empty when the query stream ends so subsequent queries start
     *  fresh. */
    public void setMemorySection(String section) { this.memorySection = section == null ? "" : section; }

    /** replace the entire system prompt. Used by the TUI when
     *  the user runs {@code /agent <name>} to switch to a custom
     *  agent. The new prompt is used for ALL subsequent queries
     *  until {@code setSystemPrompt(...)} is called again. The
     *  memory section and plan mode suffix are still appended
     *  on top of this base prompt. */
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt == null ? "" : prompt;
    }
    public String systemPrompt() { return systemPrompt; }

    /** R379: install (or clear) the default agent's system-prompt
     *  block. Appended to the base prompt on every turn, so setting
     *  a default agent takes effect for the NEXT message the user
     *  sends — the same contract Claude Code's agent picker has. */
    public void setDefaultAgentBlock(String block) {
        this.defaultAgentBlock = block == null ? "" : block;
    }
    public String defaultAgentBlock() { return defaultAgentBlock; }

    /** build the system prompt for a specific turn using a
     *  SNAPSHOTTED memory section (not the live field). This is the
     *  per-turn system prompt: identity + environment + tooling +
     *  workflow + memorySection + planModeSuffix, joined with
     *  blank lines. Extracted from {@link #effectiveSystem()} so we can
     *  pass the snapshot in. */
    private String buildSystemForTurn(String memory, String plan) {
        StringBuilder sb = new StringBuilder(systemPrompt);
        // R379: the default agent's block sits right after the base
        // prompt and BEFORE the memory section, because it is part of
        // "who you are", not part of "what you know right now".
        if (defaultAgentBlock != null && !defaultAgentBlock.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(defaultAgentBlock);
        }
        if (memory != null && !memory.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(memory);
        }
        if (plan != null && !plan.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(plan);
        }
        // R737: the response-language lock. Appended LAST so it sits
        // at the very end of the prompt — the position closest to the
        // model's "now" attention, same reasoning as planModeSuffix
        // above. This is the deterministic half of the fix: the
        // identity section of the system prompt carries a soft
        // "reply in the user's language" rule, but a MiniMax-M3
        // class model follows English because the whole prompt is
        // English. Detecting the language from the transcript and
        // stating it explicitly, per turn, at the tail, is what
        // actually flips the output.
        String lang = responseLanguageDirective();
        if (!lang.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(lang);
        }
        return sb.toString();
    }

    /**
     * R737: build the per-turn response-language directive, or ""
     * when the user has not written anything we can confidently
     * classify (or has written only English / only code).
     *
     * <p>Detection is deliberately conservative and cheap:
     * <ul>
     *   <li>Scan BACKWARDS through the transcript for the most
     *       recent message that has meaningful prose. Tool
     *       results and assistant turns are skipped — we want the
     *       USER's language, not the tool output's (tool output is
     *       always English/system-locale and would poison the
     *       detection).</li>
     *   <li>Count CJK codepoints (U+4E00–U+9FFF, U+3040–U+30FF
     *       for kana, U+AC00–U+D7AF for hangul) against Latin
     *       letters. If CJK ≥ 12 and CJK ≥ 25% of the letter
     *       count → Chinese. The threshold keeps a single pasted
     *       CJK filename or error string from flipping the whole
     *       turn's language.</li>
     *   <li>Only Chinese is detected today. Other languages fall
     *       through to the identity-section soft rule, which asks
     *       the model to mirror the user's language — the model
     *       handles that natively; Chinese needed the explicit
     *       override because the prompt is English and M3's
     *       prior for Chinese turns is to answer in English.</li>
     * </ul>
     *
     * <p>The directive is scoped the same way the identity rule is:
     * prose follows the user's language, code / paths / identifiers
     * / copied error strings stay verbatim. Without that scope the
     * model starts translating `bash` command lines and file paths,
     * which breaks the tools.
     */
    private String responseLanguageDirective() {
        String detected = detectUserLanguage();
        if (detected == null) return "";
        return """
                # Response language (R737, per-turn, highest priority)

                The user is reading **Chinese**. Write all of your natural-language
                output in Chinese: prose, summaries, explanations, questions, the
                design block, todo items, headings.

                Keep these VERBATIM in their original form — translating them breaks
                the tools:
                - code, file paths, identifiers, CLI flags
                - error strings and log lines copied out of tool output
                - tool names (`bash`, `grep`, `file_read`, …)

                Code comments should match the surrounding file's existing language.
                Do not announce the language switch and do not provide a translation
                alongside the original — just write Chinese.""";
    }

    /** R737: the detected language tag, or null when unclassifiable. */
    private String detectUserLanguage() {
        List<Message> transcript = appState.transcript();
        if (transcript == null || transcript.isEmpty()) return null;
        // Walk backwards; the FIRST user message with enough prose wins.
        // We do not stop at the first user message — a user who typed
        // "ok" after a long Chinese task would otherwise un-set the
        // language — we look for the most recent message that carries
        // enough letters to classify at all.
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Message m = transcript.get(i);
            if (m == null || m.role() != Role.USER) continue;
            String text = m.textContent();
            if (text == null || text.isBlank()) continue;
            int cjk = 0, latin = 0;
            for (int c = 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                if ((ch >= '一' && ch <= '鿿')
                        || (ch >= '぀' && ch <= 'ヿ')
                        || (ch >= '가' && ch <= '힯')) {
                    cjk++;
                } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                    latin++;
                }
            }
            if (cjk >= 12 && cjk * 4 >= latin) {
                // R737 trace. Lesson 1052: a fix you cannot see fire
                // in the log is a fix you cannot prove shipped. This
                // marker is the deployment-time check for the language
                // lock — if it never appears but the model still
                // answers in English, detection is not firing.
                LOG.info("R737: detected user language=zh from transcript[{}] "
                        + "(cjk={}, latin={}); appending the response-language "
                        + "directive to this turn's system prompt", i, cjk, latin);
                return "zh";
            }
            // Not classifiable as Chinese. Keep walking backwards —
            // the most recent *classifiable* user message wins, not the
            // most recent user message.
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("R737: no user message cleared the Chinese threshold "
                    + "(cjk>=12 && cjk*4>=latin) across {} transcript entries; "
                    + "leaving the response language to the identity-section rule",
                    transcript.size());
        }
        return null;
    }

    public QueryEngine(
            AppState appState,
            ChatClient chatClient,
            PermissionPolicy permissionPolicy,
            String systemPrompt,
            Consumer<Message> messageSink,
            StreamingToolExecutor streamingExecutor
    ) {
        this(appState, chatClient, permissionPolicy, systemPrompt, messageSink,
                streamingExecutor, null, null);
    }

    public QueryEngine(
            AppState appState,
            ChatClient chatClient,
            PermissionPolicy permissionPolicy,
            String systemPrompt,
            Consumer<Message> messageSink,
            StreamingToolExecutor streamingExecutor,
            org.aethercode.core.compact.Compactor compactor,
            Consumer<String> sideNoteSink
    ) {
        this(appState, chatClient, permissionPolicy, systemPrompt, messageSink,
                null, null, streamingExecutor, compactor, sideNoteSink);
    }

    /** full constructor. The short form above delegates here. */
    public QueryEngine(
            AppState appState,
            ChatClient chatClient,
            PermissionPolicy permissionPolicy,
            String systemPrompt,
            Consumer<Message> messageSink,
            org.aethercode.core.metrics.MetricsCollector metricsCollector,
            org.aethercode.core.cost.CostTracker costTracker,
            StreamingToolExecutor streamingExecutor,
            org.aethercode.core.compact.Compactor compactor,
            Consumer<String> sideNoteSink
    ) {
        this.appState = appState;
        this.chatClient = chatClient;
        this.permissionPolicy = permissionPolicy;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.messageSink = messageSink == null ? m -> {} : messageSink;
        this.metricsCollector = metricsCollector;
        this.costTracker = costTracker;
        this.streamingExecutor = streamingExecutor;
        this.compactor = compactor;
        this.sideNoteSink = sideNoteSink == null ? s -> {} : sideNoteSink;
    }

    /**
     * Run a single user prompt. Returns a stream of events; the caller pulls them at their
     * own pace. The stream always finishes with a {@link StreamEvent.RunEnd}.
     */
    public Stream<StreamEvent> query(String userInput) {
        return query(userInput, null);
    }

    /**
     * run a single user prompt with a per-call
     * {@link ChatClient} override. When {@code override} is
     * non-null, the LLM stream and the cost record for this
     * call use {@code override} instead of the engine's
     * default {@code chatClient}. The override is captured
     * into the {@link StreamEvent} chain and the per-call
     * cost record; the engine's own {@code chatClient}
     * field is NOT mutated, so concurrent callers (e.g. a
     * main-loop query on one thread and a workflow
     * executor's child session on another) do not race on
     * a shared field. Pass {@code null} to fall back to
     * the engine's default — that's what the 1-arg
     * overload does.
     */
    public Stream<StreamEvent> query(String userInput, ChatClient override) {
        // Snapshot once. The Spliterator below captures
        // this local; the engine's chatClient field stays
        // untouched (so the main turn loop sees no
        // mutation).
        final ChatClient effectiveChatClient = override != null ? override : this.chatClient;
        if (userInput == null || userInput.isBlank()) {
            return Stream.of(new StreamEvent.RunEnd("empty_input", List.of()));
        }
        // pre-flight auto-compact. If the transcript
        // is already past 90% of the context window
        // BEFORE the next turn, compact now (don't wait
        // for the "no tool calls" end-of-turn check,
        // because that only fires when the model gives
        // up; we want to compact proactively when the
        // model is still in the middle of work). The
        // pre-flight runs synchronously and emits its
        // own SideNote for the UI.
        try {
            runPreFlightCompact();
        } catch (RuntimeException ex) {
            // Pre-flight compact must never break a user
            // query. Log and continue; the regular
            // end-of-turn check will still fire.
            java.util.logging.Logger.getLogger(QueryEngine.class.getName())
                    .warning("R140 pre-flight compact failed: " + ex.getMessage());
        }
        // fire the onUserPrompt hook (no-op by default). The
        // engine installs a hook that detects skip-confirmation
        // patterns in the prompt and arms the registry.
        try {
            onUserPrompt.accept(userInput);
        } catch (RuntimeException hookErr) {
            // A failing hook must never break a user query.
            // Log and continue.
            java.util.logging.Logger.getLogger(QueryEngine.class.getName())
                    .warning("onUserPrompt hook failed: " + hookErr);
        }
        // per-task preamble. Inject a system-level
        // message that tells the model to start writing
        // immediately instead of doing version checks.
        // Without this, models can spend 3-5 turns on
        // mvn -version / java -version / echo / ls
        // before the R138.1 research_mode detector
        // fires. The preamble is OFF by default and
        // turned on with AETHERCODE_R144_PREAMBLE=1
        // (so existing users aren't surprised by the
        // new system message in their transcripts).
        // Placed AFTER pre-flight compact so the
        // preamble survives a compaction (it lives
        // in the transcript, not in a sidecar).
        if ("1".equals(System.getenv("AETHERCODE_R144_PREAMBLE"))) {
            String preambleText =
                    "[AetherCode R144 preamble]\n" +
                    "Skip environment research on this turn. Do NOT run " +
                    "`mvn -version`, `java -version`, `which`, `ls`, `pwd`, " +
                    "or `echo` for the purpose of verifying the environment. " +
                    "If the user is asking for code, your FIRST tool call " +
                    "must be a `file_write` for the smallest piece of the " +
                    "task (e.g. `pom.xml` for a Java project, the manifest " +
                    "for other stacks). You can read after writing.\n\n" +
                    "If the user is asking a question, just answer in text.\n\n" +
                    "User prompt follows:";
            Message preamble = Message.system(preambleText);
            appState.appendMessage(preamble);
            messageSink.accept(preamble);
        }
        // if the previous query paused for a user decision
        // (TodoRunController hit its max-bumps limit), the next
        // userInput is a continuation. Prepend a system note so the
        // LLM knows the context. We also emit a SideNote so the
        // transcript is self-explanatory in --print / TUI replays.
        if (awaitingUserDecision) {
            String prev = pendingAwaitSummary == null ? "previous task" : pendingAwaitSummary;
            Message sysNote = Message.userText(
                    "[Resuming after user decision]\n" +
                    "Previous decision prompt: " + prev + "\n\n" +
                    "User's response: " + userInput);
            appState.appendMessage(sysNote);
            messageSink.accept(sysNote);
            awaitingUserDecision = false;
            pendingAwaitSummary = null;
        }
        Message userMsg = Message.userText(userInput);
        appState.appendMessage(userMsg);
        messageSink.accept(userMsg);

        String runId = "run-" + runCounter.incrementAndGet();
        // capture the memory section that the engine set before
        // delegating. We snapshot it into a local so the engine's
        // reset (after the stream ends) doesn't race with the
        // Spliterator's read in the same query. The Spliterator runs
        // single-threaded (driven by the consumer) so the volatile
        // read on the field is fine, but this local makes the
        // semantics explicit.
        final String snapshotMemory = this.memorySection;
        // per-query loop detector. Built fresh each query so
        // prior-session history doesn't bleed into the new one. The
        // detector's window/threshold come from the live volatile
        // fields, so the SDK can tune them at runtime via
        // setLoopDetector(...) without us re-creating the engine.
        // The high-risk tool set is also read live.
        // use the complex-task factory (window 20,
        // fingerprint 5, longOutput 5000, warnBeforeStop 4) when
        // EITHER the user explicitly asked for it
        // (forceComplexTaskDetector) OR the user prompt looks
        // like a multi-file project (>1500 chars OR mentions
        // writing N files). The RAG end-to-end test needs this
        // because each module is 8+ files and the model spends
        // a lot of its time on legitimate "thinking" text
        // between file writes.
        final ProgressLoopDetector loopDetector =
                (loopDetectWindow > 0 && loopDetectThreshold > 0)
                        ? pickLoopDetector(userInput)
                        : null;
        // Stash on the field so notifyUserInterrupt() can find it.
        currentLoopDetector = loopDetector;
        // Reset the field early so the NEXT query (which the user
        // might fire on a different thread) doesn't see the stale
        // section. The current query still uses snapshotMemory.
        this.memorySection = "";
        // reset the per-todo controller so each user query
        // starts with a fresh soft threshold + bump count. The
        // previous run's bump count does NOT carry over.
        todoController.reset();
        // reset the sub-task status tracker so the first
        // SubTaskStart for a sub-task newly promoted to
        // in_progress in this query fires. Without this, a sub-task
        // that was in_progress at the end of the previous query
        // would never get a fresh Start.
        lastSubTaskStatus.clear();
        // each new query starts with no active sub-task, so
        // the first permission ask in a new sub-task is treated
        // as a fresh boundary. Reset the executor's tracking too.
        currentSubTaskKey = null;
        pushSubTaskKeyToExecutor();
        java.util.Spliterator<StreamEvent> sp = new java.util.Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
            private List<ContentBlock.ToolUseBlock> pending = new ArrayList<>();
            private String stopReason = "end_turn";
            private List<ContentBlock> lastAssistantBlocks = List.of();
            private boolean finished = false;
            /** how many model turns have been issued for this user query. */
            private int turnsTaken = 0;
            /** true if a {@code Usage} event was received from the
             *  chat client for the current turn. Used to decide
             *  whether to fall back to char-based estimation. */
            private boolean turnHadUsage = false;
            /** R750: RunStart is emitted once per RUN, not once per
             *  turn. This branch re-enters on every LLM turn of the
             *  same run, so without the latch an external stream-json
             *  consumer sees system/init repeated once per iteration. */
            private boolean runStartEmitted = false;
            /** R750 (2026-10-22): events produced during ONE
             *  {@code tryAdvance} call that have not been handed to the
             *  consumer yet.
             *
             *  <p>Why this exists. {@code tryAdvance(action)} has a
             *  contract: it may pass AT MOST ONE element to {@code action}
             *  per call, and {@code hasNext()} is allowed to invoke
             *  {@code tryAdvance} internally to find out whether an
             *  element exists. {@code StreamConsumerGuard} (R746) probes
             *  {@code hasNext()} on a separate thread precisely to bound
             *  the blocking read — which means every {@code action.accept}
             *  inside this method is invoked with the probe's OWN
             *  throwaway action, and only the single element later
             *  returned by {@code it.next()} reaches the real handler.
             *
             *  <p>That silently dropped every event emitted after the
             *  first within one tryAdvance call. Measured on a real
             *  5-turn tool-calling run: all 5 {@code ToolResult} frames
             *  arrived, and all 5 {@code ToolUseStart} declarations were
             *  lost — so the wire stream carried tool answers with no
             *  matching calls, which is exactly the orphan shape the
             *  provider's adjacency contract (R736/R741) forbids and
             *  which multica's stream-json backend then renders as
             *  "a result with no call".
             *
             *  <p>The fix is to buffer instead of forwarding: every
             *  internal emit enqueues, and {@code tryAdvance} drains at
             *  most one element per call. That makes the contract hold
             *  for ANY consumer — the guard's probe thread, a plain
             *  {@code forEach}, or {@code collect()} — rather than
             *  working around one particular consumer. */
            private final java.util.ArrayDeque<StreamEvent> pendingEvents =
                    new java.util.ArrayDeque<>();

            /** R750: internal emit target. Buffers instead of writing
             *  straight through, so nothing depends on how many events a
             *  single tryAdvance produces. */
            private void emit(StreamEvent ev) {
                if (ev != null) pendingEvents.addLast(ev);
            }

            /** R750: hand at most ONE buffered event to the consumer, per
             *  the Spliterator contract. Returns true when something was
             *  delivered OR more remain buffered (so the caller keeps
             *  pulling), false only when nothing is left to give. */
            private boolean drainOne(java.util.function.Consumer<? super StreamEvent> action) {
                if (pendingEvents.isEmpty()) return false;
                action.accept(pendingEvents.pollFirst());
                return true;
            }

            /**
             * R750b: the ONLY correct way to end a {@code tryAdvance}
             * iteration that produced no consumer-visible event.
             *
             * <p>Several branches legitimately produce nothing — a
             * {@code Usage} event recorded internally, a model-side
             * {@code RunStart} dropped as a duplicate, a turn-start that
             * only issues the LLM call. Each used to end with a bare
             * {@code return true;}, which is a CONTRACT VIOLATION, not a
             * harmless no-op.
             *
             * <p>{@code tryAdvance} returning {@code true} promises the
             * action received an element. Returning true without one
             * desynchronises {@code Spliterators.AbstractSpliterator}'s
             * iterator: {@code hasNext()} caches "true", {@code next()}
             * has nothing to hand back, and the following
             * {@code hasNext()} re-enters {@code tryAdvance}, finds an
             * empty buffer, and returns false. The consumer reads that as
             * a clean drain and exits WITHOUT the terminal RunEnd —
             * {@code no_run_end: engine stream ended without RunEnd}.
             *
             * <p>So: deliver one buffered event if there is one; if not,
             * tell the enclosing loop to keep driving the engine (which
             * parks in the LLM read until the model really produces
             * something). The loop re-checks {@code finished} and the
             * cancel interrupt flag on every pass, so terminal and
             * cancel paths still work.
             */
            private boolean deliveredOrContinue(java.util.function.Consumer<? super StreamEvent> action) {
                if (!pendingEvents.isEmpty()) return drainOne(action);
                return false;
            }
            /** R750: tool ids already announced to the consumer this run.
 *  The model-stream pass-through and the tool executor's
 *  {@code Started} event describe the SAME call; emitting both
 *  produced a duplicate {@code tool_use} frame on the Claude
 *  stream-json wire. Populated by the model's ToolUseStart branch,
 *  consulted by the executor's Started branch. */
private final java.util.Set<String> announcedToolUseIds =
                    java.util.Collections.newSetFromMap(
                            new java.util.concurrent.ConcurrentHashMap<>());

private Stream<StreamEvent> currentLlmStream;
            private java.util.Iterator<StreamEvent> currentLlmIt;

            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super StreamEvent> action) {
              try {
                // R750b: several branches legitimately produce no
                // consumer-visible event (an internally-recorded Usage, a
                // dropped duplicate RunStart, a turn-start that only
                // issues the LLM call). They must NOT end the iteration
                // with a bare `return true` — see
                // deliveredOrContinue for why that desynchronises the
                // iterator into a premature clean drain.
                engineLoop:
                while (true) {
                    // R750: a previous tryAdvance may have produced several
                    // events. The Spliterator contract allows exactly ONE per
                    // call, so hand back the buffered ones first and only
                    // resume engine work once the buffer is empty. Without
                    // this, everything after the first event of a busy turn
                    // (notably every ToolUseStart / ToolResult pair) is
                    // silently swallowed by whichever consumer probes
                    // hasNext() — see the pendingEvents doc.
                    if (!pendingEvents.isEmpty()) {
                        return drainOne(action);
                    }
                    if (finished) return false;
                    // R721: honour cancel's thread interrupt. Pre-R720
                    // the cancel RPC only flipped the future's cancel
                    // flag, which no in-flight code path actually
                    // observed — the LLM stream kept reading until the
                    // model yielded the next RunEnd, which could be
                    // 60-120s away. R721 has the cancel RPC also call
                    // {@code Thread.interrupt()} on the worker thread
                    // that owns this tryAdvance; we now bail cleanly
                    // at the top of every iteration, emit a single
                    // RunEnd so the renderer's transcript shows the
                    // run ended (not errored), and stop the stream.
                    // The interrupt flag is cleared here so any
                    // downstream I/O (socket reads, NIO selectors)
                    // that polls Thread.interrupted() doesn't loop.
                    if (Thread.interrupted()) {
                        finished = true;
                        stopReason = "cancelled";
                        // R750: anything already buffered (SideNote from a
                        // partially-processed batch, etc.) still owes the
                        // consumer a delivery. Hand back ONE per call; the
                        // top-of-method guard will keep draining on the next
                        // call. Only when the buffer is empty do we emit
                        // the terminal RunEnd directly and terminate — a
                        // buffered RunEnd drained ahead of an older event,
                        // then `return false`, would strand the RunEnd and
                        // leave the desktop in the green ● Idle state.
                        if (!pendingEvents.isEmpty()) return drainOne(action);
                        try {
                            action.accept(new StreamEvent.RunEnd("cancelled", List.of()));
                        } catch (Exception ignored) {
                            // the action consumer can throw on a closed
                            // downstream — that's fine, the run is
                            // over either way.
                        }
                        return false;
                    }

                    if (currentLlmStream == null) {
                        // the per-query turn cap is gone. Per-todo adaptive
                        // control in TodoRunController is the source of truth
                        // for "this is taking too long".
                        // Start of a turn.
                        //
                        // R750 (2026-10-22): RunStart is a RUN-scoped
                        // event, not a TURN-scoped one. This branch re-enters
                        // on every LLM turn, so the emit used to fire once
                        // per turn — an external scheduler driving us over
                        // the Claude Code stream-json protocol saw
                        // system/init repeated on every iteration of a
                        // tool-calling loop (measured: 5 identical init
                        // frames in a 5-turn run). The desktop interprets a
                        // second RunStart as "a new run began", and
                        // multica's stream-json contract treats
                        // `system`/`subtype=init` as the single
                        // session-establishing frame, so a duplicated init
                        // makes both surfaces mis-report the run.
                        //
                        // The engine-side consumers (desktop store, TUI)
                        // already only act on the FIRST RunStart for a run
                        // and treat later ones as a per-turn refresh, which
                        // is why this stayed invisible until a third-party
                        // scheduler read the same stream. Emit once.
                        if (!runStartEmitted) {
                            runStartEmitted = true;
                            emit(new StreamEvent.RunStart(runId, effectiveChatClient.modelId()));
                        }
                        // emit any sub-task transitions BEFORE the LLM
                        // stream starts. On the first turn, this surfaces the
                        // model's initial plan; on subsequent turns, this
                        // surfaces transitions that happened in the previous
                        // tool batch (e.g. a sub_todo_write close).
                        // R750: route through the pendingEvents buffer instead of the
                            // caller's raw action. Called from inside
                            // tryAdvance, a direct action.accept(...)
                            // emits several SubTaskStart/End events in
                            // ONE tryAdvance call, violating the "at most
                            // one event per call" contract and losing them
                            // to a probe-then-pull consumer. Package-
                            // private tests still call the method directly
                            // with their own collector, so the signature is
                            // unchanged.
                            emitSubTaskTransitions(stEvent -> emit(stEvent));
                        // use the snapshotted memory section for this
                        // query, not the live field. effectiveSystem() reads
                        // the live field; we temporarily swap it in via a
                        // local-only path here.
                        String turnSystem = buildSystemForTurn(snapshotMemory, planModeSuffix);
                        // R716: filter orphan tool results before handing
                        // the transcript to the chat client. Some providers
                        // (notably MiniMax-M3's OpenAI-compat endpoint) strictly
                        // validate that every role=TOOL_RESULT message carries
                        // a tool_use_id that appears in a prior assistant
                        // message's tool_calls. The on-disk transcript can
                        // drift out of sync with that invariant when a
                        // content block is truncated during recovery or when
                        // a session is restored across a schema migration —
                        // the result is a 400 with the cryptic error code
                        // 2013 "tool result's tool id(call_X) not found".
                        // Stripping the orphan entries before the wire call
                        // is a no-op for healthy transcripts (every
                        // tool_result has a matching tool_use) and a recovery
                        // path for the drifted ones. The transcript itself
                        // is NOT mutated — only the wire-side copy.
                        List<Message> cleanedTranscript = appState.transcript();
                        // R718-L3: transcript-level tool-result dedup.
                        // Runs BEFORE the strip-oldest pass so a
                        // transcript with 4 identical file_reads of
                        // the same file gets collapsed to 1, leaving
                        // the strip with less work to do. Pure filter,
                        // same-reference fast path when no dupes
                        // exist. Zero API cost. Mirrors the Claude
                        // Code "duplicate tool_result collapse" layer.
                        cleanedTranscript = TranscriptPruner.dedupeToolResults(cleanedTranscript, metricsCollector);
                        // R718-L4: strip-oldest rescue. Cheaper than
                        // the R716 hard cap, runs BEFORE the LLM call
                        // as a "your transcript is over the per-call
                        // limit, drop oldest" guard. Default cap is
                        // 150K chars (~37K tokens — the user's
                        // chosen sweet spot below the declared 1M
                        // window, lowered from R717's 200K per the
                        // "more frequent compression" directive).
                        // Tunable via
                        // AETHERCODE_R717_PRE_CALL_MAX_CHARS.
                        // Same-reference fast path when under cap.
                        // Pure filter, transcript on disk is NOT
                        // mutated. R718-L4 fires here BEFORE the R716
                        // hard cap so a 1700-message transcript gets
                        // reduced to "last 16 tail" with a synthetic
                        // note rather than a blunt "keep last 300"
                        // — the user can see the L4 note in the
                        // transcript, the renderer can attach a
                        // "View original" affordance.
                        cleanedTranscript = TranscriptPruner.stripOldestToFit(
                                cleanedTranscript,
                                TranscriptPruner.DEFAULT_PRE_CALL_MAX_CHARS,
                                TranscriptPruner.DEFAULT_MIN_KEEP,
                                TranscriptPruner.DEFAULT_TRUNCATE_TO_CHARS,
                                metricsCollector);
                        // R719: Session Anchor. Every K turns (default 5)
                        // the engine auto-appends a USER-role
                        // session-anchor to the wire-side transcript.
                        // The anchor carries:
                        //   - cwd
                        //   - last 3 user prompts (one-line summaries)
                        //   - last 3 file paths the model touched
                        //   - current intent (the latest user message
                        //     text, truncated to 200 chars)
                        //   - metadata.kind=session-anchor, anchor=true
                        //
                        // L4 (R719 anchor-preserving) guarantees the
                        // anchor survives every future
                        // stripOldestToFit. So even after 10000+ L4 strips,
                        // the model still has the system message +
                        // every periodic anchor + the last 16
                        // non-anchor messages.
                        Message anchor = maybeEmitSessionAnchor(runCounter.get());
                        if (anchor != null) {
                            // R719: anchor must persist on disk so the
                            // next turn's transcript naturally carries
                            // it. Without the on-disk append, the
                            // anchor only survives the current wire
                            // call and is lost on the next session
                            // load. appendMessage + messageSink keep the
                            // TUI / desktop transcript in sync too.
                            try {
                                appState.appendMessage(anchor);
                            } catch (Throwable ignored) {
                                // If appendMessage throws (e.g. read-only
                                // store in tests), fall back to
                                // wire-side only — the model still
                                // sees it this turn.
                            }
                            messageSink.accept(anchor);
                            cleanedTranscript = appendToList(cleanedTranscript, anchor);
                        }
                        // R716 final: trim the head of the transcript
                        // when it exceeds the hard cap. Default 300
                        // messages / 800K chars. Acts as a defence
                        // in depth behind R717-L4 — R717 keeps the
                        // transcript under the per-call cap; R716
                        // makes sure even the L4 survivor list never
                        // exceeds a single hard wall. The on-disk
                        // transcript is NOT mutated.
                        cleanedTranscript = trimHeadIfOversized(
                                cleanedTranscript,
                                DEFAULT_MAX_TRANSCRIPT_MESSAGES,
                                DEFAULT_MAX_TRANSCRIPT_CHARS);
                        // R716: filter orphan tool results AFTER the
                        // trims. Both trims can leave tail tool_results
                        // whose matching tool_use was dropped from
                        // the head — running stripOrphanToolResults
                        // here drops those. The transcript itself is
                        // NOT mutated — only the wire-side copy. Pure
                        // filter, same-reference fast path.
                        cleanedTranscript = stripOrphanToolResults(cleanedTranscript);
                        currentLlmStream = effectiveChatClient.stream(
                                cleanedTranscript, turnSystem, appState.toolPool());
                        currentLlmIt = currentLlmStream.iterator();
                        pending = new ArrayList<>();
                        stopReason = "end_turn";
                        lastAssistantBlocks = List.of();
                        turnsTaken++;
                        turnHadUsage = false;
                        // R750b: NEVER return true here having delivered
                        // nothing. This branch exists to start a turn
                        // (call the LLM, reset per-turn state); it produces
                        // no consumer-visible event by design, and the
                        // first turn only appeared to work because
                        // `runStartEmitted` had queued a RunStart that
                        // `drainOne` could hand over. From turn 2 onward
                        // there is no RunStart (correctly — it is
                        // run-scoped), the buffer is empty, and a bare
                        // `return true` promises an element that does not
                        // exist.
                        //
                        // That breaks `Spliterators.AbstractSpliterator`:
                        // its iterator caches hasNext()==true, then next()
                        // has nothing to return, and the following
                        // hasNext() re-enters tryAdvance, which now finds
                        // an empty buffer and returns false. The consumer
                        // reads that as a CLEAN DRAIN and exits WITHOUT the
                        // terminal RunEnd — the `no_run_end: engine stream
                        // ended without RunEnd` that made every tool-using
                        // stream-json run terminate as `subtype: error`.
                        //
                        // Looping instead keeps the "more events are
                        // coming" promise honest: the next iteration of
                        // this while(true) parks in `currentLlmIt.next()`
                        // until the model actually produces something, and
                        // returns true only once an event was really
                        // delivered. It re-checks `finished` and the
                        // interrupt flag on every pass, so cancel and
                        // terminal branches still work.
                        continue;
                    }

                    if (currentLlmIt.hasNext()) {
                        StreamEvent ev = currentLlmIt.next();
                        if (ev instanceof StreamEvent.TextDelta td) {
                            emit(td);
                            // R750: deliver any events this turn buffered before
                            // claiming there is more to pull.
                            if (deliveredOrContinue(action)) return true;
                            continue engineLoop;
                        }
                        if (ev instanceof StreamEvent.Usage u) {
                            // record per-call token usage to both
                            // MetricsCollector (for the UI / getMetrics)
                            // and CostTracker (for $ accounting). We don't
                            // forward Usage to the consumer — it's a side
                            // channel, not a model-stream event. If the
                            // chat client doesn't supply a real count, the
                            // post-batch fallback below will estimate.
                            turnHadUsage = true;
                            if (u.inputTokens() > 0 || u.outputTokens() > 0) {
                                if (metricsCollector != null) {
                                    metricsCollector.addTokens(u.inputTokens(), u.outputTokens());
                                }
                                if (costTracker != null) {
                                    try {
                                        costTracker.record(costSessionId(), effectiveChatClient.modelId(),
                                                new org.aethercode.core.cost.CostTracker.Usage(
                                                        u.inputTokens(), u.outputTokens()));
                                    } catch (Throwable t) {
                                        // CostTracker failures must not break the stream.
                                    }
                                }
                            }
                            // R750: deliver any events this turn buffered before
                            // claiming there is more to pull.
                            if (deliveredOrContinue(action)) return true;
                            continue engineLoop;
                        }
                        if (ev instanceof StreamEvent.ToolUseStart tu) {
                            pending.add(new ContentBlock.ToolUseBlock(tu.id(), tu.name(), tu.input()));
                            // R750: remember that the consumer has already
                            // been told about this tool, so the executor's
                            // `Started` event does not announce it a second
                            // time (duplicate tool_use frame on the wire).
                            announcedToolUseIds.add(tu.id());
                            emit(tu);
                            // R750: deliver any events this turn buffered before
                            // claiming there is more to pull.
                            if (deliveredOrContinue(action)) return true;
                            continue engineLoop;
                        }
                        if (ev instanceof StreamEvent.RunStart) {
                            // the engine owns the per-turn RunStart (emitted above
                            // before the LLM call). If a ChatClient implementation also
                            // emits its own RunStart (e.g. a legacy client, a test mock),
                            // drop it here so the consumer never sees a duplicate
                            // "turn started" event. This is purely defensive — the
                            // SpringAiChatClient no longer emits RunStart either.
                            // R750: deliver any events this turn buffered before
                            // claiming there is more to pull.
                            if (deliveredOrContinue(action)) return true;
                            continue engineLoop;
                        }
                        if (ev instanceof StreamEvent.RunEnd re) {
                            // prior round fix: the model-side RunEnd is an INTERNAL signal, not a
                            // stream event for the consumer. Do NOT return here — fall through
                            // to the batch handler below. Without this, a pure-text response
                            // (no tool calls) would re-enter the "Start of a turn" branch on
                            // the next tryAdvance and call the LLM again — producing the
                            // "infinite repeat" symptom (9-10 model calls for a simple
                            // "how are you?"). The model-side RunEnd is consumed here for its
                            // side effects (stopReason, finalBlocks), and the engine emits its
                            // own terminal RunEnd after the batch handling completes.
                            stopReason = re.stopReason();
                            lastAssistantBlocks = re.finalBlocks();
                            // chat clients no longer emit a separate
                            // ToolUseStart event per tool call (the model just
                            // declares its tool calls inside the final
                            // AssistantMessage). To preserve the engine's
                            // "run a batch of tools" behaviour, populate
                            // `pending` from the finalBlocks here so the batch
                            // handler below picks them up.
                            if (pending.isEmpty() && lastAssistantBlocks != null) {
                                for (ContentBlock b : lastAssistantBlocks) {
                                    if (b instanceof ContentBlock.ToolUseBlock tu) {
                                        pending.add(tu);
                                    }
                                }
                            }
                            currentLlmStream = null;
                            // fall through to batch handler
                        } else {
                            // ToolResult, SideNote, or any other event type — pass through.
                            emit(ev);
                            // R750b: only claim success if an event was really
                            // delivered; otherwise keep driving the engine.
                            if (deliveredOrContinue(action)) return true;
                            continue engineLoop;
                        }
                    }

                    // LLM iterator is exhausted OR RunEnd was just processed: handle the batch.
                    currentLlmStream = null;
                    // token-usage fallback. Many chat clients (notably
                    // the MiniMax / spring-ai OpenAI-compatible transport)
                    // don't populate `ChatResponse.getMetadata().getUsage()`
                    // — the model returns the answer but no usage
                    // information. The desktop TokenUsage panel would
                    // otherwise stay at 0 indefinitely. We fall back to a
                    // chars/4 estimate on the assistant's output text +
                    // the full transcript (proxy for input). Runs on
                    // EVERY turn (not just the tool-batch path) so a
                    // pure-text response still gets an estimate.
                    if (!turnHadUsage && metricsCollector != null) {
                        int estOut = estimateAssistantChars(lastAssistantBlocks) / 4;
                        int estIn = estimateInputChars(appState.transcript()) / 4;
                        if (estOut > 0 || estIn > 0) {
                            metricsCollector.addTokens(estIn, estOut);
                            if (costTracker != null) {
                                try {
                                    costTracker.record(costSessionId(), effectiveChatClient.modelId(),
                                            new org.aethercode.core.cost.CostTracker.Usage(estIn, estOut));
                                } catch (Throwable ignored) { }
                            }
                        }
                    }
                    if (!lastAssistantBlocks.isEmpty()) {
                        Message assistantMsg = new Message(null, Role.ASSISTANT, lastAssistantBlocks, null, Map.of());
                        appState.appendMessage(assistantMsg);
                        messageSink.accept(assistantMsg);
                    }
                    if (pending.isEmpty()) {
                        // No tool calls -> check if we should compact before declaring done.
                        if (compactor != null) {
                            var messages = new ArrayList<>(appState.transcript());
                            if (compactor.shouldCompact(messages)) {
                                // emit a "compaction started" SideNote BEFORE
                                // the compactor runs so the desktop can show a
                                // "compacting..." indicator while the LLM call
                                // is in flight. Before the desktop had to
                                // guess at compaction from context-window state
                                // and could only show a post-hoc system
                                // message. Both events use kind="compaction"
                                // so the desktop can match start↔complete by
                                // their kind without needing a per-event id.
                                emit(new StreamEvent.SideNote(
                                        "compaction",
                                        "started: " + messages.size() + " messages"));
                                var spliced = compactor.compact(messages);
                                if (spliced != null && spliced != messages) {
                                    appState.transcript().clear();
                                    for (var mm : spliced) appState.appendMessage(mm);
                                    // also surface the completion as a
                                    // SideNote with the same kind. The
                                    // previous sideNoteSink.accept(...)
                                    // string-only path is kept as a fallback
                                    // for callers that wired it up (none in
                                    // production, but the SDK keeps a null
                                    // sink there).
                                    sideNoteSink.accept("compacted: " + spliced.size() + " messages from " + messages.size());
                                    emit(new StreamEvent.SideNote(
                                            "compaction",
                                            "completed: " + messages.size() + " → " + spliced.size() + " messages"));
                                }
                            }
                        }
                        finished = true;
                        emit(new StreamEvent.RunEnd(stopReason, lastAssistantBlocks));
                        // R750: deliver any events this turn buffered before
                        // claiming there is more to pull.
                        if (!pendingEvents.isEmpty()) return drainOne(action);
                        return true;
                    }

                    List<ContentBlock.ToolUseBlock> batch = List.copyOf(pending);
                    pending = new ArrayList<>();
                    // compute the assistant text length for the
                    // current turn so the detector can spot "long output
                    // without tool calls" patterns.
                    int assistantTextChars = 0;
                    for (ContentBlock b : lastAssistantBlocks) {
                        if (b instanceof ContentBlock.TextBlock t) {
                            assistantTextChars += t.text() == null ? 0 : t.text().length();
                        }
                    }
                    // pre-check the batch with no results yet (the
                    // pre-run signals we know about: same fingerprint, high-
                    // risk tool, long output). Same-error is checked after
                    // the batch runs.
                    //
                    // tiered warning. preInfo.shouldStop() means the
                    // detector has hit WARN_BEFORE_STOP+1 times in a row
                    // — the user has not acked, so we treat it as a real
                    // hard stop and end the run. preInfo.isWarning() means
                    // the detector is at tier 1 or 2 — we emit a SideNote
                    // so the desktop LoopGuardBanner can show "is this a
                    // real loop?" and offer the user a "Continue" button that
                    // calls loopAck to reset the tier, and we keep going.
                    // The user can also let it climb to tier 3 (stop) on
                    // its own if the same pattern keeps firing.
                    if (loopDetector != null) {
                        ProgressLoopDetector.LoopInfo preInfo =
                                loopDetector.recordBatch(batch, java.util.List.of(), assistantTextChars);
                        // R170 (2026-08-29): the user-reported loop-stop bug.
                        // The detector's old "tier 3 = loop_detected = hard
                        // stop" branch above is DEAD CODE. The user explicitly
                        // asked for a user-confirmation flow ("where is the user confirmation?") — silently killing a model on a small task
                        // because its tool calls happened to match a fingerprint
                        // is unacceptable. The detector now only WARNS;
                        // ending the run is reserved for the user (Ctrl-C →
                        // user_interrupt) or the streaming executor's own
                        // error path. The detector's internal tier is still
                        // bumped so the LoopGuardBanner can keep escalating
                        // severity in the UI, but the engine does not act
                        // on it.
                        //
                        // If the user later wants the old hard-stop back, the
                        // simplest path is to gate this block on
                        // `System.getProperty("aethercode.loop.hardStop", "false")
                        //  .equalsIgnoreCase("true")` so the behaviour is
                        // opt-in per daemon, not the surprise default.
                        //
                        // R266h (2026-09-14): carve-out for the
                        // {@code empty_tool_input} pattern. The
                        // soft-warn policy is the right call for
                        // fingerprint-style loops (a real model
                        // sometimes does legitimately repeat the
                        // same fingerprint while iterating on a
                        // long task) and for research_mode (a
                        // model might genuinely be exploring). The
                        // empty-input pattern is different: a model
                        // that emits `bash {}` or `glob {}` 2+ times
                        // in a row is provably not making progress
                        // — the engine's `StreamingToolExecutor`
                        // already rejected the call with a precise
                        // "expected JSON shape" error (R266h) and
                        // the model is unable to translate that
                        // hint into a correct call. Continuing
                        // past the threshold wastes user time and
                        // pollutes the transcript with more "X is
                        // required" tool results. We hard-stop
                        // here and surface a clear RunEnd so the
                        // LoopGuardBanner can show the user
                        // "your model is stuck on empty tool
                        // calls — start a new session or
                        // rephrase" with a one-click "Reset
                        // session" action.
                        if (preInfo != null && "loop_detected".equals(preInfo.kind())
                                && "empty_tool_input".equals(loopDetector.lastLoopKind())) {
                            emit(new StreamEvent.SideNote(
                                    "empty_tool_input",
                                    "the model sent " + loopDetector.emptyInputStreak()
                                            + " consecutive tool calls with empty input. "
                                            + "this is a model-side bug — the engine rejected each call with the expected JSON shape "
                                            + "but the model could not re-emit a correct call. "
                                            + "the run is being ended; please start a new session or rephrase your prompt."));
                            stopReason = "loop_detected: empty_tool_input ("
                                    + loopDetector.emptyInputStreak()
                                    + " consecutive empty tool calls)";
                            finished = true;
                            emit(new StreamEvent.RunEnd(stopReason, List.of()));
                            // R750: deliver any events this turn buffered before
                            // claiming there is more to pull.
                            if (deliveredOrContinue(action)) return true;
                            continue engineLoop;
                        }
                        if (preInfo != null && preInfo.isWarning()) {
                            // tier-1 (soft) warnings are silent.
                            // previously, every tier-1 hit emitted a
                            // SideNote that the TUI surfaced in the
                            // chat with a yellow box + "Tab to expand"
                            // button + "still running" badge. The
                            // user explicitly asked to drop the
                            // tier-1 chatter ("if the impact is small, just drop it"
                            // — the run still continues, the model
                            // is being given a chance to course-correct
                            // on its own). Tier-2 (the actual hard
                            // stop) keeps emitting the existing
                            // SideNote so the LoopGuardBanner can
                            // surface the final decision; that's
                            // the one warning the user MUST see.
                            //
                            // The detector still increments its
                            // internal tier and still escalates to
                            // tier-2 if the pattern persists — this
                            // change only affects the side channel.
                            if (preInfo.tier() >= ProgressLoopDetector.WARN_BEFORE_STOP) {
                                emit(new StreamEvent.SideNote(
                                        preInfo.kind(),
                                        "warn " + preInfo.tier() + "/" + ProgressLoopDetector.WARN_BEFORE_STOP
                                                + " after " + turnsTaken + " turns — "
                                                + preInfo.description()
                                                + (preInfo.summary() == null ? "" : " (" + preInfo.summary() + ")")));
                            } else {
                                // tier-1 only — log at DEBUG so
                                // operators can still see the streak
                                // in the daemon log, but the user
                                // doesn't get a chat notification.
                                LOG.debug("loop-detector tier-1 hit: " + preInfo.description()
                                        + (preInfo.summary() == null ? "" : " (" + preInfo.summary() + ")"));
                            }
                        }
                    }
                    // Use the streaming executor for live event delivery.
                    java.util.Iterator<StreamingToolExecutor.Event> it =
                            streamingExecutor.run(batch, appState).iterator();
                    java.util.List<ProgressLoopDetector.BatchResult> results = new java.util.ArrayList<>();
                    while (it.hasNext()) {
                        StreamingToolExecutor.Event ev = it.next();
                        switch (ev) {
                            case StreamingToolExecutor.Event.Started st -> {
                                // R750: do NOT re-announce a tool the
                                // renderer has already been told about.
                                //
                                // The model-stream pass-through above (the
                                // `ev instanceof StreamEvent.ToolUseStart`
                                // branch) already emitted this exact id /
                                // name / input as soon as the MODEL decided
                                // to call it, which is what makes the tool
                                // card appear instantly in the UI. The
                                // executor's `Started` event carries the
                                // same id and payload, so emitting it again
                                // produced TWO `tool_use` frames per real
                                // tool call. On the R747 Claude stream-json
                                // wire that is a duplicate tool_use id —
                                // exactly the class of protocol defect that
                                // produces "tool result's tool id not
                                // found" style rejections downstream.
                                //
                                // The executor event is still the
                                // authoritative signal for tools that never
                                // streamed a ToolUseStart from the model
                                // (e.g. a tool injected by the todo
                                // controller), so it is emitted ONLY when the
                                // id has not already been announced.
                                if (announcedToolUseIds.add(st.id())) {
                                    emit(new StreamEvent.ToolUseStart(
                                            st.id(), st.name(), st.input()));
                                }
                            }
                            case StreamingToolExecutor.Event.Completed cp -> {
                                Message toolMsg = Message.toolResult(cp.id(), cp.output(), cp.isError());
                                appState.appendMessage(toolMsg);
                                messageSink.accept(toolMsg);
                                emit(new StreamEvent.ToolResult(cp.id(), cp.output(), cp.isError()));
                                // Look up the tool name from the batch by id.
                                String toolName = null;
                                for (ContentBlock.ToolUseBlock b : batch) {
                                    if (cp.id() != null && cp.id().equals(b.id())) {
                                        toolName = b.name();
                                        break;
                                    }
                                }
                                results.add(new ProgressLoopDetector.BatchResult(
                                        cp.id(), toolName, String.valueOf(cp.output()), cp.isError()));
                                // "auto-finish loop detector" — when a
                                // tool call successfully produces real output
                                // (file_write "wrote N bytes" / non-error
                                // shell exit 0 / etc.), reset the loop
                                // detector's tier so the next "thinking"
                                // turn after the write isn't misclassified
                                // as a loop. This is the RAG "I just wrote
                                // 5 files, now I'm thinking about the next
                                // one" signal — the thinking is real work.
                                //
                                // We use the tool name + isError + output
                                // shape (a few well-known success patterns
                                // from built-in tools) to decide. Adding new
                                // patterns is cheap; false positives just
                                // reset the tier to 0 which is always safe.
                                if (loopDetector != null && !cp.isError()
                                        && isProgressSignal(toolName, cp.output())) {
                                    loopDetector.notifyProgress();
                                }
                            }
                            case StreamingToolExecutor.Event.Progress p -> {
                                // forward streamed tool output as a
                                // ToolOutputDelta stream event so the
                                // desktop can stream-append the chunk to
                                // the matching tool event's `output`
                                // field. previously, we only fed the
                                // messageSink (which the SDK's
                                // AetherCodeEngine left as `null`), so
                                // bash tool's line-by-line emit was
                                // silently dropped — the user saw a
                                // blank card for the whole 5s of an
                                // `mvn --version` and only saw the
                                // output after the tool completed. The
                                // user described this as "mvn takes a bit long to run" — the actual runtime
                                // was fine, but with no live feedback
                                // it felt slow.
                                //
                                // Extract the text from the assistant
                                // message. BashTool's emit produces
                                // `Message.assistantText("[out] line")`
                                // or `"[err] line"`. We forward the
                                // raw text including the `[out]/[err]`
                                // prefix so the user can tell stdout
                                // from stderr in the streamed view.
                                String text = progressText(p.msg());
                                if (text != null && !text.isEmpty()) {
                                    emit(new StreamEvent.ToolOutputDelta(p.id(), text));
                                }
                                // Also forward to the legacy messageSink
                                // (kept for any direct TUI consumer that
                                // wired one up — the SDK's
                                // AetherCodeEngine is the canonical
                                // consumer now and reads via the
                                // ToolOutputDelta path).
                                messageSink.accept(p.msg());
                            }
                            case StreamingToolExecutor.Event.BatchEnd ignored -> {
                                // tool batch finished. The TodoWriteTool /
                                // SubTodoWriteTool have already mutated
                                // appState.todoList(); emit any sub-task
                                // transitions before we head back to the LLM
                                // for the next turn. This is the right place
                                // because it batches all the in-batch
                                // sub_todo_write calls into a single diff
                                // pass — the model often updates several
                                // sub-tasks in one batch and we don't want a
                                // separate SubTaskStart/End pair per
                                // transition.
                                // R750: route through the pendingEvents buffer instead of the
                            // caller's raw action. Called from inside
                            // tryAdvance, a direct action.accept(...)
                            // emits several SubTaskStart/End events in
                            // ONE tryAdvance call, violating the "at most
                            // one event per call" contract and losing them
                            // to a probe-then-pull consumer. Package-
                            // private tests still call the method directly
                            // with their own collector, so the signature is
                            // unchanged.
                            emitSubTaskTransitions(stEvent -> emit(stEvent));
                            }
                        }
                    }
                    // post-check. The detector gets the actual results
                    // so it can spot the "same error N times in a row" pattern.
                    //
                    // removed the post-check hard stop too. The
                    // pre-check was already non-stopping, but the
                    // post-check still had `if (postInfo.shouldStop())
                    // { finished = true; return false; }` — a tool
                    // batch that produced `same_error` after a real
                    // result would still kill the run. The detector
                    // still emits its warnings + the LoopGuardBanner
                    // can surface them; ending the run is the user's
                    // call (Ctrl-C → `user_interrupt`).
                    if (loopDetector != null) {
                        ProgressLoopDetector.LoopInfo postInfo =
                                loopDetector.recordBatch(batch, results, assistantTextChars);
                        if (postInfo != null && postInfo.isWarning()) {
                            // same tier-1 silent treatment as
                            // the pre-check (see the pre-check comment
                            // for the user-facing rationale). Tier-2
                            // (the actual stop) keeps emitting the
                            // SideNote so the LoopGuardBanner fires.
                            if (postInfo.tier() >= ProgressLoopDetector.WARN_BEFORE_STOP) {
                                emit(new StreamEvent.SideNote(
                                        postInfo.kind(),
                                        "warn " + postInfo.tier() + "/" + ProgressLoopDetector.WARN_BEFORE_STOP
                                                + " after " + turnsTaken + " turns — "
                                                + postInfo.description()
                                                + (postInfo.summary() == null ? "" : " (" + postInfo.summary() + ")")));
                            } else {
                                LOG.debug("loop-detector tier-1 hit (post): " + postInfo.description()
                                        + (postInfo.summary() == null ? "" : " (" + postInfo.summary() + ")"));
                            }
                        }
                        // hard-stop the run on `same_error` death spiral.
                        //
                        // previously (prior round) the post-check never hard-stopped;
                        // the rationale was "ending the run is the user's
                        // call (Ctrl-C -> user_interrupt)". But that breaks
                        // unattended runs: a model looping on
                        //   file_write -> "timeout: null" -> file_write -> ...
                        // emits 30+ tool calls in a row, errorRate=0.89,
                        // turnsCompleted=0, and the user might not be at the
                        // keyboard. The same-error pattern means the tool is
                        // deterministically broken for this model; no amount
                        // of "thinking" will recover, and the run just keeps
                        // spending tokens.
                        //
                        // We only re-introduce the hard stop for the
                        // `same_error` kind. The other loop kinds
                        // (same_fingerprint, long_output, research_mode)
                        // keep the R171 soft treatment so a model that
                        // legitimately needs many turns of exploration
                        // (e.g. looking at 5 files before writing) isn't
                        // killed mid-task.
                        if (postInfo != null && "same_error".equals(postInfo.kind())
                                && postInfo.shouldStop()) {
                            LOG.warn("R181: hard-stopping run on same_error death spiral (tier={}, turns={}): {}",
                                    postInfo.tier(), turnsTaken, postInfo.description());
                            emit(new StreamEvent.SideNote(
                                    "same_error_hard_stop",
                                    "R181: same tool returned the same error 3+ times in a row — "
                                            + "the run is in a death spiral and is being hard-stopped. "
                                            + "User can re-run after fixing the underlying tool issue. "
                                            + "Reason: " + postInfo.description()));
                            stopReason = "same_error_hard_stop";
                            finished = true;
                            // R735 (2026-10-20): the previous R181 patch
                            // forgot to emit StreamEvent.RunEnd. The other
                            // three terminal branches in this tryAdvance
                            // (cancelled, awaiting_user_decision,
                            // empty_tool_input) all emit RunEnd so the
                            // desktop's stream_event handler can flip
                            // isStreaming=false, populate lastFailedPrompt
                            // (so the EndOfTaskPanel's ↻ retry button
                            // shows), and clear currentQuery. Without
                            // RunEnd, the engine falls into the green
                            // `● Idle` state with no error banner and
                            // no recovery affordance — the user thinks
                            // the engine is still running.
                            //
                            // lastAssistantBlocks is the final assistant
                            // text accumulated for this run (line 1537,
                            // updated at 1771). Carrying it on the RunEnd
                            // lets the MessageList mark the last
                            // assistant message complete (run_end
                            // isComplete=true handler at store/index.ts
                            // :3239) and react-markdown re-parse the
                            // trailing body.
                            emit(new StreamEvent.RunEnd(stopReason, lastAssistantBlocks));
                            // R750: drain the buffer one event per call
                            // BEFORE terminating. `return false` with a
                            // RunEnd still buffered would strand it and
                            // leave the desktop in the green ● Idle state.
                            if (!pendingEvents.isEmpty()) return drainOne(action);
                            return false;
                        }
                    }
                    // per-todo adaptive control. Checked AFTER the loop
                    // detector so a true stuck-loop still wins (we stop
                    // immediately, no point asking the LLM to keep going on
                    // something that's clearly broken). Verdict handling:
                    //   - Continue: keep going.
                    //   - AskLlm: append a synthetic user message that asks
                    //     the LLM to decide; bump the soft threshold.
                    //   - AwaitUser: stop the run and emit
                    //     AwaitUserDecision so the renderer can show a
                    //     special prompt. The next query() call is treated
                    //     as the user's continuation decision.
                    List<String> toolNames = new ArrayList<>();
                    int batchErrors = 0;
                    for (var b : batch) toolNames.add(b.name());
                    for (var r : results) if (r.isError()) batchErrors++;
                    TodoRunController.Verdict verdict = todoController.check(
                            batch, toolNames, batchErrors, appState);
                    if (verdict instanceof TodoRunController.Verdict.AskLlm ask) {
                        emit(new StreamEvent.SideNote("todo-step-bump",
                                ask.summary() + " (threshold now " + ask.newSoftThreshold() + ")"));
                        // Inject the ask prompt as a real user message so the
                        // LLM sees it on the next turn.
                        String prompt = TodoRunController.buildAskLlmPrompt(
                                todoController.currentTodoKey(),
                                todoController.currentTodoStepCount(),
                                ask.newSoftThreshold() - todoController.bumpIncrement(),
                                ask.newSoftThreshold());
                        Message askMsg = Message.userText(prompt);
                        appState.appendMessage(askMsg);
                        messageSink.accept(askMsg);
                        emit(new StreamEvent.SideNote("todo-ask-llm", prompt));
                    } else if (verdict instanceof TodoRunController.Verdict.AwaitUser au) {
                        stopReason = "awaiting_user_decision";
                        finished = true;
                        emit(new StreamEvent.SideNote("todo-await-user",
                                au.summary() + " (after " + todoController.bumpsIssued() + " LLM bumps)"));
                        emit(new StreamEvent.AwaitUserDecision(
                                au.summary(),
                                todoController.currentTodoStepCount(),
                                todoController.softThreshold(),
                                TodoRunController.defaultDecisionOptions()));
                        pendingAwaitSummary = au.summary();
                        awaitingUserDecision = true;
                        emit(new StreamEvent.RunEnd(stopReason, lastAssistantBlocks));
                        // R750: drain the buffer one event per call BEFORE
                        // terminating — a `return false` with the RunEnd
                        // still buffered strands it (green ● Idle).
                        if (!pendingEvents.isEmpty()) return drainOne(action);
                        return false;
                    }
                    // R750b: only claim success if an event was really delivered;
                    // otherwise keep driving the engine.
                    if (deliveredOrContinue(action)) return true;
                            continue engineLoop;
                }

                } catch (Throwable t) {
                    // R735a (2026-10-20): the LLM stream threw mid-iteration
                    // (e.g. upstream 500 from the chat provider, or any
                    // WebClientResponseException that escaped the chat-client
                    // retry loop). Pre-R735a the exception propagated out of
                    // tryAdvance, killing the Spliterator with no terminal
                    // RunEnd emitted — the desktop sat in the green
                    // `● Idle` state with no error banner or retry affordance
                    // because nothing ever arrived on the `stream_event`
                    // channel. Translate the throw into a single RunEnd whose
                    // stopReason is prefixed `runtime_exception` so the
                    // desktop's run_end handler can flip isStreaming=false,
                    // populate lastFailedPrompt, and surface the
                    // `● Failed (reason)` pill that R735 introduced.
                    //
                    // R744 (2026-10-21): this catch is NECESSARY BUT NOT
                    // SUFFICIENT, and the gap is worth naming so the next
                    // reader doesn't trust it alone. A catch can only
                    // translate a throw. It cannot help when the engine is
                    // parked DOWNSTREAM inside `currentLlmIt.next()` →
                    // `SpringAiChatClient`'s Spliterator → `queue.take()`
                    // → the chat client's runner thread parked on a socket
                    // read that never returns. In that shape nothing is
                    // thrown, so this catch is unreachable and the run is
                    // silent. Observed live on daemon PID 23280: the
                    // upstream provider accepted the HTTP request and never
                    // finished the SSE body; the log ended at the last
                    // pre-call line, the transcript stopped writing, and
                    // the desktop showed a green `● Idle`.
                    //
                    // R744 therefore fixes the CAUSE at the only layer that
                    // can see it — the chat client's unbounded first-chunk
                    // read (`SpringAiChatClient.awaitFirstChunk`) — by
                    // bounding that wait so the call falls through to the
                    // R697 retry ladder and ultimately to the R742-hardened
                    // `(RunEnd, POISON)` terminal branch. The contract
                    // below stays: every `return false;` in this method is
                    // preceded by a RunEnd. What it cannot promise is that
                    // `currentLlmIt.next()` ever RETURNS — that is the
                    // chat client's obligation, not the engine's.
                    //
                    // NOTE for future editors: do not reproduce the
                    // stopReason ASSIGNMENT shape inside a comment in
                    // this file. `QueryEngineR735Test` scans the
                    // SOURCE for that pattern and would treat the
                    // prose as a real terminal branch (Lesson 1057).
                    finished = true;
                    stopReason = "runtime_exception: " + t.getClass().getSimpleName()
                            + ": " + (t.getMessage() == null ? "" : t.getMessage());
                    LOG.warn("R735a: LLM stream threw, ending run with {}",
                            stopReason, t);
                    // R750: the terminal RunEnd here is the ONE event that
                    // must NOT go through the pendingEvents buffer. The run
                    // is over and we are about to `return false`; if we
                    // buffered the RunEnd and then drained the OLDEST
                    // buffered event (a ToolUseStart from the failed turn)
                    // and returned, the terminal RunEnd would never reach
                    // the consumer — reproducing the exact green `● Idle`
                    // silent-death that R735a/R744 exist to prevent.
                    //
                    // So: discard any leftover buffered non-terminal events
                    // (the run is failing; partial tool_use frames without
                    // their tool_results would be an orphan-protocol bug),
                    // then deliver the RunEnd DIRECTLY via action.accept and
                    // terminate the Spliterator. This keeps the
                    // "every terminal return is preceded by exactly one
                    // RunEnd via action.accept" contract that
                    // QueryEngineSilentDeathR744Test pins, AND the
                    // "at most one event per tryAdvance" Spliterator
                    // contract — we emit exactly one event here, then stop.
                    pendingEvents.clear();
                    try {
                        action.accept(new StreamEvent.RunEnd(stopReason, List.of()));
                    } catch (Exception ignored) {
                        // downstream may already be closed (WS dropped,
                        // cancelled run); nothing to do.
                    }
                    return false;
                }}
        };
        return StreamSupport.stream(sp, false);
    }

    /** turn a fingerprint like {@code Bash|command=ls;} into a
     *  human-friendly summary like {@code Bash(command=ls)}. Truncates
     *  long args to keep the SideNote message readable. */
    private static String summariseFingerprint(String fp) {
        if (fp == null || fp.isEmpty()) return "(unknown)";
        int sep = fp.indexOf('|');
        if (sep < 0) return fp;
        String name = fp.substring(0, sep);
        String args = fp.substring(sep + 1);
        if (args.length() > 80) args = args.substring(0, 77) + "...";
        return name + "(" + args + ")";
    }

    /** rough output-token estimate based on the assistant's
     *  text length. The chars/4 heuristic is the same one
     *  {@code AutoCompact.shouldCompact} uses; it's good enough
     *  for the "the API didn't return usage" fallback. */
    private static int estimateAssistantChars(List<ContentBlock> blocks) {
        if (blocks == null) return 0;
        int n = 0;
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.TextBlock t && t.text() != null) {
                n += t.text().length();
            }
        }
        return n;
    }

    /** rough input-token estimate from the full transcript.
     *  Counts text + tool result text. Used as a fallback when
     *  the chat client doesn't return usage. */
    private static int estimateInputChars(List<Message> transcript) {
        if (transcript == null) return 0;
        int n = 0;
        for (Message m : transcript) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.TextBlock t && t.text() != null) {
                    n += t.text().length();
                } else if (b instanceof ContentBlock.ToolResultBlock r) {
                    if (r.content() instanceof String s) n += s.length();
                }
            }
        }
        return n;
    }

    // =================================================================
    //  R719: Session Anchor emission
    //  =================================================================
    //
    //  Auto-append a USER-role session-anchor every K turns. The
    //  anchor's metadata carries {@code anchor=true} so the R719
    //  anchor-preserving L4 keeps it across every future strip.
    //
    //  Why a USER-role message and not a SYSTEM-role note?
    //  SYSTEM role is implicitly anchor (the existing
    //  head-locked path), but appending SYSTEM mid-transcript
    //  is semantically wrong (the model sees it as a "global
    //  rule" update). USER-role anchor reads as "a periodic
    //  reminder from the user/system about where we are" — the
    //  model treats it as context, not as a rule rewrite.

    /** R719: if {@code currentTurn} is a multiple of the anchor
     *  stride (default 5), build a session-anchor message and
     *  return it. Returns null otherwise. The anchor is then
     *  appended to the wire-side transcript by the caller.
     *
     *  <p>Anchor content:
     *  <ul>
     *    <li>cwd</li>
     *    <li>last 3 user prompts (one-line summaries)</li>
     *    <li>last 3 file paths the model touched</li>
     *    <li>current intent (latest user message, truncated to 200 chars)</li>
     *  </ul>
     */
    private Message maybeEmitSessionAnchor(int currentTurn) {
        int stride = readAnchorStrideEnv();
        if (stride <= 0) return null;
        if (currentTurn <= 0) return null;
        // R731: at session start (no anchor has ever fired in
        // this process — anchorTurnCounter is still 0), emit
        // an anchor immediately on the first turn so the
        // model sees the cwd context docs from the start.
        //
        // We dropped the pre-R731 (currentTurn % stride != 0)
        // gate: it forced cadence to align with the
        // modulo-stride boundary, which after a boot anchor
        // at turn 1 produced an irregular cadence (1, 10, 15,
        // 20, 25). With the modulo gate dropped, the
        // within-stride window alone controls spacing, so
        // emissions are exactly stride turns apart regardless
        // of where the boot anchor landed (1, 6, 11, 16, 21,
        // 26 when stride=5).
        int last = anchorTurnCounter.get();
        boolean firstEver = last == 0;
        // Only one anchor per stride window. anchorTurnCounter
        // tracks the last turn we fired on — we don't fire
        // twice if the engine retries. R731: the "within
        // stride window" gate does NOT apply to the first
        // ever anchor so the boot anchor is never swallowed
        // by a non-zero anchorTurnCounter from a prior
        // session's daemon process.
        if (currentTurn - last < stride && !firstEver) return null;
        anchorTurnCounter.set(currentTurn);

        List<Message> transcript = appState.transcript();
        StringBuilder body = new StringBuilder();
        body.append("[R719 session-anchor] turn ").append(currentTurn)
                .append(" / ").append(stride).append("-stride.\n");

        // cwd
        Path cwdPath = null;
        try {
            cwdPath = appState.cwd();
            if (cwdPath != null) {
                body.append("cwd: ").append(cwdPath).append('\n');
            }
        } catch (Throwable ignored) {}

        // last 3 user prompts
        body.append("\nLast user prompts:\n");
        java.util.List<String> lastUserTexts = new java.util.ArrayList<>();
        if (transcript != null) {
            for (int i = transcript.size() - 1; i >= 0 && lastUserTexts.size() < 3; i--) {
                Message m = transcript.get(i);
                if (m != null && m.role() == Role.USER && !TranscriptPruner.isAnchor(m)) {
                    String t = m.textContent();
                    if (t != null && !t.isBlank()) {
                        String oneLine = t.replace('\n', ' ').replace('\r', ' ');
                        if (oneLine.length() > 100) oneLine = oneLine.substring(0, 97) + "...";
                        lastUserTexts.add(0, "  - " + oneLine);
                    }
                }
            }
        }
        if (lastUserTexts.isEmpty()) body.append("  (none yet)\n");
        else for (String s : lastUserTexts) body.append(s).append('\n');

        // last 3 file paths the model touched
        body.append("\nRecent files touched:\n");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (transcript != null) {
            for (int i = transcript.size() - 1; i >= 0 && seen.size() < 3; i--) {
                Message m = transcript.get(i);
                if (m == null) continue;
                for (ContentBlock b : m.content()) {
                    if (b instanceof ContentBlock.ToolUseBlock tu && tu.input() instanceof Map) {
                        Object p = ((Map<?, ?>) tu.input()).get("file_path");
                        if (p == null) p = ((Map<?, ?>) tu.input()).get("path");
                        if (p != null) seen.add(p.toString());
                        if (seen.size() >= 3) break;
                    }
                }
            }
        }
        if (seen.isEmpty()) body.append("  (none yet)\n");
        else for (String p : seen) body.append("  - ").append(p).append('\n');

        // R726: list on-disk doc files (plan.md / progress.md /
        // design.md / R###-*.md / q##-*.md / docs/* / .aethercode/*)
        // so the model can read them when it loses the original
        // prompt from the head of the transcript. This is the
        // concrete answer to "if you cannot see it, read
        // on-disk plan/progress docs" hint below.
        int cwdDocCap = readCwdDocCapEnv();
        java.util.List<String> cwdDocs = scanCwdContextDocs(cwdPath, cwdDocCap);
        body.append("\nOn-disk context files (read these to recover state):\n");
        if (cwdDocs.isEmpty()) {
            body.append("  (no plan/progress/design/R-doc/q-prompt files found in cwd)\n");
        } else {
            for (String p : cwdDocs) body.append("  - ").append(p).append('\n');
        }

        // current intent
        body.append("\nCurrent intent: continue the long-running task. The original user ");
        body.append("prompt was at the head of this transcript; if you cannot see it, ");
        body.append("read one of the on-disk context files listed above.\n");

        Message anchor = TranscriptPruner.anchorMessage(body.toString());
        if (metricsCollector != null) {
            metricsCollector.incAnchorInjection();
        }
        LOG.info("R719/R726: emitted session-anchor at turn {} (stride={}, cwdDocs={})",
                currentTurn, stride, cwdDocs.size());
        return anchor;
    }

    // -----------------------------------------------------------------
    //  R726: on-disk context-doc scanner.
    // -----------------------------------------------------------------
    //
    //  maybeEmitSessionAnchor() used to say "if you cannot see it,
    //  read on-disk plan/progress docs to recover state" without
    //  telling the model WHICH files actually exist. R726 fixes
    //  that by scanning cwd + docs/ + .aethercode/memory/ for
    //  known long-task doc filenames and listing the discovered
    //  paths in the anchor body. The model can then `cat` /
    //  `read_file` the right file instead of guessing.
    //
    //  Pattern coverage:
    //    - top-level: plan.md / progress.md / design.md /
    //      tasks.md / TODO.md / README.md / AGENTS.md
    //    - top-level: R###-*.md (R-numbered design docs)
    //    - top-level: r###-*.md / q##-*.md / q##-*.json (Q-prompt docs)
    //    - docs/*.{md,markdown,json,txt}
    //    - .aethercode/{memory,sessions,sdd}/*.{md,markdown,json,txt}
    //
    //  Limit: top CAP entries (default 10). Sort name DESC so the
    //  most recent R-numbered docs appear first. All subdir scans
    //  swallow IOException (a missing or unreadable dir is fine).

    /** R726: max entries to include in the on-disk context-files
     *  hint. Tunable via {@code AETHERCODE_R726_CWD_DOC_CAP} (default
     *  {@link #DEFAULT_CWD_DOC_CAP}). */
    public static final int DEFAULT_CWD_DOC_CAP = 10;

    /** R726: known top-level long-task doc filenames (case
     *  insensitive). Anything not on this list and not matching
     *  the R### / q## patterns is filtered out at the top level —
     *  we don't want to dump thousands of unrelated source files
     *  into the anchor. */
    private static final java.util.Set<String> CWD_KNOWN_TOP_NAMES;
    static {
        java.util.Set<String> s = new java.util.HashSet<>(java.util.Arrays.asList(
                "plan.md", "plan.markdown",
                "progress.md", "progress.markdown",
                "design.md", "design.markdown",
                "task.md", "tasks.md", "todo.md",
                "readme.md", "agents.md"));
        CWD_KNOWN_TOP_NAMES = java.util.Collections.unmodifiableSet(s);
    }
    /** R726: top-level filename matchers for R-numbered design
     *  docs and Q-prompt docs. */
    private static final java.util.regex.Pattern R726_R_PATTERN =
            java.util.regex.Pattern.compile("^[Rr]\\d{3}.*\\.(md|markdown)$");
    private static final java.util.regex.Pattern R726_Q_PATTERN =
            java.util.regex.Pattern.compile("^q\\d{2}[a-z_-]*\\.(md|markdown|json|txt)$");
    /** R726: subdir filename matchers. */
    private static final java.util.regex.Pattern R726_SUBDIR_SUFFIX =
            java.util.regex.Pattern.compile(".*\\.(md|markdown|json|txt)$");
    /** R726: subdir relpaths to scan (relative to cwd, depth-1).
     *  The {@code .aethercode} entry covers project-level memory
     *  files (AGENTS.md at the aethercode root, etc.) that the
     *  fixed memory/sessions/sdd subdirs would otherwise miss. */
    private static final String[] R726_SUBDIRS = new String[] {
            "docs",
            ".aethercode",
            ".aethercode/memory",
            ".aethercode/sessions",
            ".aethercode/sdd"
    };

    /** R726: scan {@code cwd} for known long-task doc files.
     *  Returns up to {@code cap} absolute path strings, sorted by
     *  filename descending so most-recent R-numbered docs surface
     *  first. Returns an empty list when cwd is null, does not
     *  exist, is unreadable, or contains no matching files.
     *
     *  <p>Visible for testing — package-private. */
    static java.util.List<String> scanCwdContextDocs(Path cwd, int cap) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (cwd == null || cap <= 0) return out;
        Path root;
        try {
            root = cwd.toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(root)) return out;
        } catch (Exception ignored) {
            return out;
        }

        // top level
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(root)) {
            java.util.List<Path> topHits = new java.util.ArrayList<>();
            java.util.Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                String fn;
                try {
                    fn = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                } catch (Exception ignored) { continue; }
                if (CWD_KNOWN_TOP_NAMES.contains(fn)
                        || R726_R_PATTERN.matcher(fn).matches()
                        || R726_Q_PATTERN.matcher(fn).matches()) {
                    topHits.add(p);
                }
            }
            // sort by filename string DESC so newest R-numbered files come first
            topHits.sort((a, b) -> {
                String an, bn;
                try {
                    an = a.getFileName().toString();
                    bn = b.getFileName().toString();
                } catch (Exception e) { return 0; }
                return bn.compareTo(an);
            });
            for (Path p : topHits) {
                if (out.size() >= cap) break;
                out.add(p.toString());
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // unreadable top-level — fall through to subdir scan
        }

        // subdirs
        for (String rel : R726_SUBDIRS) {
            if (out.size() >= cap) break;
            Path d;
            try { d = root.resolve(rel); }
            catch (Exception ignored) { continue; }
            if (!java.nio.file.Files.isDirectory(d)) continue;
            java.util.List<Path> sub;
            try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(d)) {
                sub = new java.util.ArrayList<>();
                java.util.Iterator<Path> it = stream.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    String fn;
                    try { fn = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT); }
                    catch (Exception ignoredInner) { continue; }
                    if (R726_SUBDIR_SUFFIX.matcher(fn).matches()) {
                        sub.add(p);
                    }
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                continue;
            }
            sub.sort((a, b) -> {
                String an, bn;
                try { an = a.getFileName().toString(); bn = b.getFileName().toString(); }
                catch (Exception e) { return 0; }
                return bn.compareTo(an);
            });
            for (Path p : sub) {
                if (out.size() >= cap) break;
                out.add(p.toString());
            }
        }

        return out;
    }

    /** R726: read {@code AETHERCODE_R726_CWD_DOC_CAP}. Default
     *  {@link #DEFAULT_CWD_DOC_CAP}. */
    private static int readCwdDocCapEnv() {
        String raw;
        try { raw = System.getenv("AETHERCODE_R726_CWD_DOC_CAP"); }
        catch (SecurityException ignored) { return DEFAULT_CWD_DOC_CAP; }
        if (raw == null || raw.isBlank()) return DEFAULT_CWD_DOC_CAP;
        try { int v = Integer.parseInt(raw.trim()); return v > 0 ? v : DEFAULT_CWD_DOC_CAP; }
        catch (NumberFormatException ignored) { return DEFAULT_CWD_DOC_CAP; }
    }

    /** R719: read AETHERCODE_R719_ANCHOR_STRIDE env, default 5. */
    private static int readAnchorStrideEnv() {
        String raw;
        try { raw = System.getenv("AETHERCODE_R719_ANCHOR_STRIDE"); }
        catch (SecurityException ignored) { return DEFAULT_ANCHOR_STRIDE; }
        if (raw == null || raw.isBlank()) return DEFAULT_ANCHOR_STRIDE;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException ignored) { return DEFAULT_ANCHOR_STRIDE; }
    }

    /** R719: append a single message to a list, returning a new
     *  list (the wire-side transcript is filtered each turn so
     *  we don't mutate appState.transcript() here). */
    private static List<Message> appendToList(List<Message> in, Message m) {
        if (m == null) return in;
        java.util.ArrayList<Message> out = new java.util.ArrayList<>(in == null ? 1 : in.size() + 1);
        if (in != null) out.addAll(in);
        out.add(m);
        return out;
    }
}
