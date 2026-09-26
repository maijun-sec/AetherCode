/**
 * TUI state + reducer.
 *
 * The reducer is pure — given (state, action) it returns a new
 * state. All side effects (RPC calls, file I/O) live in the App
 * component via useEffect / event handlers.
 *
 * The state is a small flat record: a sessionId, a model name,
 * a list of "turns" (one entry per visible line in the
 * scrollback), the in-flight input, and a few booleans for
 * status / spinner animation.
 */

export type Role = "user" | "assistant" | "thinking" | "tool" | "plan" | "system" | "error" | "permission";
export type ToolStatus = "running" | "ok" | "error";
export type AppStatus = "connecting" | "ready" | "thinking" | "streaming" | "running-tool" | "exiting";
/**
 * the four states the daemon connection can be in. Mirrors
 * the ConnectionState in jsonrpc.ts. The status bar renders a
 * colour-coded badge + a one-line hint (e.g. "↻ reconnecting
 * (2/10) — last error: java.lang.RuntimeException") so the
 * user can tell at a glance whether the daemon is alive.
 */
export type ConnectionState = "connecting" | "connected" | "reconnecting" | "disconnected";

export interface Turn {
  id: number;
  role: Role;
  text: string;
  ts: number;
  toolName?: string;
  toolArgs?: string;
  toolStatus?: ToolStatus;
  toolResult?: string;
  planItems?: string[];
  /**
   * per-turn collapse state. When `true`, the renderer shows
   * only the "summary" form (status icon + name for tool turns;
   * the first line for assistant turns; etc.). When `false`, the
   * full content is rendered.
   *
   * Defaults are decided by the reducer when the turn is created —
   * tool and intermediate thinking turns default to `true` (collapsed);
   * final assistant replies default to `false` (expanded).
   */
  collapsed: boolean;
  /**
   * when the turn is collapsed, this is the maximum number
   * of characters of `text` / `toolResult` to surface. The renderer
   * shows the first N chars and a hint like "… (Tab to expand)".
   */
  previewChars: number;
  /**
   * when the tool call started (epoch ms). Null for non-tool
   * turns. The ToolCard shows a live duration timer ("1.4s") when
   * the tool is still running, and the final duration when it ends.
   * Stored on the Turn so the duration survives across re-renders.
   */
  toolStartedAt?: number;
  /**
   * when the tool call ended (epoch ms). Null while running.
   * The ToolCard uses this to compute `toolEndedAt - toolStartedAt`
   * and show the final duration next to the status.
   */
  toolEndedAt?: number;
  /**
   * per-tool "category" — read / write / search / run / agent.
   * Computed from the tool name (e.g. file_read → "read",
   * file_write → "write", bash → "run"). Used by the ToolCard to
   * pick a category icon and color for sensory differentiation.
   */
  toolCategory?: ToolCategory;
  /**
   * when true, the user has pressed 'd' on this card and the
   * ToolCard is showing the full args + result (no truncation).
   * Persisted in the reducer so it survives across re-renders.
   */
  toolExpanded?: boolean;
}

export type ToolCategory = "read" | "write" | "search" | "run" | "agent" | "other";

export interface State {
  sessionId: string;
  model: string;
  status: AppStatus;
  pending: number;
  permissionMode: string;
  turns: Turn[];
  input: string;
  history: string[];
  historyCursor: number;       // index into history, -1 = current draft
  submitting: boolean;
  connected: boolean;
  /**
   * detailed daemon-connection state. Distinct from
   * {@link #connected} (the legacy boolean) because the
   * user benefits from a four-state view: "connecting",
   * "connected", "reconnecting (N/M)", "disconnected".
   * The legacy `connected` boolean is kept for the
   * legacy `disconnect` action so the rest of the
   * reducer doesn't break.
   */
  connectionState: ConnectionState;
  /**
   * how many reconnect attempts have been made in
   * the current cycle. Resets to 0 when the daemon
   * successfully connects. The status bar shows
   * "reconnecting (3/10)" using this number.
   */
  reconnectAttempt: number;
  /**
   * max reconnect attempts before the client
   * gives up. 10 is the default (see JsonRpcClient);
   * a manual forceReconnect (Ctrl-R) resets the counter
   * to 0 so a user retry can extend the budget.
   */
  maxReconnectAttempts: number;
  /**
   * wall-clock ms when the daemon was last seen
   * disconnected (and the reconnect loop kicked in).
   * The status bar uses this to render "disconnected
   * 2 minutes ago" when the daemon has been down for
   * a while.
   */
  lastDisconnectAt: number | null;
  /**
   * tail of the daemon's stderr from the last
   * crash / exit. The status bar shows the first 80
   * chars so the user can see WHY the daemon died
   * (e.g. "java.lang.OutOfMemoryError", "permission
   * denied on .aethercode/"). Cleared on a successful
   * reconnect.
   */
  lastStderrTail: string | null;
  jarPath: string;
  showHelp: boolean;
  inputTokens: number | null;
  outputTokens: number | null;
  totalCostUsd: number | null;
  /** R344: per-model context window in tokens. 0 = unknown. The
   *  Header renders a fill glyph + percentage from this. Sourced
   *  from `getState().contextWindow`. */
  contextWindow: number;
  /** R344: provider name (e.g. "minmax", "openai"). Optional —
   *  the daemon doesn't surface this on the wire yet, so it's
   *  left undefined until a future round adds a `getProviders`
   *  RPC. The Header reserves the slot for forward compatibility. */
  provider?: string;
  /** R344: timestamp (ms since epoch) when the TUI connected to
   *  the current daemon session. Used by the Header to show
   *  elapsed time. `null` until the first `init` event fires. */
  uptimeStartedAt: number | null;
  helpVisible: boolean;
  /**
   * per-session skip-confirmation counter. While this is > 0,
   * the engine is auto-allowing tool calls that would otherwise
   * prompt. Surfaced in the status bar so the user can see how many
   * rounds of "no confirmation" remain. Updated by the
   * `skip_confirmation` JSON-RPC notification.
   */
  skipConfirmationRemaining: number;
  /**
   * skip-confirmation adoption stats. Counts the
   * number of times a skip was consumed, the number of times
   * the user armed a skip, and the total number of user
   * prompts. The TUI footer shows "skip: 4/7 prompts used"
   * when the stats are non-zero.
   */
  skipStats: { consumed: number; armed: number; prompts: number };
  /**
   * low-waterline for the skip-confirmation counter.
   * The TUI shows "skip: N (low!)" when the counter is at
   * or below this threshold. Default 5; <= 0 disables the
   * low-warning UI. Configured per-project via
   * `.aethercode/config.json::skipLowWaterline`.
   */
  skipLowWaterline: number;
  /**
   * last skip-low event snapshot. Surfaced in the
   * StatusBar as "skip: N (low!)" for ~5 seconds after the
   * event, then auto-clears. Used to drive the "low!" badge
   * even when the user just connected and missed the live
   * NOTIFY_SKIP_LOW notification.
   */
  lastSkipLow: { sessionId: string; remaining: number; atMs: number } | null;
  /**
   * permission-mode suggestion from the engine's
   * heuristic suggester. May be null if the suggester
   * failed or the project root is empty. The TUI renders
   * "💡 suggested: ACCEPT_TASK (reasons)" next to the
   * current mode when this is non-null AND the suggested
   * mode differs from the user's current mode.
   */
  permissionModeSuggestion: { mode: string; reasons: string[] } | null;
  /**
   * when `true`, all turns render in their collapsed form
   * regardless of per-turn `collapsed`. Ctrl-O toggles this.
   */
  collapseAll: boolean;
  /**
   * current permission ask the daemon is waiting on. While
   * non-null, the modal is shown and the input prompt is disabled.
   */
  permissionAsk: PermissionAsk | null;
  /**
   * the most recent run's stop reason (e.g. "end_turn",
   * "loop_detected", "max_iterations", "error"). Null until the
   * first run completes. Cleared when a new run starts. The StatusBar
   * shows a colored indicator and the scrollback prepends a banner
   * so the user can never confuse "model is still thinking" with
   * "model stopped because of a loop".
   */
  lastStopReason: string | null;
  /**
   * derived from {@link #lastStopReason} for UI styling.
   *   - "ok"        → normal end (end_turn, end_turn_and_tool)
   *   - "loop"      → loop detector fired (loop_detected, or any
   *                   loop kind from ProgressLoopDetector)
   *   - "max_turns" → hit the per-query turn cap
   *   - "error"     → engine / RPC error
   *   - "empty"     → empty user input
   *   - null        → no run completed yet
   */
  lastStopKind: "ok" | "loop" | "max_turns" | "error" | "empty" | null;
  /**
   * whether the side panel is visible. Toggled with Ctrl+B.
   * Default: false (panel hidden) — the user explicitly opts in.
   */
  sidebarVisible: boolean;
  /**
   * T-6-15: whether the right panel (SessionDetailsPanel +
   * TodoBoard) is visible. Toggled with Ctrl+D. Default: true —
   * the right column is the natural "I'm in a session" view;
   * the user can collapse it for more chat space.
   */
  rightPanelVisible: boolean;
  /**
   * most recent tasks from listTasks RPC. Refreshed every
   * 30s + on every task_state notification. The Sidebar component
   * reads this to render the "tasks" section.
   */
  recentTasks: Array<{ id: string; name: string; status: string; ts: number }>;
  /**
   * whether the search bar is visible. Toggled with Ctrl-F.
   * Default: false (closed). When true, a SearchBar component is
   * rendered above the input box.
   */
  showSearch: boolean;
  /**
   * the active search query. Updated as the user types.
   * Cleared when the search bar closes. */
  searchQuery: string;
  /**
   * indices into state.turns that match the current search
   * query. Recomputed by the SearchBar component on each keystroke.
   * The Scrollback uses this to highlight matching turns. */
  searchMatches: number[];
  /**
   * active toasts (transient notifications). Each toast has
   * a 2-second TTL. Old toasts are removed by a 200ms tick.
   * The ToastStack component renders the top N toasts. */
  toasts: Array<{ id: number; kind: "info" | "ok" | "warn" | "err" | "rpc"; text: string; createdAt: number }>;
  /**
   * active theme name. Components read this and pull the
   * matching palette from themes.ts. Switch with /theme NAME.
   * Default: "default". */
  themeName: "default" | "solarized" | "monokai";
  /**
   * layout preset.
   *   - "full"     : header + scrollback + input + statusbar (default)
   *   - "minimal"  : scrollback + input only (hide header + statusbar)
   *   - "focus"    : scrollback only (hide everything else; max room for content)
   *
   *  Useful for users who want maximum scrollback space. Switch
   *  with /layout NAME. */
  layout: "full" | "minimal" | "focus";
  /**
   * cost budget (USD). When > 0, the StatusBar shows a
   * progress bar that fills as totalCostUsd approaches this
   * value. 0 = no budget (default). Set with /budget <usd>. */
  costBudget: number;
  /**
   * whether the command palette is open. Toggled with Ctrl-P.
   * The palette is a modal that floats above everything else. */
  paletteOpen: boolean;
  /**
   * recent total-cost values, one per completed run.
   * The StatusBar's TokenChart shows a sparkline of these. The
   * list is bounded at 64 entries. */
  recentCosts: number[];
  /**
   * "rewind" state. When the user presses Ctrl-Z (or issues
   * `/rewind`), the engine's transcript is rolled back to a
   * prior user message. The TUI keeps a small history of user
   * prompts (state.history) and the user can pick which to
   * rewind to. */
  rewindTarget: number | null;
  /**
   * named prompt templates ("snippets"). The user can save
   * the current input as a named snippet and re-load it later.
   * Stored in memory only (cleared on restart). For persistence
   * we'd need a ~/.aethercode/snippets.json file — leave for R73
   * (plugin loader). */
  snippets: Record<string, string>;
  /**
   * the most recent error message, if any. The TUI shows
   * this prominently in the scrollback (a dedicated "Error" card
   * with red border). Cleared when the user starts a new run. */
  lastError: { message: string; ts: number; stack?: string } | null;
  /**
   * a rolling buffer of recent daemon log lines. The log
   * viewer (Ctrl-L) reads this. Capped at 200 entries. */
  logBuffer: Array<{ ts: number; level: "info" | "warn" | "err"; message: string }>;
  /**
   * whether the log viewer is visible. Toggled with Ctrl-L. */
  logViewerOpen: boolean;
  /**
   * whether the tutorial overlay is visible. The tutorial is
   * a richer welcome screen shown the first time the user
   * connects AND on demand (e.g. Ctrl-T). */
  tutorialOpen: boolean;
  /**
   * bookmarked turn ids. The user can bookmark a turn via
   * /bookmark <id> and the list is shown in the help overlay. */
  bookmarks: number[];
  /**
   * most recent completed trace spans from the engine.
   * The /trace command fetches via getTraces RPC and dispatches
   * setRecentTraces to land the result here. The render layer
   * formats these for the side note. */
  recentTraces: TraceSummary[];
  /**
   * number of spans the engine has open (started but not
   * yet ended). Surfaced in the /trace panel as a header line
   * so the user can see "something is still running". */
  tracesInFlight: number;
  /**
   * total completed spans retained by the engine at the
   * time of the last /trace call. Useful as a "you've run N
   * queries" counter on the header line. */
  tracesCompleted: number;
  /**
   * the most recently fetched single-trace tree (from
   * {@code getTrace}). The render layer uses the parent linkage
   * to draw `├─` / `└─` connectors. Empty when /trace has not
   * been called with a specific id. */
  spanTree: TraceSummary[];
  /**
   * the traceId of the most recent {@code getTrace}
   * request. Surfaced in the side-note header so the user knows
   * which trace they're looking at. */
  selectedTraceId: string | null;
  /**
   * when true, model "thinking" text (between {@code <think>} and
   * {@code </think>}) renders in a dim, collapsible sub-section of the
   * assistant turn. Default: true — the model emits long reasoning
   * blocks that would otherwise drown the actual answer. Toggle with
   * Ctrl-I.
   */
  showThinking: boolean;
  /**
   * when the daemon asks for permission, we no longer full-screen
   * the modal — we render an inline "decision pending" card. The active
   * ask is still parked in {@code permissionAsk}, but the user can
   * scroll back, copy text from earlier turns, and re-open the input
   * box at any time. The decision keys (A / T / P / U / D / N) work
   * regardless of focus.
   */
  decisionCardExpanded: boolean;
  /**
   * while the model is streaming, we keep a "current mode" — the
   * next text delta either lands in the most recent assistant turn
   * ({@code "reply"}) or in a dedicated "thinking" turn (between
   * {@code <think>} and {@code </think>}). The mode is captured
   * from the {@code text_delta} stream by scanning for the
   * open/close markers.
   */
  streamingMode: "reply" | "thinking";
  /**
   * small buffer held over from the previous text_delta so we can
   * catch a {@code <think>} or {@code </think>} marker that was split
   * across two deltas. Capped at 10 chars. Always live alongside
   * {@link streamingMode}: the parser only looks for a tag inside
   * (this buffer + the new delta), so a split tag is detected.
   */
  tagLookahead: string;
  /**
   * one-line status of the most recent background subagent
   * event. Updated on every `subagent_event` notification. The
   * StatusBar shows this as a small indicator so the user can
   * see "[sag-1] running 2.3s" / "[sag-2] done 1.4s" without
   * having to run a subagent_status RPC. Empty when no
   * subagent has been observed in this session.
   */
  subagentStatus: string;
  /**
   * number of background subagents currently in
   * RUNNING state. Updated by the `subagentEvent` reducer
   * action — the daemon's notification doesn't carry a
   * count, so the TUI maintains it locally by tracking
   * the number of RUNNING vs terminal transitions.
   */
  runningSubagents: number;
  /**
   * full in-memory list of recent subagent jobs,
   * keyed by jobId. The SubagentPanel renders a row per
   * job with role + status + elapsed + (for terminal
   * jobs) the captured result. Capped at
   * {@link MAX_SUBAGENT_JOBS} entries (LRU-evicted; in-
   * flight jobs are never evicted). The TUI maintains
   * this locally from the same subagent_event stream
   * that drives {@link subagentStatus} / {@link
   * runningSubagents} — no extra RPC required.
   */
  subagentJobs: Record<string, SubagentJobView>;
  /**
   * when the user is navigating the SubagentPanel,
   * the id of the row their cursor is on. The panel
   * focuses this row's job (Cancel / Insert actions).
   * Undefined when the panel is closed.
   */
  subagentPanelFocus?: string;
  /**
   * is the SubagentPanel open? Toggled by
   * Ctrl+S. The panel renders inline in the right
   * column; the main chat scrollback is dimmed but
   * still visible behind it.
   */
  subagentPanelOpen: boolean;
  /**
   * T-422 (Phase 5 Round 2): is the ThemePicker modal open?
   * The picker is driven by aethercode-themes' ThemeStore
   * (separate from the legacy 5-palette `themeName` field).
   * Toggle with /theme-pick or Ctrl+Shift+T.
   */
  themePickerOpen: boolean;
  /**
   * T-422: the last theme name the user picked from the
   * ThemePicker. Stored so we can highlight the active row
   * in subsequent picker opens. Doesn't drive the existing
   * `themeName` (legacy 5-palette) — the two systems are
   * independent.
   */
  themesPickedName: string | null;
  /**
   * T-420: whether the AgentSelector modal is open. The picker
   * is a presentational component; the host feeds it the agent
   * catalog (from `agent/list` RPC) and routes the user's pick
   * to `agent/setActive`. Toggle with /agent-pick.
   */
  agentPickerOpen: boolean;
  /**
   * T-420: most recent agent-list fetch. The picker reads this
   * directly so we don't re-fetch every time it opens. Refreshed
   * on the `agent/list` RPC response.
   */
  agents: Array<{ name: string; description?: string; isDefault?: boolean }>;
  /**
   * T-420: persisted default agent. Used by the picker to draw
   * the "(default)" badge. Updated by `agent/setDefault` RPC.
   */
  defaultAgent: string | null;
  /**
   * T-421: whether the EffortPicker modal is open. Toggle with
   * /effort-pick. The host feeds the picker the catalog
   * (model-dependent) and routes the pick to `effort/set`.
   */
  effortPickerOpen: boolean;
  /**
   * T-421: catalog of effort levels the active
   * `provider:model` pair supports. Refreshed when the model
   * changes (the daemon publishes the supported efforts on the
   * `model/info` event).
   */
  efforts: Array<{ label: string; id?: string; description?: string }>;
  /**
   * T-421: the user's persisted default effort. Mirrors
   * `defaultAgent` for the agent picker.
   */
  defaultEffort: string | null;
  /**
   * T-423: whether the CwdSwitcher modal is open. Toggle with
   * /cwd-pick. The user types a path; the host validates it
   * and dispatches `cwd/set` on submit.
   */
  cwdSwitcherOpen: boolean;
  /**
   * T-441: the model's effective input window (maxTokens -
   * maxOutputFloor). Updated by the daemon's `model/info`
   * notification. Used by the embedded ContextMeter in
   * the StatusBar to compute the context-usage ratio. When
   * the daemon hasn't published it yet, the StatusBar
   * defaults to 200_000 (the de-facto "most modern model"
   * floor) so the bar still renders meaningfully.
   */
  contextMaxTokens: number | null;
  /**
   * T-441: timestamp (epoch ms) of the last compact event.
   * Mirrors the same field on the embedded ContextMeter's
   * ContextInfo. The StatusBar forwards it so the meter
   * can show the "↓ compacted" hint even when the
   * meter's own polling missed the event.
   */
  lastCompactTs: number | null;
  /**
   * T-441: when true, the engine has auto-compact disabled
   * for this project. The embedded ContextMeter flips the
   * border to red and surfaces a "⚠ auto-compact off" hint
   * so the user knows the meter is informational only.
   */
  autoCompactDisabled: boolean;

  /**
   * live TODO items for the current session. The host
   * subscribes to `todo_update` notifications on mount and
   * dispatches `setTodos` with the latest snapshot. The
   * right-column TodoBoard (Ctrl+D) reads this so the user
   * can see the agent's plan, the in-progress item, and the
   * progress percentage without opening a panel.
   *
   * Empty when the agent hasn't started planning yet.
   */
  todos: Array<{
    id: string;
    title: string;
    status: "pending" | "in_progress" | "completed" | "cancelled";
    owner?: string | null;
    startedAt?: number | null;
    completedAt?: number | null;
    detail?: string | null;
  }>;
  /**
   * which TODO the engine is currently working on
   * (status=in_progress, first in the snapshot). The
   * TodoBoard highlights this row so the user can see
   * "which step is currently executing" at a glance. Derived from `todos`
   * but cached for O(1) access in the renderer.
   */
  currentTodoId: string | null;

  /**
   * id of the subagent whose transcript is currently
   * being viewed. {@code null} means the primary agent's
   * session. Switching this triggers a `loadSession` RPC
   * in the host (the primary session's turns are kept in
   * state; switching replaces them with the subagent's
   * transcript fetched via `session/show`).
   *
   * switching is via Ctrl+1 (primary) or Ctrl+2..9
   * (subagent 1..8 in the running list). The SubagentPanel's
   * `v` action on a focused row also drives this.
   */
  viewingSubagentId: string | null;
  /**
   * tracks the in-memory history of "I switched to
   * subagent X, then back to primary, then to subagent Y"
   * so the user can pop back with Ctrl+B (or a back action)
   * — mirrors the desktop's SessionListFilter "back to
   * primary" affordance. Empty when the user has only
   * looked at the primary session in this run.
   */
  viewHistory: Array<{ kind: "primary" } | { kind: "subagent"; jobId: string }>;
  /**
   * a flat map of "spawn cards" shown inline in the
   * chat transcript when a subagent is launched. Keyed by
   * the spawn event id (so duplicate dispatches don't
   * double-render). The TUI mirrors the desktop's
   * SubagentSpawnCard with the same collapsed/expanded
   * affordance and "View subagent →" link.
   */
  subagentSpawnCards: Record<string, {
    subagentId: string;
    role: string;
    description: string;
    status: string;
    ts: number;
  }>;
}

/** a single row in the SubagentPanel. Mirrors the
 *  desktop's SubagentJobView so the two surfaces render
 *  the same fields. The full result text is kept so the
 *  user can insert it into the input box without another
 *  round-trip to the engine. */
export interface SubagentJobView {
  jobId: string;
  role: string;
  status: "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  startedAtMs: number;
  endedAtMs?: number;
  elapsedMs: number;
  summary: string;
  /** the captured result text. Only present for
   *  COMPLETED jobs (the engine publishes it on
   *  markCompleted). Used by the "Insert into input"
   *  action. Truncated to 4 KB on store to keep the
   *  panel render snappy. */
  resultText?: string;
  /** most recent streaming partial result.
   *  Updated as the worker emits RUNNING events with
   *  the partial text. Cleared on terminal transitions
   *  (the final result takes over). The SubagentPanel
   *  shows this inline so the user sees a live preview
   *  of in-flight work. Truncated to 4 KB on the
   *  engine side; we do not re-truncate here. */
  partialResult?: string;
  /** T-444: the model the subagent is running on
   *  (`provider:model`). Mirrors the deepagents-code
   *  SubagentRecord.model field. The SubagentPanel
   *  renders this in the row so the user can tell
   *  at a glance which model produced the partial /
   *  final result. */
  model?: string;
  /** T-444: error message, only set on FAILED rows.
   *  Mirrors the deepagents-code SubagentRecord.error
   *  field. The SubagentPanel surfaces the first 80
   *  chars inline so the user can read WHY the
   *  subagent failed without scrolling. */
  error?: string;
}

/** cap on the in-memory subagent list. Matches
 *  the desktop's MAX_SUBAGENT_JOBS so the two surfaces
 *  stay in lockstep. */
export const MAX_SUBAGENT_JOBS = 32;

/** a single trace span in the TUI shape. Mirrors the JSON
 *  returned by getTraces but typed so the reducer / render code
 *  can be statically checked. R79: added {@code parentSpanId} so
 *  the TUI can render a tree. */
export interface TraceSummary {
  traceId: string;
  name: string;
  parentSpanId: string | null;
  startMs: number;
  endMs: number;
  status: "running" | "ok" | "error";
  durationMs: number;
  attrs: Record<string, unknown>;
}

/** a permission ask the daemon sent us. The user must
 *  choose an option (A / D / Y / N) before any further events flow. */
export interface PermissionAsk {
  requestId: string;
  runId: string;
  tool: string;
  input: Record<string, unknown>;
  reason: string;
  riskLevel: "low" | "medium" | "high" | "critical";
}

export type Action =
  | { type: "init"; sessionId: string; model: string; permissionMode: string; jarPath: string; contextWindow?: number; provider?: string }
  | { type: "setInput"; text: string }
  | { type: "setHistory"; history: string[] }
  | { type: "historyUp" }
  | { type: "historyDown" }
  | { type: "submit" }
  | { type: "streamStart" }
  | { type: "streamText"; text: string }
  | { type: "streamToolStart"; name: string; args?: string }
  | { type: "streamToolEnd"; name: string; isError: boolean; result?: string }
  | { type: "streamEnd"; stopReason: string; usage?: { input?: number; output?: number; costUsd?: number } }
  | { type: "sideNote"; kind: string; message: string }
  | { type: "plan"; items: string[] }
  | { type: "log"; message: string }
  | { type: "showHelp"; show: boolean }
  | { type: "exit" }
  | { type: "disconnect" }
  // connection-state updates from the JsonRpcClient's
  // onConnectionState callback. The App component wires
  // these via a useEffect that calls dispatch. The reducer
  // cases below update connectionState + reconnectAttempt +
  // lastDisconnectAt + lastStderrTail. `disconnect` (above)
  // remains as a legacy "user pressed Ctrl-C, exit cleanly"
  // action — it sets connected=false but does NOT enter the
  // reconnect loop.
  | { type: "setConnectionState"; state: ConnectionState; attempt: number; lastError: string | null }
  | { type: "toggleTurn"; id: number }              // prior round
  | { type: "setCollapseAll"; collapsed: boolean }   // prior round
  | { type: "permissionAsk"; ask: PermissionAsk }     // prior round
  | { type: "permissionClear" }                       // prior round
  | { type: "toggleToolExpand"; id: number }          // R34: 'd' to expand a tool card
  | { type: "toggleSidebar" }                         // R37: Ctrl+B shows/hides the side panel
  | { type: "toggleRightPanel" }                     // T-6-15: Ctrl+D shows/hides the right panel (details + todos)
  | { type: "setRecentTasks"; tasks: Array<{ id: string; name: string; status: string; ts: number }> } // R37
  | { type: "showSearch"; show: boolean; query?: string } // R40: Ctrl-F opens/closes the search bar
  | { type: "setSearchQuery"; query: string; matches: number[] } // R40: update the query + match list
  | { type: "pushToast"; kind: "info" | "ok" | "warn" | "err" | "rpc"; text: string } // R41
  | { type: "trimToasts"; now: number } // R41: drop toasts older than 2s
  | { type: "setTheme"; theme: "default" | "solarized" | "monokai" } // R42
  | { type: "setLayout"; layout: "full" | "minimal" | "focus" } // R43
  | { type: "setThemePickerOpen"; open: boolean } // T-422
  | { type: "setThemesPickedName"; name: string | null } // T-422
  | { type: "setAgentPickerOpen"; open: boolean } // T-420
  | { type: "setAgents"; agents: Array<{ name: string; description?: string; isDefault?: boolean }> } // T-420
  | { type: "setDefaultAgent"; name: string | null } // T-420
  | { type: "setEffortPickerOpen"; open: boolean } // T-421
  | { type: "setEfforts"; efforts: Array<{ label: string; id?: string; description?: string }> } // T-421
  | { type: "setDefaultEffort"; effort: string | null } // T-421
  | { type: "setCwdSwitcherOpen"; open: boolean } // T-423
  | { type: "setCostBudget"; usd: number } // R44
  | { type: "setPaletteOpen"; open: boolean } // R47
  | { type: "pushCost"; cost: number } // R48
  | { type: "setRewindTarget"; target: number | null } // R50
  | { type: "saveSnippet"; name: string; body: string } // R51
  | { type: "deleteSnippet"; name: string } // R51
  | { type: "loadSnippet"; name: string } // R51
  | { type: "setLastError"; error: { message: string; ts: number; stack?: string } | null } // R52
  | { type: "pushLog"; level: "info" | "warn" | "err"; message: string } // R56
  | { type: "setLogViewerOpen"; open: boolean } // R56
  | { type: "setTutorialOpen"; open: boolean } // R59
  | { type: "toggleBookmark"; id: number } // R60
  | { type: "setRecentTraces"; traces: TraceSummary[]; inFlight: number; completed: number } // R78
  | { type: "setSpanTree"; traceId: string | null; spans: TraceSummary[] } // R79
  | { type: "setShowThinking"; show: boolean } // R86: toggle dim "thinking" sub-section
  | { type: "setDecisionCardExpanded"; expanded: boolean } // R86: collapse the inline permission card
  | { type: "setPermissionMode"; mode: string } // R87: update the active permission mode (ACCEPT_TASK / DEFAULT / ...)
  | { type: "subagentEvent"; jobId: string; role: string; status: string; elapsedMs: number; atMs: number; summary: string; sessionId?: string; resultText?: string; partialResult?: string } // prior round: background subagent lifecycle. prior round: sessionId for per-session filter. prior round: resultText for COMPLETED jobs. prior round: partialResult for in-flight streaming preview.
  | { type: "openSubagentPanel" } // prior round: Ctrl+S opens the SubagentPanel
  | { type: "closeSubagentPanel" } // prior round: Esc / Ctrl+S again closes
  | { type: "setSubagentPanelFocus"; jobId: string | undefined } // prior round: arrow keys move the cursor
  | { type: "subagentPanelFocusNext" } // prior round: ↓
  | { type: "subagentPanelFocusPrev" } // prior round: ↑
  | { type: "setSkipConfirmationRemaining"; remaining: number } // R99: live skip-confirmation counter from the daemon
  | { type: "setSkipStats"; consumed: number; armed: number; prompts: number } // R106: skip-confirmation adoption stats from getState / getSkipStats
  | { type: "setSkipLowWaterline"; waterline: number } // R108: low-waterline for the skip counter from getState
  | { type: "setLastSkipLow"; snap: { sessionId: string; remaining: number; atMs: number } | null } // R108: NOTIFY_SKIP_LOW snapshot
  | { type: "clearLastSkipLow" } // R112: 5s auto-clear
  | { type: "setPermissionModeSuggestion"; suggestion: { mode: string; reasons: string[] } | null } // R109: cached permission-mode suggestion from getState / getPermissionModeSuggestion

  | { type: "setTodos"; todos: Array<{ id: string; title: string; status: "pending" | "in_progress" | "completed" | "cancelled"; owner?: string | null; startedAt?: number | null; completedAt?: number | null; detail?: string | null }> } // R214: `todo_update` notification snapshot
  | { type: "viewSubagent"; jobId: string } // R214: switch the active transcript to a subagent (triggers loadSession in the host)
  | { type: "viewPrimary" } // R214: switch back to the primary agent (Ctrl+1)
  | { type: "viewHistoryPop" } // R214: pop one entry from viewHistory (the "back" affordance)
  | { type: "addSubagentSpawnCard"; eventId: string; subagentId: string; role: string; description: string; status: string; ts: number } // R214: inline card in the chat transcript
  | { type: "removeSubagentSpawnCard"; eventId: string } // R214: dismiss the spawn card (e.g. the user cleared the chat)
  ;

let turnIdSeq = 1;
export const nextTurnId = () => turnIdSeq++;
let toastIdSeq = 1;
export const nextToastId = () => toastIdSeq++;

export const INITIAL: State = {
  sessionId: "—",
  model: "—",
  status: "connecting",
  pending: 0,
  permissionMode: "DEFAULT",
  turns: [],
  input: "",
  history: [],
  historyCursor: -1,
  submitting: false,
  connected: false,
  // connection-state machine. The reducer is updated
  // by the App component's useEffect that wires the
  // JsonRpcClient's onConnectionState callback.
  connectionState: "connecting" as ConnectionState,
  reconnectAttempt: 0,
  maxReconnectAttempts: 10,
  lastDisconnectAt: null,
  lastStderrTail: null,
  jarPath: "",
  showHelp: false,
  inputTokens: null,
  outputTokens: null,
  totalCostUsd: null,
  contextWindow: 0,
  uptimeStartedAt: null,
  helpVisible: false,
  collapseAll: false,
  permissionAsk: null,
  lastStopReason: null,
  lastStopKind: null,
  sidebarVisible: false,
  rightPanelVisible: false,
  recentTasks: [],
  showSearch: false,
  searchQuery: "",
  searchMatches: [],
  toasts: [],
  themeName: "default",
  layout: "full",
  costBudget: 0,
  paletteOpen: false,
  recentCosts: [],
  rewindTarget: null,
  // live TODO + subagent view state
  todos: [],
  currentTodoId: null,
  viewingSubagentId: null,
  viewHistory: [],
  subagentSpawnCards: {},
  snippets: {},
  lastError: null,
  logBuffer: [],
  logViewerOpen: false,
  tutorialOpen: false,
  bookmarks: [],
  recentTraces: [],
  tracesInFlight: 0,
  tracesCompleted: 0,
  spanTree: [],
  selectedTraceId: null,
  // thinking is on by default (model emits a lot of it). User
  // can Ctrl-I to hide. The collapse-all state still overrides
  // individual turn collapse, so a Ctrl-O collapses thinking too.
  showThinking: true,
  // inline permission card is expanded by default. User can
  // press Esc to collapse it (the input box then takes the focus,
  // and the keys A/T/P/U/D/N are still honoured).
  decisionCardExpanded: true,
  // streaming parser state. Both reset to "reply" / "" on every
  // runStart. The reducer updates them on every streamText action.
  streamingMode: "reply",
  tagLookahead: "",
  // subagent indicator. Empty by default; the
  // StatusBar shows nothing until a subagent_event
  // notification arrives.
  subagentStatus: "",
  runningSubagents: 0,
  // skip-confirmation counter. Starts at 0 (no skipping).
  skipConfirmationRemaining: 0,
  // skip stats. All zero until the daemon replies or a
  // skip happens.
  skipStats: { consumed: 0, armed: 0, prompts: 0 },
  // low-waterline for the skip counter. Default 5;
  // <= 0 disables. Updated from getState (the daemon reads
  // the project's .aethercode/config.json at boot).
  skipLowWaterline: 5,
  // last skip-low event. Null until NOTIFY_SKIP_LOW
  // fires; the StatusBar renders "low!" for 5s after the
  // event.
  lastSkipLow: null,
  // permission-mode suggestion from the engine.
  // Null when the suggester failed or the user already
  // matches the suggestion.
  permissionModeSuggestion: null,
  // in-memory job list for the SubagentPanel.
  // Populated by the same subagentEvent stream that
  // drives subagentStatus / runningSubagents. Empty
  // until the user runs a background subagent.
  subagentJobs: {},
  subagentPanelOpen: false,
  themePickerOpen: false,
  themesPickedName: null,
  // T-420: agent picker + catalog + default.
  agentPickerOpen: false,
  agents: [],
  defaultAgent: null,
  // T-421: effort picker + catalog + default.
  effortPickerOpen: false,
  efforts: [],
  defaultEffort: null,
  // T-423: cwd switcher.
  cwdSwitcherOpen: false,
  // T-441: embedded ContextMeter. `contextMaxTokens`
  // starts null (the StatusBar falls back to 200_000
  // until the daemon publishes `model/info`); the
  // other two default to "no event yet" / "auto-compact
  // on".
  contextMaxTokens: null,
  lastCompactTs: null,
  autoCompactDisabled: false,
};

/** classify a stop reason for the StatusBar. The daemon
 *  emits a small set of stopReason values; we map them to a
 *  four-bucket UI kind (ok / loop / max_turns / error).
 *
 *  Known daemon reason values (MiniMax + OpenAI + engine-internal):
 *  - "end_turn"               → ok (Anthropic / OpenAI standard)
 *  - "stop"                   → ok (MiniMax)
 *  - "tool_calls"             → ok (MiniMax — model wants to call tools)
 *  - "max_tokens" / "length"  → ok (model hit output cap, not an error)
 *  - "loop_detected"          → loop (prior round)
 *  - "max_iterations"         → max_turns (prior round)
 *  - "error" / "rpc_error"    → error
 *  - "empty_input"            → empty
 *  - default                   → ok (we treat unknown reasons as
 *                                "the run finished" — better to show
 *                                a green check than a red X for a
 *                                reason we don't recognise) */
export function classifyStopReason(reason: string | null | undefined): "ok" | "loop" | "max_turns" | "error" | "empty" | null {
  if (reason == null) return null;
  const r = reason.toLowerCase();
  // Loop detector — only the engine's own "loop_detected" or
  // progress-loop-detector variants. Avoid matching arbitrary
  // words containing "loop" (e.g. "main_loop").
  if (r === "loop_detected" || r.startsWith("loop_")) return "loop";
  // Per-query turn cap. NOT "max_tokens" (that's the model-side
  // output cap, which is a normal completion).
  if (r === "max_iterations" || r === "max_turns" || r === "max_tool_iterations") return "max_turns";
  if (r === "empty_input" || r === "empty") return "empty";
  if (r === "error" || r.startsWith("error_") || r.endsWith("_error")) return "error";
  return "ok";
}

/** sensible default collapse state for a new turn.
 *  Tool turns and intermediate "thinking" turns start collapsed;
 *  final assistant text starts expanded. Plan / user / system / error
 *  turns are always shown expanded (they're typically short). */
/** categorise a tool by its name. Drives the icon + color
 *  on the ToolCard so each tool *type* is visually distinct
 *  (read = blue, write = yellow, search = cyan, run = red, etc.).
 *
 *  Categories are coarse by design — we only have 5 icons and we
 *  want the user to identify the *type* of operation, not the
 *  specific tool. A tool with no recognisable name falls back to
 *  "other" and uses the default colour.
 *
 *  Naming convention: aethercode tools are usually verb_noun
 *  (file_read, file_write, bash_run). The categoriser checks
 *  the verb first. */
export function categorizeTool(name: string | undefined | null): ToolCategory {
  if (!name) return "other";
  const n = name.toLowerCase();
  // Read: any tool that surfaces existing data.
  if (n === "file_read" || n === "read" || n.startsWith("read_") || n === "cat" || n === "show" || n === "view") return "read";
  if (n === "glob" || n === "grep" || n === "search" || n.startsWith("search_") || n === "find") return "search";
  // Write: any tool that creates or modifies files.
  if (n === "file_write" || n === "file_edit" || n === "file_create" || n.startsWith("write_") || n.startsWith("edit_")) return "write";
  // Run: shell / process / test execution.
  if (n === "bash" || n === "shell" || n === "exec" || n === "run_command" || n.startsWith("bash_") || n === "test" || n === "build") return "run";
  // Agent: anything that spawns a sub-agent or long-running task.
  if (n === "agent" || n === "task" || n === "delegate" || n.startsWith("agent_") || n.startsWith("task_")) return "agent";
  return "other";
}

/** render a one-line summary of a subagent
 *  transition suitable for the StatusBar. Compact
 *  (<40 chars when possible) because the bar has
 *  limited horizontal space.
 *
 *  Examples:
 *    sag-1, RUNNING, 0      → "[sag-1] running"
 *    sag-1, COMPLETED, 1400 → "[sag-1] done 1.4s"
 *    sag-1, FAILED, 800     → "[sag-1] failed"
 *    sag-1, CANCELLED, 0    → "[sag-1] cancelled"
 */
export function formatSubagentStatus(jobId: string, status: string, elapsedMs: number): string {
  const id = jobId || "?";
  switch (status) {
    case "RUNNING":   return `[${id}] running`;
    case "COMPLETED": return `[${id}] done ${formatDuration(elapsedMs)}`;
    case "FAILED":    return `[${id}] failed`;
    case "CANCELLED": return `[${id}] cancelled`;
    default:          return `[${id}] ${status.toLowerCase()}`;
  }
}

/** Return the id of the most recently started subagent
 *  (the one the SubagentPanel should focus when it
 *  opens). Falls back to the most recently ended
 *  terminal job when no in-flight job exists. Returns
 *  undefined when the list is empty. */
export function mostRecentSubagentId(jobs: Record<string, SubagentJobView>): string | undefined {
  const ids = Object.keys(jobs);
  if (ids.length === 0) return undefined;
  // Sort by startedAtMs desc.
  ids.sort((a, b) => jobs[b].startedAtMs - jobs[a].startedAtMs);
  return ids[0];
}

/** Return job ids in display order: in-flight (running)
 *  first, then terminal jobs newest-first. The SubagentPanel
 *  renders the rows in this order so the user sees the
 *  active work at the top and recent history below. */
export function subagentJobIdsInOrder(jobs: Record<string, SubagentJobView>): string[] {
  const ids = Object.keys(jobs);
  const running: string[] = [];
  const terminal: string[] = [];
  for (const id of ids) {
    const j = jobs[id];
    if (j.status === "RUNNING") running.push(id);
    else terminal.push(id);
  }
  // Stable sort by startedAtMs desc within each bucket.
  running.sort((a, b) => jobs[b].startedAtMs - jobs[a].startedAtMs);
  terminal.sort((a, b) => {
    const ea = jobs[a].endedAtMs ?? jobs[a].startedAtMs;
    const eb = jobs[b].endedAtMs ?? jobs[b].startedAtMs;
    return eb - ea;
  });
  return [...running, ...terminal];
}

/** render a one-line summary of a job for the
 *  SubagentPanel. Slightly wider than the StatusBar's
 *  formatSubagentStatus — the panel has its own
 *  column so we can afford a few more characters. */
export function formatSubagentPanelRow(j: SubagentJobView, now: number): string {
  const id = j.jobId || "?";
  const elapsed = j.status === "RUNNING" && j.endedAtMs == null
    ? Math.max(0, now - j.startedAtMs)
    : j.elapsedMs;
  const dur = formatDuration(elapsed);
  switch (j.status) {
    case "RUNNING":   return `[${id}] ${j.role || "general-purpose"} · running ${dur}`;
    case "COMPLETED": return `[${id}] ${j.role || "general-purpose"} · done ${dur}`;
    case "FAILED":    return `[${id}] ${j.role || "general-purpose"} · failed ${dur}`;
    case "CANCELLED": return `[${id}] ${j.role || "general-purpose"} · cancelled`;
    default:          return `[${id}] ${j.status}`;
  }
}

/** render a duration (ms) as a short human-readable string.
 *  Examples:
 *    123     → "123ms"
 *    1_500   → "1.5s"
 *    65_000  → "1m05s"
 *    7_200_000 → "2h00m"
 *
 *  This is intentionally terse — the tool card has limited width
 *  and the duration is supplementary information, not the focus. */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return "—";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  if (m < 60) return `${m}m${s.toString().padStart(2, "0")}s`;
  const h = Math.floor(m / 60);
  return `${h}h${(m % 60).toString().padStart(2, "0")}m`;
}

function defaultCollapsed(role: Role): boolean {
  switch (role) {
    case "tool":       return true;   // tool call cards start collapsed
    case "assistant":  return false;  // final assistant reply stays open
    case "thinking":   return true;   // R86: dim sub-section, expand on demand
    case "plan":       return false;  // plans are short, keep visible
    case "user":       return false;  // user prompts visible
    case "system":     return false;  // system notes are short
    case "error":      return false;  // errors should be visible
    case "permission": return true;   // R86: collapsed inline card by default
    default:           return false;
  }
}

export function reducer(state: State, action: Action): State {
  switch (action.type) {
    case "init":
      return {
        ...state,
        sessionId: action.sessionId,
        model: action.model,
        permissionMode: action.permissionMode,
        jarPath: action.jarPath,
        contextWindow: action.contextWindow ?? 0,
        provider: action.provider,
        uptimeStartedAt: Date.now(),
        status: "ready",
        connected: true,
      };
    case "setInput":
      return { ...state, input: action.text, historyCursor: -1 };
    case "setHistory":
      return { ...state, history: action.history };
    case "historyUp":
      if (state.history.length === 0) return state;
      return {
        ...state,
        historyCursor: state.historyCursor < 0
          ? state.history.length - 1
          : Math.max(0, state.historyCursor - 1),
        input: state.historyCursor < 0
          ? state.history[state.history.length - 1] ?? ""
          : state.history[Math.max(0, state.historyCursor - 1)] ?? "",
      };
    case "historyDown":
      if (state.historyCursor < 0) return state;
      return {
        ...state,
        historyCursor: state.historyCursor + 1 >= state.history.length ? -1 : state.historyCursor + 1,
        input: state.historyCursor + 1 >= state.history.length
          ? ""
          : state.history[state.historyCursor + 1] ?? "",
      };
    case "submit": {
      if (state.input.trim().length === 0 || state.submitting) return state;
      const prompt = state.input;
      const newHistory = state.history.includes(prompt)
        ? state.history
        : [...state.history, prompt].slice(-200);
      return {
        ...state,
        submitting: true,
        status: "thinking",
        input: "",
        historyCursor: -1,
        history: newHistory,
        // a new run starts — clear the previous stop reason.
        // The StatusBar returns to the "running" indicator until
        // the next run_end.
        lastStopReason: null,
        lastStopKind: null,
        // a new run starts — clear the previous error too.
        lastError: null,
        turns: [
          ...state.turns,
          {
            id: nextTurnId(),
            role: "user",
            text: prompt,
            ts: Date.now(),
            collapsed: defaultCollapsed("user"),
            previewChars: 200,
          },
        ],
      };
    }
    case "streamStart":
      return {
        ...state,
        status: "streaming",
        submitting: true,
        // a fresh run resets the streaming parser. New run =
        // new reply section starting in "reply" mode. The tag
        // lookahead buffer is also dropped.
        streamingMode: "reply",
        tagLookahead: "",
        turns: [
          ...state.turns,
          {
            id: nextTurnId(),
            role: "assistant",
            text: "",
            ts: Date.now(),
            collapsed: defaultCollapsed("assistant"),
            previewChars: 240,
          },
        ],
      };
    case "streamText": {
      // split <think>...</think> content into a separate
      // "thinking" turn. Algorithm:
      //
      //   1. Combine the previous lookahead (text from the prior
      //      delta that might be the start of a tag) with the
      //      new delta into a single string.
      //   2. Scan left to right for the EARLIEST tag (<think> or
      //      </think>) in the combined text. The text BEFORE the
      //      tag is "settled" — it cannot be a tag prefix.
      //   3. Switch mode based on which tag was found. Continue
      //      scanning from after the tag.
      //   4. Stop when no more tags can be found. Any text that
      //      REMAINS at the end is the new lookahead — it might
      //      be the start of a tag in the next delta.
      //   5. streamEnd flushes any leftover lookahead into the
      //      current mode's turn.
      //
      // We use a simple linear scan with `indexOf` rather than a
      // char-by-char walk — the previous char-by-char approach
      // had a subtle off-by-one in the "drop first char" step
      // that produced duplicated text at the end of the run.
      const TAGS = ["<think>", "</think>"] as const;
      let mode: "reply" | "thinking" = state.streamingMode;
      const segments: Array<{ mode: "reply" | "thinking"; text: string }> = [];
      const combined = state.tagLookahead + action.text;
      let cursor = 0;
      while (cursor < combined.length) {
        // Find the next tag. Search from the current cursor.
        let bestIdx = -1;
        let bestTag: typeof TAGS[number] | null = null;
        for (const tag of TAGS) {
          const idx = combined.indexOf(tag, cursor);
          if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
            bestIdx = idx;
            bestTag = tag;
          }
        }
        if (bestTag === null) {
          // No more tags in the combined text. Everything from
          // `cursor` onwards is the new lookahead. The streamEnd
          // action will flush it when the run is over.
          break;
        }
        // Settle the text between cursor and bestIdx.
        if (bestIdx > cursor) {
          const segText = combined.slice(cursor, bestIdx);
          const last = segments[segments.length - 1];
          if (last && last.mode === mode) {
            last.text += segText;
          } else {
            segments.push({ mode, text: segText });
          }
        }
        // Switch mode.
        mode = bestTag === "<think>" ? "thinking" : "reply";
        cursor = bestIdx + bestTag.length;
      }
      // Anything from `cursor` to the end of combined is the new
      // lookahead. The streamEnd action flushes it on run close.
      const tail = combined.slice(cursor);
      // Now apply segments to the turns list. Each segment may
      // append to the most recent matching-role turn or open a
      // new one. The streaming mode is internal ("reply" /
      // "thinking"); the rendered role is "assistant" / "thinking".
      let turns = state.turns;
      const roleOf = (m: "reply" | "thinking"): Role => m === "reply" ? "assistant" : "thinking";
      for (const seg of segments) {
        if (seg.text.length === 0) continue;
        const segRole = roleOf(seg.mode);
        const last = turns[turns.length - 1];
        if (last && last.role === segRole) {
          turns = turns.slice(0, -1).concat([{ ...last, text: last.text + seg.text }]);
        } else {
          // Open a new turn for this mode.
          turns = turns.concat([{
            id: nextTurnId(),
            role: segRole,
            text: seg.text,
            ts: Date.now(),
            // thinking turns start collapsed so the dim
            // sub-section doesn't push the actual answer off
            // screen. The user can expand with the toggle.
            collapsed: seg.mode === "thinking",
            previewChars: seg.mode === "thinking" ? 80 : 240,
          }]);
        }
      }
      return { ...state, turns, streamingMode: mode, tagLookahead: tail };
    }
    case "streamToolStart":
      return {
        ...state,
        status: "running-tool",
        pending: state.pending + 1,
        turns: [
          ...state.turns,
          {
            id: nextTurnId(),
            role: "tool",
            text: action.name,
            toolName: action.name,
            toolArgs: action.args,
            toolStatus: "running",
            ts: Date.now(),
            // track when the tool started so the ToolCard can
            // show a live duration timer and a final duration once
            // the result arrives. toolCategory powers the icon + color
            // (read/write/search/run/agent).
            toolStartedAt: Date.now(),
            toolCategory: categorizeTool(action.name),
            collapsed: defaultCollapsed("tool"),
            previewChars: 80,
          },
        ],
      };
    case "streamToolEnd": {
      const turns = state.turns.slice();
      // Mark the most recent matching tool turn as completed.
      for (let i = turns.length - 1; i >= 0; i--) {
        const t = turns[i];
        if (t.role === "tool" && t.toolName === action.name && t.toolStatus === "running") {
          turns[i] = {
            ...t,
            toolStatus: action.isError ? "error" : "ok",
            toolResult: action.result,
            toolEndedAt: Date.now(),
          };
          break;
        }
      }
      return { ...state, turns, pending: Math.max(0, state.pending - 1) };
    }
    case "streamEnd": {
      // capture the stop reason so the StatusBar can show
      // a colored "run finished" indicator and the scrollback can
      // prepend a banner. The user must not be able to confuse
      // "model stopped because of a loop" with "model is still
      // thinking".
      //
      // also flush any leftover tag-lookahead buffer. If the
      // run ends mid-stream (the model closed the connection or
      // was cut off) there may be text waiting for the next delta
      // that never comes. Emit it into the most recent turn of
      // the matching role — walking BACKWARDS through the turns
      // list because the most recent turn may be a thinking turn
      // (e.g. "<think>A</think>mid1<think>B" ended with thinking,
      // but the leftover text "final" should land in the previous
      // assistant turn, not open a new one).
      const kind = classifyStopReason(action.stopReason);
      let turns = state.turns;
      let leftover = state.tagLookahead;
      if (leftover.length > 0) {
        const role: Role = state.streamingMode === "reply" ? "assistant" : "thinking";
        // Walk backwards to find the most recent turn of the
        // matching role. We slice(0, i) to drop the original
        // turn, then concat the modified version in its place.
        // (slice(0, i+1) was a bug: it kept the original AND
        // appended the modified, doubling the turn.)
        let merged = false;
        for (let i = turns.length - 1; i >= 0; i--) {
          if (turns[i].role === role) {
            turns = turns.slice(0, i)
              .concat([{ ...turns[i], text: turns[i].text + leftover }])
              .concat(turns.slice(i + 1));
            leftover = ""; // consumed
            merged = true;
            break;
          }
        }
        if (!merged) {
          // No matching turn found — open a new one.
          turns = turns.concat([{
            id: nextTurnId(),
            role,
            text: leftover,
            ts: Date.now(),
            collapsed: role === "thinking",
            previewChars: role === "thinking" ? 80 : 240,
          }]);
          leftover = "";
        }
      }
      return {
        ...state,
        turns,
        status: "ready",
        submitting: false,
        inputTokens: action.usage?.input ?? state.inputTokens,
        outputTokens: action.usage?.output ?? state.outputTokens,
        totalCostUsd: action.usage?.costUsd ?? state.totalCostUsd,
        lastStopReason: action.stopReason,
        lastStopKind: kind,
        // drop the lookahead and reset mode — there's no
        // next delta in this run. A future streamStart will
        // re-initialise both.
        tagLookahead: "",
        streamingMode: "reply",
      };
    }

    // (The `leftover` local is no longer needed past this point;
    // it was consumed in the flush above. We keep the variable
    // name to mirror the parse-phase `pending`.)
    // The `turns` local has been mutated by the lookahead flush
    // above; the return value uses it.
    // Unused-but-here: nothing else to do.
    case "sideNote":
      return {
        ...state,
        turns: [
          ...state.turns,
          {
            id: nextTurnId(),
            role: "system",
            text: action.message,
            ts: Date.now(),
            collapsed: defaultCollapsed("system"),
            previewChars: 120,
          },
        ],
      };
    case "plan":
      return {
        ...state,
        turns: [
          ...state.turns,
          {
            id: nextTurnId(),
            role: "plan",
            text: "Plan",
            planItems: action.items,
            ts: Date.now(),
            collapsed: defaultCollapsed("plan"),
            previewChars: 240,
          },
        ],
      };
    case "log":
      return {
        ...state,
        turns: [
          ...state.turns,
          {
            id: nextTurnId(),
            role: "system",
            text: action.message,
            ts: Date.now(),
            collapsed: defaultCollapsed("system"),
            previewChars: 120,
          },
        ],
      };
    case "toggleTurn": {
      const turns = state.turns.map((t) =>
        t.id === action.id ? { ...t, collapsed: !t.collapsed } : t
      );
      return { ...state, turns };
    }
    case "setCollapseAll":
      return { ...state, collapseAll: action.collapsed };
    case "permissionAsk":
      return { ...state, permissionAsk: action.ask, input: "" };
    case "permissionClear":
      return { ...state, permissionAsk: null };
    case "toggleToolExpand": {
      // 'd' on a tool turn toggles the full-content view
      // (no truncation, monospace block). Lives on the Turn so
      // it survives across re-renders and across the
      // collapse-all toggle.
      const turns = state.turns.map((t) =>
        t.id === action.id && t.role === "tool"
          ? { ...t, toolExpanded: !t.toolExpanded }
          : t
      );
      return { ...state, turns };
    }
    case "toggleSidebar":
      // Ctrl+B shows/hides the side panel. The panel is
      // hidden by default; the user opts in. No persistence —
      // restart resets to hidden.
      return { ...state, sidebarVisible: !state.sidebarVisible };
    case "toggleRightPanel":
      // T-6-15: Ctrl+D shows/hides the right column (session
      // details + todo board + tokens). The panel is visible
      // by default.
      return { ...state, rightPanelVisible: !state.rightPanelVisible };
    case "setRecentTasks":
      // replace the recent-tasks list (called after a
      // listTasks RPC). We cap at 20 to bound the panel height.
      return { ...state, recentTasks: action.tasks.slice(0, 20) };
    case "setRecentTraces":
      // replace the recent-traces list (called after a
      // getTraces RPC). Capped at 32 to keep the side note
      // readable; the user can pass /trace 50 if they want
      // more (capped server-side at 256).
      return {
        ...state,
        recentTraces: action.traces.slice(0, 32),
        tracesInFlight: action.inFlight,
        tracesCompleted: action.completed,
      };
    case "setSpanTree":
      // replace the single-trace tree (called after a
      // getTrace RPC). Capped at 64 spans — a single query
      // tree is rarely larger, but we cap defensively in
      // case some future caller nests a lot of tool calls.
      return {
        ...state,
        spanTree: action.spans.slice(0, 64),
        selectedTraceId: action.traceId,
      };
    case "setShowThinking":
      // when true, model <think>...</think> content renders
      // in a dim, collapsible sub-section of the assistant turn.
      // Default on. Ctrl-I toggles.
      return { ...state, showThinking: action.show };
    case "setDecisionCardExpanded":
      // collapse the inline permission card. The user can
      // keep using the input box; the decision keys A/T/P/U/D/N
      // still fire as long as permissionAsk is non-null.
      return { ...state, decisionCardExpanded: action.expanded };
    case "setPermissionMode":
      // update the active permission mode. The header pill
      // re-colors immediately and any pending permission ask
      // (held in state.permissionAsk) is auto-cleared since the
      // mode change usually means a fresh decision is coming
      // from the engine anyway.
      return {
        ...state,
        permissionMode: action.mode,
        permissionAsk: null,
      };
    case "subagentEvent": {
      // a background subagent transition. Update the
      // one-line status the StatusBar shows, and adjust
      // runningSubagents by the transition: RUNNING bumps
      // the count, terminal events (completed / failed /
      // cancelled) drop it back. The status string is
      // compact (<40 chars when possible) because the
      // StatusBar has limited horizontal space.
      //
      // per-session filter. The daemon ships a
      // sessionId on every subagent_event payload. We
      // drop events from other sessions so the StatusBar
      // doesn't show noise from a different session that
      // happens to run in the same daemon. The empty
      // sessionId is the legacy-D wire shape and the
      // "single-session" sentinel — accept it as ours
      // when state.sessionId is also empty (a fresh
      // TUI before getState returns has no session id,
      // so we can't filter yet).
      const evSession = (action as { sessionId?: string }).sessionId ?? "";
      if (evSession !== "" && state.sessionId !== "—" && evSession !== state.sessionId) {
        // Not our session — drop on the floor. The
        // status bar stays untouched; runningSubagents
        // doesn't change.
        return state;
      }
      // also maintain the in-memory job list so
      // the SubagentPanel can render rows. The previous
      // job (if any) is preserved for startedAtMs
      // continuity across transitions; the result text
      // (if any, on COMPLETED) is captured. The list is
      // LRU-evicted at MAX_SUBAGENT_JOBS entries; in-
      // flight jobs are never evicted.
      const prev = state.subagentJobs[action.jobId];
      const nextJob: SubagentJobView = {
        jobId: action.jobId,
        role: action.role || prev?.role || "",
        status: (action.status === "RUNNING"
                  || action.status === "COMPLETED"
                  || action.status === "FAILED"
                  || action.status === "CANCELLED"
                  ? action.status
                  : "RUNNING"),
        startedAtMs: prev?.startedAtMs
                       ?? (action.status === "RUNNING"
                              ? Math.max(0, action.atMs - action.elapsedMs)
                              : action.atMs),
        endedAtMs: (action.status === "COMPLETED"
                     || action.status === "FAILED"
                     || action.status === "CANCELLED")
                     ? action.atMs
                     : prev?.endedAtMs,
        elapsedMs: action.elapsedMs,
        summary: action.summary,
        resultText: action.resultText ?? prev?.resultText,
        // streaming partial result. Update on
        // every RUNNING event so the panel can show
        // a live preview of in-flight work. On
        // terminal transitions we clear the slot
        // (the final result / error takes over).
        partialResult: action.status === "RUNNING"
          ? (action.partialResult ?? prev?.partialResult)
          : undefined,
        // T-444: model + error fields (deepagents-code
        // parity). The model is sticky across the
        // job's lifetime — once the daemon publishes
        // it on the first RUNNING event, every
        // subsequent event preserves it via the
        // `prev?.model` fallback. The error slot is
        // only set on FAILED events and is preserved
        // on subsequent terminal events.
        model: (action as { model?: string }).model
          ?? prev?.model,
        error: action.status === "FAILED"
          ? ((action as { error?: string }).error ?? prev?.error)
          : prev?.error,
      };
      let nextJobs = state.subagentJobs;
      if (!nextJobs[action.jobId]) {
        const ids = Object.keys(nextJobs);
        if (ids.length >= MAX_SUBAGENT_JOBS) {
          // Find the oldest terminal job to evict.
          const terminalIds = ids.filter((id) => {
            const j = nextJobs[id];
            return j.status !== "RUNNING" && j.endedAtMs != null;
          });
          terminalIds.sort((a, b) => {
            const ea = nextJobs[a].endedAtMs ?? 0;
            const eb = nextJobs[b].endedAtMs ?? 0;
            return ea - eb;
          });
          const dropId = terminalIds[0];
          if (dropId) {
            const { [dropId]: _drop, ...rest } = nextJobs;
            nextJobs = rest;
          }
        }
      }
      nextJobs = { ...nextJobs, [action.jobId]: nextJob };
      return {
        ...state,
        subagentStatus: formatSubagentStatus(action.jobId, action.status, action.elapsedMs),
        runningSubagents: state.runningSubagents + (action.status === "RUNNING" ? 1 : action.status === "COMPLETED" || action.status === "FAILED" || action.status === "CANCELLED" ? -1 : 0),
        subagentJobs: nextJobs,
      };
    }
    case "openSubagentPanel":
      return {
        ...state,
        subagentPanelOpen: true,
        // focus the most recent job so the user
        // can press Enter / c immediately.
        subagentPanelFocus: mostRecentSubagentId(state.subagentJobs),
      };
    case "closeSubagentPanel":
      return {
        ...state,
        subagentPanelOpen: false,
        subagentPanelFocus: undefined,
      };
    case "setSubagentPanelFocus":
      return { ...state, subagentPanelFocus: action.jobId };
    case "setSkipConfirmationRemaining":
      // live counter from the daemon. Negative values clamped
      // to 0 so the UI never displays a meaningless "skip: -1".
      return { ...state, skipConfirmationRemaining: Math.max(0, action.remaining) };
    case "setSkipLowWaterline":
      // waterline for the skip counter. <= 0 means
      // disabled; the StatusBar treats the badge as opt-out.
      return { ...state, skipLowWaterline: action.waterline };
    case "setLastSkipLow":
      // snapshot of the last NOTIFY_SKIP_LOW event.
      // Used by the StatusBar to render "skip: N (low!)" for
      // 5s after the event. Null clears the badge.
      return { ...state, lastSkipLow: action.snap };
    case "clearLastSkipLow":
      // 5s auto-clear after the last skip-low event.
      return { ...state, lastSkipLow: null };
    case "setPermissionModeSuggestion":
      // cached permission-mode suggestion from the
      // engine's heuristic suggester. Null when the
      // suggester failed or the suggestion is empty.
      return { ...state, permissionModeSuggestion: action.suggestion };
    case "setSkipStats":
      // adoption stats. Negative values clamped to 0
      // (defensive — the daemon should never send them).
      return {
        ...state,
        skipStats: {
          consumed: Math.max(0, action.consumed),
          armed:    Math.max(0, action.armed),
          prompts:  Math.max(0, action.prompts),
        },
      };
    case "subagentPanelFocusNext":
    case "subagentPanelFocusPrev": {
      const ids = subagentJobIdsInOrder(state.subagentJobs);
      if (ids.length === 0) return state;
      const cur = state.subagentPanelFocus;
      const idx = cur ? ids.indexOf(cur) : -1;
      const next = action.type === "subagentPanelFocusNext"
        ? Math.min(ids.length - 1, idx + 1)
        : Math.max(0, idx - 1);
      return { ...state, subagentPanelFocus: ids[next] };
    }
    case "showSearch":
      // open or close the search bar. When opening, the
      // caller may pass a `query` to seed the input (e.g. when
      // re-opening after a search-n-next). When closing, we
      // also clear searchMatches and searchQuery.
      return {
        ...state,
        showSearch: action.show,
        searchQuery: action.show ? (action.query ?? state.searchQuery) : "",
        searchMatches: action.show ? state.searchMatches : [],
      };
    case "setSearchQuery":
      // SearchBar component fires this on every keystroke.
      return {
        ...state,
        searchQuery: action.query,
        searchMatches: action.matches,
      };
    case "pushToast":
      // append a toast. We cap the array at 8 to bound memory
      // and prevent UI spam if many notifications arrive in
      // rapid succession.
      return {
        ...state,
        toasts: [
          ...state.toasts.slice(-7),
          { id: nextToastId(), kind: action.kind, text: action.text, createdAt: Date.now() },
        ],
      };
    case "trimToasts":
      // drop toasts older than 2 seconds. Called from a
      // 200ms tick effect in tui.tsx.
      return {
        ...state,
        toasts: state.toasts.filter((t2) => action.now - t2.createdAt < 2000),
      };
    case "setTheme":
      // switch the color palette. The reducer doesn't know
      // the palette itself — components read state.themeName and
      // pull the palette via pickPalette().
      return { ...state, themeName: action.theme };
    case "setLayout":
      // switch the layout preset. The tui.tsx render uses
      // this to decide which sub-components to show.
      return { ...state, layout: action.layout };
    case "setCostBudget":
      // set the per-session cost budget (USD). 0 = no
      // budget. The StatusBar reads this and shows a progress
      // bar that fills as totalCostUsd approaches this value.
      return { ...state, costBudget: Math.max(0, action.usd) };
    case "setPaletteOpen":
      // open or close the command palette.
      return { ...state, paletteOpen: action.open };
    case "setThemePickerOpen":
      // T-422: open or close the ThemePicker modal. The picker
      // is driven by aethercode-themes; the legacy 5-palette
      // `themeName` is unaffected.
      return { ...state, themePickerOpen: action.open };
    case "setThemesPickedName":
      // T-422: record the most recent ThemePicker selection so
      // subsequent picker opens highlight the right row.
      return { ...state, themesPickedName: action.name };
    case "setAgentPickerOpen":
      // T-420: open or close the AgentSelector modal.
      return { ...state, agentPickerOpen: action.open };
    case "setAgents":
      // T-420: refresh the agent catalog (from `agent/list` RPC).
      return { ...state, agents: [...action.agents] };
    case "setDefaultAgent":
      // T-420: persist the user's default agent (from
      // `agent/setDefault` RPC). The picker uses this to draw
      // the (default) badge.
      return { ...state, defaultAgent: action.name };
    case "setEffortPickerOpen":
      // T-421: open or close the EffortPicker modal.
      return { ...state, effortPickerOpen: action.open };
    case "setEfforts":
      // T-421: refresh the effort catalog (from
      // `model/info.efforts` on the daemon side).
      return { ...state, efforts: [...action.efforts] };
    case "setDefaultEffort":
      // T-421: persist the user's default effort.
      return { ...state, defaultEffort: action.effort };
    case "setCwdSwitcherOpen":
      // T-423: open or close the CwdSwitcher modal.
      return { ...state, cwdSwitcherOpen: action.open };
    case "pushCost":
      // append a cost entry (called on run_end). Bounded at
      // 64 entries so the sparkline has bounded memory.
      return {
        ...state,
        recentCosts: [...state.recentCosts, action.cost].slice(-64),
      };
    case "setRewindTarget":
      // rewind to a prior user message. The target is the
      // index in the local history; the engine's rewind RPC
      // (added in R50) will be called with this index.
      return { ...state, rewindTarget: action.target };
    case "saveSnippet":
      // save the current input as a named snippet. If a
      // snippet with this name already exists, we overwrite it.
      return { ...state, snippets: { ...state.snippets, [action.name]: action.body } };
    case "deleteSnippet": {
      // delete a named snippet. We re-build the map without
      // the key (more reliable than `delete` with a copy).
      const next: Record<string, string> = {};
      for (const k of Object.keys(state.snippets)) {
        if (k !== action.name) next[k] = state.snippets[k];
      }
      return { ...state, snippets: next };
    }
    case "setLastError":
      // capture the most recent error message. The
      // Scrollback renders a dedicated error card when this is
      // non-null. Cleared on the next submit.
      return { ...state, lastError: action.error };
    case "pushLog":
      // append a log line. We cap at 200 entries; the log
      // viewer only shows the most recent N.
      return {
        ...state,
        logBuffer: [
          ...state.logBuffer.slice(-199),
          { ts: Date.now(), level: action.level, message: action.message },
        ],
      };
    case "setLogViewerOpen":
      // open/close the log viewer.
      return { ...state, logViewerOpen: action.open };

    case "setTodos": {
      // replace the TODO list with the latest snapshot
      // from the daemon's `todo_update` notification. The
      // first in_progress row (by startedAt asc) becomes
      // `currentTodoId` so the renderer can highlight
      // "which step is currently executing" without scanning the list on
      // every render.
      const todos = [...action.todos];
      let currentTodoId: string | null = null;
      const inProgress = todos
        .filter((t) => t.status === "in_progress")
        .sort((a, b) => (a.startedAt ?? 0) - (b.startedAt ?? 0));
      if (inProgress.length > 0) currentTodoId = inProgress[0].id;
      return { ...state, todos, currentTodoId };
    }
    case "viewSubagent": {
      // switch the active transcript to a subagent.
      // The host (tui.tsx) listens for this transition and
      // fires a `loadSession(action.jobId)` RPC; the new
      // transcript replaces the turns in a follow-up
      // `init` action. The previous view (primary or
      // another subagent) is pushed onto viewHistory so
      // the user can pop back with Ctrl+Shift-B.
      return {
        ...state,
        viewingSubagentId: action.jobId,
        viewHistory: [
          ...state.viewHistory,
          state.viewingSubagentId == null
            ? { kind: "primary" as const }
            : { kind: "subagent" as const, jobId: state.viewingSubagentId },
        ],
      };
    }
    case "viewPrimary": {
      // switch back to the primary agent. Push the
      // current view onto history so the user can pop
      // back to a subagent if they want.
      return {
        ...state,
        viewingSubagentId: null,
        viewHistory: state.viewingSubagentId == null
          ? state.viewHistory
          : [
              ...state.viewHistory,
              { kind: "subagent" as const, jobId: state.viewingSubagentId },
            ],
      };
    }
    case "viewHistoryPop": {
      // pop the most recent view from history. If
      // empty, no-op. The host reads the new value of
      // `viewingSubagentId` and either calls `loadSession`
      // (for a subagent target) or restores the primary
      // session (for a "primary" target).
      if (state.viewHistory.length === 0) return state;
      const next = state.viewHistory[state.viewHistory.length - 1];
      return {
        ...state,
        viewHistory: state.viewHistory.slice(0, -1),
        viewingSubagentId: next.kind === "subagent" ? next.jobId : null,
      };
    }
    case "addSubagentSpawnCard": {
      // drop a subagent spawn card into the chat
      // transcript. Keyed by the event id so duplicate
      // dispatches don't double-render. The card stays
      // even after the subagent's status flips to
      // COMPLETED — the user dismisses it via
      // removeSubagentSpawnCard.
      return {
        ...state,
        subagentSpawnCards: {
          ...state.subagentSpawnCards,
          [action.eventId]: {
            subagentId: action.subagentId,
            role: action.role,
            description: action.description,
            status: action.status,
            ts: action.ts,
          },
        },
      };
    }
    case "removeSubagentSpawnCard": {
      // dismiss a spawn card (e.g. on user clear).
      if (!(action.eventId in state.subagentSpawnCards)) return state;
      const { [action.eventId]: _drop, ...rest } = state.subagentSpawnCards;
      return { ...state, subagentSpawnCards: rest };
    }
    // NOTE: openSubagentPanel / closeSubagentPanel /
    // setSubagentPanelFocus / subagentPanelFocusNext /
    // subagentPanelFocusPrev are handled above (right
    // after the subagentEvent case).
    case "setTutorialOpen":
      // open/close the tutorial overlay.
      return { ...state, tutorialOpen: action.open };
    case "toggleBookmark": {
      // add/remove a turn id from the bookmarks list.
      const has = state.bookmarks.includes(action.id);
      return {
        ...state,
        bookmarks: has
          ? state.bookmarks.filter((b) => b !== action.id)
          : [...state.bookmarks, action.id],
      };
    }
    case "showHelp":
      return { ...state, helpVisible: action.show };
    case "exit":
      return { ...state, status: "exiting" };
    case "disconnect":
      return { ...state, status: "exiting", connected: false };
    // connection-state machine. The status bar reads
    // {connectionState, reconnectAttempt, lastDisconnectAt,
    // lastStderrTail} to render the connection badge.
    case "setConnectionState":
      return {
        ...state,
        connectionState: action.state,
        reconnectAttempt: action.attempt,
        // The legacy `connected` boolean is also flipped for
        // any code path that still reads it (most don't after
        // R165, but legacy actions like the InputBox's
        // submit gate still do).
        connected: action.state === "connected",
        lastDisconnectAt:
          (action.state === "reconnecting" || action.state === "disconnected")
            ? Date.now()
            : (action.state === "connected" ? null : state.lastDisconnectAt),
        lastStderrTail:
          (action.state === "reconnecting" || action.state === "disconnected")
            ? (action.lastError ? action.lastError.slice(-512) : null)
            : (action.state === "connected" ? null : state.lastStderrTail),
      };
    default:
      return state;
  }
}
