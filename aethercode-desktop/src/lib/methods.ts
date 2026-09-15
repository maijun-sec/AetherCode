// Typed wrapper for the 17 R80 daemon RPC methods.
// All calls go through the Rust backend (rpc_call Tauri command) which owns
// the WebSocket — the renderer never opens a raw WebSocket itself.

import { invoke } from '@tauri-apps/api/core';
import { listen, UnlistenFn } from '@tauri-apps/api/event';

/**
 * one RPC round-trip record. Emitted by
 * AetherCodeRpc.call() after the promise settles
 * (success or failure). The store's RPC diagnostic
 * panel reads the last N of these. The `params`
 * field is a JSON-safe shallow clone of what the
 * caller sent; binary blobs are not preserved (the
 * raw `invoke` payload in the underlying IPC is
 * beyond the panel's interest).
 */
export interface RpcEvent {
  method: string;
  params: unknown;
  durationMs: number;
  success: boolean;
  error?: string;
  /** Wall-clock ms when the call started. Lets
   *  the panel sort by recency. */
  ts: number;
}

/**
 * a tagged method descriptor returned by
 * the daemon's {@code /api/methods} endpoint.
 * The tags power the R121 RpcCommandPalette's
 * chip-bar filter ("show me engine writes",
 * "show me loop RPCs") and a future
 * group-collapsed view. The fixed tag strings
 * are mirrored in Java
 * {@code AetherCodeMethods.METHOD_TAGS} — a
 * refactor that adds a new tag there without
 * adding it here would render as a "no
 * matches" chip.
 */
export interface RpcMethodInfo {
  name: string;
  tags: string[];
}
/** The 14 fixed tag strings the daemon emits
 *  via {@code /api/methods}. Kept in sync with
 *  {@code AetherCodeMethods.TAG_*}. The list
 *  is duplicated in the daemon's
 *  {@code /api/methods} response as a separate
 *  {@code tags} array for the renderer's
 *  pre-render. */
export const RPC_TAGS = [
  'read', 'write', 'engine', 'session',
  'permission', 'loop', 'tools', 'workflow',
  'memory', 'task', 'skill', 'agent', 'project',
  'diagnostic',
] as const;

export interface EngineState {
  sessionId: string;
  model: string;
  permissionMode: string;
  toolCount: number;
  contextWindow: number;
  contextUsed?: number;
  // per-session skip-confirmation counter. When > 0, the
  // engine is auto-allowing tool calls that would otherwise
  // prompt. Surfaced in the StatusBar so the user can see how
  // many "no confirmation" rounds remain.
  skipConfirmationRemaining?: number;
  // skip-confirmation adoption stats. Counts how often
  // the user has armed and consumed skips, plus the total
  // prompt count. Surfaced in the StatusBar so the user can
  // see "skip: 4/7 prompts used".
  skipStats?: {
    consumed: number;
    armed: number;
    prompts: number;
    adoption: number;
    byTool?: Record<string, number>;
  };
  // low-waterline for the skip counter. When the
  // counter drops to or below this number, the StatusBar
  // shows the "low!" badge so the user knows to re-arm.
  // <= 0 disables.
  skipLowWaterline?: number;
  // snapshot of the last NOTIFY_SKIP_LOW event. Lets
  // a reconnecting client show "skip running low" for 5s
  // after the live event, even if it missed the notification.
  // The full shape includes the sessionId + remaining count
  // so a status pill can show the value alongside the timestamp.
  lastSkipLow?: { sessionId: string; remaining: number; atMs: number } | null;
  // cached permission-mode suggestion from the engine's
  // heuristic suggester. Surfaced next to the current mode so
  // the user can accept the suggestion via the existing
  // setPermissionMode RPC.
  permissionModeSuggestion?: { mode: string; reasons: string[] } | null;
  // loop-detector thresholds. -1 on either axis
  // means "disabled" (the engine's QueryEngine maps
  // any non-positive value to -1). The Settings
  // panel reads these to render the slider state and
  // pushes back via setLoopDetectorThresholds().
  loopWindow?: number;
  loopThreshold?: number;
  // current provider name (set by
  // listProviders / switchProvider). The Settings
  // panel highlights this row in the picker so the
  // user can see what's currently driving the engine.
  provider?: string;
}

export interface ToolInfo {
  name: string;
  description: string;
  parameters?: Record<string, unknown>;
}

/** per-session task summary. The
 *  user explicitly asked for "a summary
 *  regardless of whether the task ended
 *  correctly" — the daemon always
 *  returns a fully-populated SessionSummary
 *  on every poll. The TUI's End-of-task
 *  panel and the loop-detected banner
 *  both consume this shape. */
export interface SessionSummary {
  ok: boolean;
  sessionId: string;
  files_written: number;
  files_read: number;
  shell_calls: number;
  total_tool_calls: number;
  queries: number;
  state: string;
  last_error: string;
  by_tool: Record<string, number>;
  summary_text: string;
  started_at_ms: number;
  last_activity_at_ms: number;
  duration_ms: number;
}

/**
 * per-tool permission assessment from the daemon's
 * {@code listToolActions} RPC. The {@code defaultAction} is
 * what the matrix would return for the tool's typical call
 * shape; {@code isSafe} is true when that action is ALLOW.
 * The UI uses the safe flag to render a "✓" badge in the
 * tool list.
 */
export interface ToolActionInfo extends ToolInfo {
  defaultOpKind: string;
  samplePath: string | null;
  isReadOnly: boolean;
  defaultAction: 'ALLOW' | 'ASK' | 'DENY';
  isSafe: boolean;
}

/** one entry from the daemon's {@code listSkills}
 *  RPC. The shape matches
 *  {@code org.aethercode.core.skill.SkillRegistry.SkillMeta}. */
export interface SkillMeta {
  name: string;
  description: string;
  descriptionZhHans: string;
  displayName: string;
  displayNameZhHans: string;
  source: 'user' | 'project';
  lastModifiedMs: number;
}

/** one entry from the daemon's {@code listAgents}
 *  RPC. The shape matches
 *  {@code org.aethercode.core.agent.AgentRegistry.AgentMeta}.
 *  prior round added the `model` field so the Settings panel can
 *  show the agent's per-agent model binding without an extra
 *  round-trip. */
export interface AgentMeta {
  name: string;
  description: string;
  displayName: string;
  /** "provider/model" string. Empty = use the engine's
   *  current model. */
  model: string;
  lastModifiedMs: number;
}

export interface SessionInfo {
  id: string;
  name?: string;
  /** cwd the session is bound to. Sessions on
   *  the same cwd share project-level memory. The
   *  LeftPanel groups sessions by cwd so the user
   *  can see "all my work on /project-a" as one
   *  group. Populated by the daemon's listSessions
   *  when the caller passes { withCwd: true }. */
  cwd?: string;
  createdAt?: number;
  lastUsedAt?: number;
  messageCount?: number;
  // optional preview text (first
  // user message, capped at 200 chars).
  // Populated when the caller passes
  // { withPreview: true } to listSessions.
  preview?: string;
  /** R270 (2026-09-15) — most recent agent activity,
   *  rendered as a one-line summary. Format:
   *    tool_use  → "<tool_name> <input_path_tail>"
   *    text only → "<first 80 chars of last assistant text>"
   *  Populated alongside `preview` when the caller passes
   *  { withPreview: true } to listSessions. The desktop
   *  SessionListRow uses this to show a Claude Code /
   *  OpenCode style two-line summary:
   *    [first user prompt]
   *    → last agent action
   *  An empty string means the session is brand-new (no
   *  assistant message yet). */
  lastAgentEvent?: string;
}

export interface TaskInfo {
  id: string;
  /** matches the engine's TaskType enum
   *  (lowercased). USER / AGENT / SUBAGENT / SYSTEM. */
  type: 'user' | 'agent' | 'subagent' | 'system';
  /** matches the engine's TaskStatus enum
   *  (lowercased). PENDING / RUNNING / COMPLETED /
   *  FAILED / KILLED. The legacy-3 status set
   *  included 'done' and 'cancelled' which mapped
   *  to COMPLETED and KILLED respectively; the
   *  daemon normalises both directions. */
  status: 'pending' | 'running' | 'completed' | 'failed' | 'killed';
  description: string;
  parentTaskId?: string;
  createdAtMs: number;
  endedAtMs: number;
  result?: string;
  error?: string;
}

export interface ProjectInfo {
  id: string;
  name: string;
  path: string;
  active?: boolean;
  lastUsedAt?: number;
}

export interface MetricsSnapshot {
  /** R77 alias for {@code turnsStarted} on the Java side. */
  totalQueries: number;
  /** R77 alias for {@code toolCalls}. */
  totalToolCalls: number;
  /** cumulative input tokens across all LLM calls. The chat
   *  client emits a {@code Usage} event after each call; the engine
   *  accumulates the numbers in {@code MetricsCollector}. */
  inputTokens?: number;
  /** cumulative output tokens. */
  outputTokens?: number;
  /** convenience — input + output. The desktop TokenUsage
   *  panel prefers the split view; this stays for backward
   *  compatibility with any older consumer. */
  totalTokens?: number;
  cacheHitRate?: number;
  uptime?: number;
  costUsd?: number;
}

export interface TraceSummary {
  traceId: string;
  name: string;
  startMs: number;
  endMs?: number;
  status: 'ok' | 'error' | 'running';
  attrs?: Record<string, unknown>;
  parentSpanId?: string | null;
}

export interface SpanNode {
  traceId: string;
  name: string;
  startMs: number;
  endMs?: number;
  status: 'ok' | 'error' | 'running';
  attrs?: Record<string, unknown>;
  parentSpanId?: string | null;
  children: SpanNode[];
}

export interface DaemonInfo {
  port: number;
  wsUrl: string;
  httpUrl: string;
  spawned: boolean;
  jarPath: string;
  /** The cwd the daemon is serving. R82+: set by the Rust side
   *  so the renderer can compare to the user's chosen path. */
  cwd?: string;
}

/** R82+ Issue 3: info about a pre-warmed "sibling" daemon kept
 *  hot for a sub-second cwd swap. Same shape as DaemonInfo but
 *  with a guaranteed `cwd` field (used to match against
 *  setCwd requests). */
export interface PreWarmInfo {
  port: number;
  wsUrl: string;
  httpUrl: string;
  spawned: boolean;
  jarPath: string;
  cwd: string;
}

export interface ModelInfo {
  id: string;
  inputPer1k: number;
  outputPer1k: number;
  default: boolean;
}

/** one provider entry from the daemon's
 *  listProviders RPC. Mirrors the engine's
 *  ProviderSpec / ModelSpec. The renderer
 *  surfaces this in the Settings provider
 *  picker; the user picks a row to drive
 *  switchProvider. */
export interface ProviderInfo {
  name: string;
  type: string;
  baseUrl: string;
  apiKeyEnv: string;
  defaultModel: string | null;
  models: {
    id: string;
    inputPer1k: number;
    outputPer1k: number;
    context: number;
    default: boolean;
  }[];
}

/** parsed workflow metadata returned by `listWorkflows` /
 *  `getWorkflow`. The `raw` field is the full YAML body, only
 *  populated for `getWorkflow`. The `steps[]` is the step list
 *  with id + type, which is enough for the input bar's
 *  progress pill (no need to expose the full executor schema
 *  to the renderer). */
export interface WorkflowDoc {
  name: string;
  description: string;
  inputs: string[];
  steps: WorkflowStep[];
  stepCount: number;
}
export interface WorkflowStep {
  id: string;
  type: string;
  /** per-step `continue_on_error: true` flag. When true,
   *  a failure in this step is downgraded to "skipped" and the
   *  workflow keeps advancing. Default false. */
  continueOnError?: boolean;
}

export type NotificationHandler = (params: unknown) => void;

export class AetherCodeRpc {
  private handlers = new Map<string, Set<NotificationHandler>>();
  private unlisten: UnlistenFn | null = null;
  // RPC event observers. Every call() emits a
  // {method, params, durationMs, success, error?, ts}
  // event after the round-trip completes. The store
  // subscribes via onRpcEvent() and appends to a
  // capped array — the RpcDiagnosticsPanel reads
  // that array. The hook is intentionally NOT
  // coupled to the store (the rpc class is a
  // transport, the store is a domain model) so
  // future consumers (e.g. a dedicated TUI
  // logger) can also subscribe. A Set keeps
  // unsubscribed handlers from leaking (a
  // disconnected store should not pin a dead
  // closure).
  private rpcEventHandlers = new Set<(e: RpcEvent) => void>();

  // subscribe to RPC events. Returns an
  // unsubscribe function. Used by the store; the
  // panel reads from the store, not directly from
  // the rpc, so the rpc has no UI coupling.
  onRpcEvent(handler: (e: RpcEvent) => void): () => void {
    this.rpcEventHandlers.add(handler);
    return () => { this.rpcEventHandlers.delete(handler); };
  }

  // emit to all observers. Swallows
  // individual handler errors so a buggy observer
  // can't take down the call() path.
  private emitRpcEvent(e: RpcEvent) {
    for (const h of this.rpcEventHandlers) {
      try { h(e); } catch { /* ignore */ }
    }
  }
  async start(): Promise<void> {
    if (this.unlisten) return;
    this.unlisten = await listen<{ method: string; params: unknown }>('ws-notify', (event) => {
      const { method, params } = event.payload;
      const set = this.handlers.get(method);
      if (!set) return;
      for (const h of set) {
        try { h(params); } catch (e) { console.error('notif handler threw:', e); }
      }
    });
  }

  async stop(): Promise<void> {
    if (this.unlisten) { this.unlisten(); this.unlisten = null; }
    this.handlers.clear();
  }

  async call<T = unknown>(method: string, params?: unknown): Promise<T> {
    // capture start time + a JSON-safe
    // shallow clone of the params so the diagnostic
    // panel can show what the caller sent. We
    // intentionally don't deep-clone (a
    // 200-field sessionId-bearing object would be
    // expensive to serialise) — a shallow clone
    // via JSON round-trip is good enough for the
    // human-readable panel.
    const startedAt = performance.now();
    const ts = Date.now();
    let paramsSnapshot: unknown = null;
    try {
      paramsSnapshot = params ? JSON.parse(JSON.stringify(params)) : null;
    } catch {
      // Params had a non-serialisable field (function,
      // circular ref). The panel falls back to a
      // "[unserialisable]" placeholder.
      paramsSnapshot = '[unserialisable]';
    }
    try {
      // daemon's strict JSON-RPC handler rejects
      // `params: null` with -32602 (every method
      // requires a params object, even an empty one).
      // Default to `{}` so callers that omit the second
      // arg still get a valid params object — same
      // fix as the tui's `jsonrpc.request()`.
      const result = await invoke<T>('rpc_call', { method, params: params ?? {} });
      const durationMs = Math.round(performance.now() - startedAt);
      this.emitRpcEvent({ method, params: paramsSnapshot, durationMs, success: true, ts });
      return result;
    } catch (e: any) {
      const durationMs = Math.round(performance.now() - startedAt);
      this.emitRpcEvent({
        method,
        params: paramsSnapshot,
        durationMs,
        success: false,
        error: e?.message ?? String(e),
        ts,
      });
      throw e;
    }
  }

  on(method: string, handler: NotificationHandler): () => void {
    let set = this.handlers.get(method);
    if (!set) { set = new Set(); this.handlers.set(method, set); }
    set.add(handler);
    return () => set!.delete(handler);
  }

  ping(): Promise<{ ok: true; timestamp: number }> { return this.call('ping'); }
  getState(): Promise<EngineState> { return this.call('getState'); }
  listTools(): Promise<{ tools: ToolInfo[] }> { return this.call('listTools'); }
  // per-tool permission action assessment. The UI uses
  // `tools[i].isSafe` to render a "safe" badge.
  listToolActions(): Promise<{ tools: ToolActionInfo[] }> { return this.call('listToolActions'); }
  // skip-confirmation adoption stats (consumed / armed /
  // prompts / adoption). Refreshed on a tighter interval than
  // getState so the StatusBar's "skip: 4/7" badge updates
  // quickly.
  getSkipStats(opts?: { sessionId?: string }): Promise<{
    sessionId: string;
    consumed: number;
    armed: number;
    prompts: number;
    adoption: number;
  }> { return this.call('getSkipStats', opts ?? {}); }
  /** runtime loop-detector threshold tweak. Pass
   *  `window` and `threshold` as positive integers to
   *  enable the detector; pass either as 0 to disable
   *  (the daemon maps 0 / negative to -1 internally).
   *  Returns the new effective values + a `disabled`
   *  flag so the Settings panel can render the
   *  post-tick state without an extra getState
   *  round-trip. The Settings panel pairs this call
   *  with refreshEngineState() so the canonical
   *  state slice lands in the store. */
  setLoopDetectorThresholds(opts: {
    window: number;
    threshold: number;
    sessionId?: string;
  }): Promise<{ ok: boolean; sessionId?: string; window: number; threshold: number; disabled: boolean; error?: string }> {
    return this.call('setLoopDetectorThresholds', opts);
  }
  /** toggle the daemon-side auto-approve-
   *  low-risk flag. The default (true) matches R87
   *  behaviour; the StatusBar exposes a flip so
   *  a user who wants manual control can disable
   *  it without a restart. The RPC returns the
   *  new value + the cumulative auto-approved
   *  count so the store can mirror the daemon
   *  without an extra getState round-trip. */
  setAutoApproveLowRisk(opts: { enabled: boolean }): Promise<{ ok: boolean; enabled: boolean; autoApprovedCount: number }> {
    return this.call('setAutoApproveLowRisk', opts);
  }
  /** medium+high-risk auto-approve toggle.
   *  Distinct from setAutoApproveLowRisk so the
   *  user can keep everyday read-only shortcuts
   *  on while opting into high-risk auto-allow
   *  for a headless / scripted run. Critical
   *  risk (rm -rf, sudo, mkfs) is NEVER
   *  auto-approved regardless of this flag.
   *  Response carries BOTH counters so a single
   *  round-trip refreshes the StatusBar badge
   *  pair. */
  setAutoApproveMediumHigh(opts: { enabled: boolean }): Promise<{ ok: boolean; enabled: boolean; autoApprovedCount: number; autoApprovedElevatedCount: number }> {
    return this.call('setAutoApproveMediumHigh', opts);
  }
  setModel(model: string): Promise<{ ok: true; model: string }> { return this.call('setModel', { model }); }
  setPermissionMode(mode: string): Promise<{ ok: true; mode: string }> { return this.call('setPermissionMode', { mode }); }
  setSystemPrompt(prompt: string): Promise<{ ok: true }> { return this.call('setSystemPrompt', { prompt }); }
  query(prompt: string, opts?: { sessionId?: string }): Promise<{ ok: true; messageId: string }> { return this.call('query', { prompt, ...opts }); }
  cancel(): Promise<{ ok: true }> { return this.call('cancel'); }
  // listSessions accepts optional
  // { limit, withPreview } params. The
  // legacy wire shape is preserved
  // (no-arg call still works). withPreview
  // reads the first user message from
  // each session's JSONL transcript (4 KB
  // cap, regex-only) so the TUI's session
  // picker can render one-line titles.
  listSessions(opts?: { limit?: number; withPreview?: boolean }): Promise<{ sessions: SessionInfo[]; current: string; total?: number; returned?: number; error?: string }> {
    return this.call('listSessions', opts ?? null);
  }
  loadSession(sessionId: string): Promise<{ ok: true; sessionId: string; messageCount: number }> { return this.call('loadSession', { sessionId }); }
  /** mint a fresh session id on the daemon and start an
   *  empty transcript. Returns the new id; the caller should
   *  follow up with `loadSession(newId)` to make the new
   *  session the active one. R197: the optional `cwd` is
   *  bound to the new session (engine.createSession(cwd)
   *  + setCwd), so the renderer can hand a new project
   *  folder to the daemon and get back a clean session
   *  that operates on that folder — no need to first
   *  switch the cwd and then create.
   * `firstPrompt` is the user's first prompt
   * (or a short prefix of it). The daemon persists it
   * to `session_info.first_prompt` and surfaces it via
   * `listSessions.preview` as a fallback when the
   * transcript has no user-role line yet — i.e. the
   * session was just created and the user message
   * hasn't been written to disk. The desktop's lazy
   * create flow uses this to show a real session title
   * from the moment the user submits the prompt,
   * instead of "New Session" / "Untitled session". */
  createSession(opts?: { cwd?: string; worktree?: string; firstPrompt?: string }): Promise<{ ok: true; sessionId: string; cwd?: string; messageCount: number; note?: string }> {
    return this.call('createSession', {
      cwd: opts?.cwd ?? null,
      worktree: opts?.worktree ?? null,
      firstPrompt: opts?.firstPrompt ?? null,
    });
  }
  /** remove a session file from the store. The active
   *  session cannot be deleted (the daemon rejects with
   *  IllegalStateException). */
  deleteSession(sessionId: string): Promise<{ ok: true; sessionId: string; removed: boolean }> { return this.call('deleteSession', { sessionId }); }
  // listTasks / createTask / updateTaskStatus
  // are defined in the prior round block below (line ~500).
  // The original legacy-3 listTasks shape returned
  // a different format (tool-call derived) and was
  // replaced; the new shape matches the engine's
  // TaskRegistry output.
  listProjects(): Promise<{ projects: ProjectInfo[] }> { return this.call('listProjects'); }
  switchProject(projectId: string): Promise<{ ok: true; project: ProjectInfo }> { return this.call('switchProject', { projectId }); }
  permissionResponse(requestId: string, allow: boolean): Promise<{ ok: true }> {
    return this.call('permissionResponse', { requestId, decision: allow ? 'allow' : 'deny' });
  }
  getMetrics(): Promise<MetricsSnapshot> { return this.call('getMetrics'); }
  getTraces(limit?: number): Promise<{ traces: TraceSummary[]; inFlight: number; completed: number }> { return this.call('getTraces', { limit: limit ?? 10 }); }
  getTrace(traceId: string): Promise<{ root: SpanNode; orphans: SpanNode[] }> { return this.call('getTrace', { traceId }); }
  listModels(): Promise<{ models: ModelInfo[]; default: string }> { return this.call('listModels'); }
  /** retry a previously-failed sub-task. The backend delegates
   *  to query() with a synthesised "[R89 retry] <goal>" prompt; the
   *  full transcript is preserved so the model has all prior context.
   *  Returns `{ runId, accepted }`. */
  retrySubTask(goal: string, hint?: string): Promise<{ runId: string; accepted: boolean }> {
    return this.call('retrySubTask', { goal, hint: hint ?? null });
  }
  /** install a per-(tool, target) override on top of the
   *  current permission policy. Mirrors the "始终允许 <tool>" button
   *  the PermissionList exposes. Returns `{ ok, tool, target,
   *  decision }`. */
  permissionPolicyOverride(
    tool: string,
    decision: 'allow' | 'deny',
    opts?: { target?: string; scope?: 'session' | 'project' | 'user' },
  ): Promise<{ ok: boolean; tool: string; target: string; decision: string; scope: string }> {
    // when the caller passes no target, omit the
    // field instead of sending `null`. The Java side
    // `String.valueOf(null)` returns the literal string
    // "null", which the rule-matcher then regex-quotes
    // as `\Qnull\E` and never matches anything. Omitting
    // the field means `p.get("target")` returns null on
    // the Java side, which our R200 fix there treats as
    // "match all invocations of this tool" (a blank
    // matcher). Symmetric defence: the Java side also
    // tolerates the literal "null" string.
    const params: Record<string, unknown> = { tool, decision, scope: opts?.scope ?? 'session' };
    if (opts?.target !== undefined) {
      params.target = opts.target;
    }
    return this.call('permissionPolicyOverride', params);
  }
  /** list memory files in a scope. The browser calls this
   *  once per scope on mount. `files` is sorted MEMORY.md first,
   *  then by mtime desc.
   *
   *  R172 fix: the wire name is `listMemoryFiles` (R92
   *  file-based), NOT `listMemory` (R127 entry-based). The two
   *  shapes are different:
   *    listMemoryFiles  -> { files: [...], count }  (R92 file-based, what MemoryPanel wants)
   *    listMemory       -> { ok, entries, count }   (R127 entry-based, different surface)
   *  legacy the code was calling `listMemory` and then reading
   *  `r.files` from the response — the field doesn't exist on
   *  the entry-based shape, so `r.files` was always undefined.
   *  The downstream `setFiles((f) => ({...f, [scope]: r.files}))`
   *  clobbered `files[scope]` with undefined, and the very next
   *  render of `MemoryPanel` crashed in a `useMemo` that did
   *  `files.USER.length + files.PROJECT.length + files.LOCAL.length`
   *  on the now-undefined slots. The crash took the whole right
   *  panel down with it (the parent component is the closest
   *  error boundary), which is what the user saw as
   *  "Disconnected" + a blank window. */
  listMemoryFiles(scope: 'USER' | 'PROJECT' | 'LOCAL', agentType: string): Promise<{
    files: { name: string; path: string; size: number; mtime: number; isEntry: boolean; scope: string }[];
    count: number;
  }> {
    return this.call('listMemoryFiles', { scope, agentType });
  }
  /** read a memory file's content. Returns the raw markdown
   *  text. The engine re-parses the same string when it next
   *  recalls this file. R172: same wire-name fix as above —
   *  readMemoryFile (file-based), not readMemory (entry-based). */
  readMemoryFile(scope: 'USER' | 'PROJECT' | 'LOCAL', agentType: string, name: string): Promise<{ content: string }> {
    return this.call('readMemoryFile', { scope, agentType, name });
  }
  /** write a memory file's content. Returns the absolute
   *  path the daemon wrote to (handy for the "open in OS" affordance
   *  if we add one later). R172: wire-name fix. */
  writeMemoryFile(scope: 'USER' | 'PROJECT' | 'LOCAL', agentType: string, name: string, content: string): Promise<{ ok: boolean; path: string }> {
    return this.call('writeMemoryFile', { scope, agentType, name, content });
  }
  /** delete a memory file. Idempotent. R172: wire-name fix. */
  deleteMemoryFile(scope: 'USER' | 'PROJECT' | 'LOCAL', agentType: string, name: string): Promise<{ ok: boolean }> {
    return this.call('deleteMemoryFile', { scope, agentType, name });
  }
  /** reset the loop detector's tier to 0. The desktop
   *  LoopGuardBanner's "Continue" button calls this when the user
   *  decides "yes, this loop is intentional — keep going". The
   *  `kind` param is informational only (the current detector
   *  has a single tier counter, not one per kind); the field
   *  is accepted so future R102+ can support per-kind ack
   *  without a frontend rewrite. Resolves to `{ ok, tier, kind,
   *  wasTier? }`. */
  loopAck(opts?: { runId?: string; kind?: 'all' | 'long_output' | 'same_fingerprint' | 'same_error' | 'user_interrupt' }): Promise<{ ok: boolean; tier: number; kind: string; wasTier?: number; reason?: string }> {
    return this.call('loopAck', {
      runId: opts?.runId ?? null,
      kind: opts?.kind ?? 'all',
    });
  }
  /** list workflows in `<cwd>/.aethercode/workflows/*.yaml`.
   *  The desktop calls this once on app start and again whenever
   *  the user opens the workflow picker — the response is cheap
   *  (a directory scan + small parse) and the picker is
   *  user-driven so there's no need to subscribe to changes.
   *  Shape: `{ workflows: WorkflowDoc[], count, dir }`. */
  listWorkflows(): Promise<{
    workflows: WorkflowDoc[];
    count: number;
    dir: string;
  }> {
    return this.call('listWorkflows', null);
  }
  /** read a single workflow's raw YAML + parsed metadata.
   *  Used by the input bar's "selected workflow" pill to show the
   *  declared inputs / step list. Shape: `{ ok, name, description,
   *  inputs, steps, raw }`. */
  getWorkflow(name: string): Promise<{
    ok: boolean;
    name: string;
    description: string;
    inputs: string[];
    steps: WorkflowStep[];
    raw: string;
    stepCount: number;
  }> {
    return this.call('getWorkflow', { name });
  }
  /** kick off a workflow run. For now the daemon emits a
   *  `workflow_step` side note per declared step and returns the
   *  run id. R103 will wire the executor that advances the steps
   *  from "pending" → "running" → "ok" / "error". Shape: `{ ok,
   *  runId, name, stepCount, accepted, note }`. */
  runWorkflow(name: string, inputs: Record<string, unknown> = {}): Promise<{
    ok: boolean;
    runId: string;
    name: string;
    stepCount: number;
    accepted: boolean;
    note?: string;
  }> {
    return this.call('runWorkflow', { name, inputs });
  }
  /** write a workflow YAML. Used by `/workflow create`
   *  and `/workflow modify`. The daemon path-scope check
   *  refuses names with `..` or path separators; the call
   *  here is otherwise idempotent (overwrites on name
   *  collision). Shape: `{ ok, path, name }`. */
  writeWorkflow(name: string, content: string): Promise<{
    ok: boolean;
    path?: string;
    name?: string;
    reason?: string;
  }> {
    return this.call('writeWorkflow', { name, content });
  }
  /** delete a workflow YAML. Idempotent — returns
   *  `{ok: true, removed: false}` for a missing file so the
   *  desktop can safely retry. Shape: `{ ok, removed, name }`. */
  deleteWorkflow(name: string): Promise<{
    ok: boolean;
    removed: boolean;
    name?: string;
    reason?: string;
  }> {
    return this.call('deleteWorkflow', { name });
  }
  /** list files under `<cwd>/` for the input bar's
   *  `@`-mention autocomplete. The daemon walks up to
   *  depth 6 and skips common noise dirs (node_modules,
   *  .git, target, etc.). Shape: `{ files: string[], count,
   *  dir }`. */
  listCwdFiles(query: string = '', max: number = 50): Promise<{
    files: string[];
    count: number;
    dir: string;
    error?: string;
  }> {
    return this.call('listCwdFiles', { query, max });
  }

  // The desktop's SkillsPanel (right panel) calls these to
  // discover available skills and to fetch a skill's full
  // body on demand. The reload RPC is wired to a small "↻"
  // button so the user can force a re-scan after adding a
  // new SKILL.md without restarting the daemon.
  listSkills(): Promise<{ ok: true; count: number; skills: SkillMeta[] }> {
    return this.call('listSkills');
  }
  getSkillBody(name: string): Promise<{
    ok: boolean;
    name: string;
    body?: string;
    path?: string;
    lastModifiedMs?: number;
    error?: string;
  }> {
    return this.call('getSkillBody', { name });
  }
  reloadSkills(): Promise<{ ok: true; count: number; reloadedAt: number }> {
    return this.call('reloadSkills');
  }

  // The desktop surfaces the available Mavis agents in a
  // picker (used by the workflow editor's "agent" step) and
  // previews the body in a modal before wiring the agent
  // into a workflow.
  listAgents(): Promise<{ ok: true; count: number; agents: AgentMeta[] }> {
    return this.call('listAgents');
  }
  getAgentBody(name: string): Promise<{
    ok: boolean;
    name: string;
    body?: string;
    path?: string;
    /** frontmatter fields returned alongside
     *  the body so the AgentEditor can prefill
     *  description / displayName / model on edit.
     *  Empty strings when the field is missing in
     *  the on-disk frontmatter. */
    description?: string;
    displayName?: string;
    model?: string;
    lastModifiedMs?: number;
    error?: string;
  }> {
    return this.call('getAgentBody', { name });
  }

  // The Settings panel's "Agents" tab uses
  // these to manage agents on disk. The
  // shape mirrors the daemon's AgentRegistry
  // write path: a free-form body plus named
  // frontmatter params (description,
  // displayName, model).
  createAgent(opts: {
    name: string;
    description?: string;
    displayName?: string;
    model?: string;
    body: string;
  }): Promise<{ ok: true; name: string }> {
    return this.call('createAgent', {
      name: opts.name,
      description: opts.description ?? null,
      displayName: opts.displayName ?? null,
      model: opts.model ?? null,
      body: opts.body,
    });
  }
  updateAgent(opts: {
    name: string;
    description?: string;
    displayName?: string;
    model?: string;
    body: string;
  }): Promise<{ ok: true; name: string }> {
    return this.call('updateAgent', {
      name: opts.name,
      description: opts.description ?? null,
      displayName: opts.displayName ?? null,
      model: opts.model ?? null,
      body: opts.body,
    });
  }
  deleteAgent(name: string): Promise<{ ok: true; name: string }> {
    return this.call('deleteAgent', { name });
  }
  reloadAgents(): Promise<{ ok: true; count: number }> {
    return this.call('reloadAgents', null);
  }

  // The StatusBar polls getEngineStats every 5s; the
  // Settings panel writes setConcurrencyProfile when the
  // user picks a profile from the dropdown. The
  // getEngineStats return type matches
  // org.aethercode.core.concurrency.EngineStats.toMap().
  getEngineStats(): Promise<{
    ok: true;
    memUsedBytes: number;
    memMaxBytes: number;
    memUsedMb: number;
    memMaxMb: number;
    memPct: number;
    throttled: boolean;
    backpressured: boolean;
    queriesInFlight: number;
    maxConcurrentQueries: number;
    toolsInFlight: number;
    maxConcurrentTools: number;
    branchesInFlight: number;
    maxConcurrentBranches: number;
    concurrencyProfile: string;
    sampledAtMs: number;
  }> {
    return this.call('getEngineStats');
  }
  setConcurrencyProfile(profile: 'low' | 'normal' | 'high'): Promise<{
    ok: true;
    profile: string;
  }> {
    return this.call('setConcurrencyProfile', { profile: profile as string });
  }

  // The renderer's Settings panel reads
  // listProviders() on open to populate the
  // provider picker; switchProvider() rebuilds
  // the engine's ChatClient on the fly. The
  // "currentProvider" / "currentModel" fields
  // let the renderer highlight the active row
  // in the picker so the user sees what's
  // currently driving the engine.
  listProviders(): Promise<{
    ok: true;
    providers: ProviderInfo[];
    currentProvider: string | null;
    currentModel: string | null;
  }> {
    return this.call('listProviders', null);
  }
  switchProvider(opts: {
    provider: string;
    model?: string | null;
  }): Promise<{
    ok: true;
    provider: string;
    model: string;
  }> {
    return this.call('switchProvider', {
      provider: opts.provider,
      model: opts.model ?? null,
    });
  }

  // The user explicitly asked for "a summary
  // regardless of whether the task ended
  // correctly" — so this RPC always returns
  // the current SessionStats snapshot
  // (files_written / files_read / shell_calls
  // / state / last_error / by_tool /
  // summary_text).
  summary(opts?: { sessionId?: string | null }): Promise<SessionSummary> {
    return this.call('summary', { sessionId: opts?.sessionId ?? null });
  }
  // Off by default. The TUI Settings panel
  // surfaces this as a checkbox; flipping it
  // on makes crashed children respawn up to
  // MAX_RESTARTS_PER_CHILD (5) times.
  setAutoRestart(opts: { enabled: boolean }): Promise<{
    ok: boolean;
    enabled: boolean;
    autoRestart: boolean;
  }> {
    return this.call('setAutoRestart', { enabled: opts.enabled });
  }
  // ---- R153: forward a JSON-RPC notification
  // (fire-and-forget) to a child daemon via
  // the supervisor. Used when the TUI is
  // connected to the supervisor (not the
  // child) and needs to relay user events
  // like permission_response or loop_ack.
  proxyNotification(opts: {
    childId: string;
    method: string;
    params: unknown;
  }): Promise<{ ok: boolean; forwarded: boolean }> {
    return this.call('proxyNotification', {
      childId: opts.childId,
      method: opts.method,
      params: opts.params,
    });
  }
  // Returns the engine's in-memory transcript for the
  // currently-loaded session. Used by the desktop to
  // back-fill its `messages` array on first launch and
  // after a WS reconnect (the `transcript_event`
  // subscriber is unreliable for events that fired
  // before it attached). The shape matches the payload
  // the daemon broadcasts via `transcript_event` with
  // action "sync", so the same Message→ChatMessage
  // adapter handles both paths.
  getTranscript(): Promise<{
    ok: true;
    sessionId: string;
    messages: Array<{
      id: string;
      role: 'user' | 'assistant' | 'system' | 'tool_result';
      content: Array<{
        type: 'text' | 'tool_use' | 'tool_result';
        text?: string;
        id?: string;
        name?: string;
        input?: Record<string, unknown>;
        tool_use_id?: string;
        content?: unknown;
        is_error?: boolean;
      }>;
      timestamp: number;
      metadata: Record<string, unknown>;
    }>;
  }> {
    return this.call('getTranscript', null);
  }

  // The desktop's Kanban board creates new tasks
  // (the "+" button), updates their status (drag-drop
  // to a new column), and lists the live set on mount.
  // The shape matches the TaskRegistry's Task record
  // (lowercased enum names) so the renderer's
  // adapter can share the same TypeScript type.
  // The `task_event` WS subscription (registered in
  // store/index.ts) keeps the columns in sync with
  // the engine's view without polling.
  listTasks(): Promise<{
    tasks: TaskInfo[];
    count: number;
  }> {
    return this.call('listTasks', null);
  }
  createTask(opts: {
    description: string;
    type?: 'user' | 'agent' | 'subagent' | 'system';
    parentTaskId?: string;
  }): Promise<{
    ok: true;
    task: TaskInfo;
  }> {
    return this.call('createTask', {
      description: opts.description,
      type: opts.type ?? 'user',
      parentTaskId: opts.parentTaskId ?? null,
    });
  }
  updateTaskStatus(opts: {
    id: string;
    status: 'pending' | 'running' | 'completed' | 'failed' | 'killed';
  }): Promise<{
    ok: true;
    task: TaskInfo;
  }> {
    return this.call('updateTaskStatus', {
      id: opts.id,
      status: opts.status,
    });
  }

  // CWD is not a daemon RPC — it's a Tauri command that controls the daemon
  // process itself. Wrapped here for convenience.
  // returns {cwd, sessionId, swapped}. `swapped` is true when a
  // new daemon was spawned+promoted (multi-cwd case), false when the
  // slot was just updated and ensure_daemon will spawn one rooted at
  // the new cwd on the next call. `sessionId` is the new session id
  // minted on the (possibly new) primary daemon.
  setCwd(path: string): Promise<{ cwd: string; sessionId: string | null; swapped: boolean }> {
    return invoke('set_cwd', { path });
  }

  getCwd(): Promise<string | null> {
    return invoke<string | null>('get_cwd');
  }

  // The desktop's MemoryPanel and the right-rail
  // "session vs project vs user" tabs call these.
  // Shapes match the daemon's
  // org.aethercode.protocol.methods.AetherCodeMethods
  // implementation:
  //   - getMemory({scope, key, sessionId?})
  //   - setMemory({scope, key|content, value?, sessionId?})
  //   - listMemory({scope, sessionId?, cwd?})
  //   - deleteMemory({scope, key, sessionId?})
  //   - compressProjectMemory({cwd, threshold?, keepRecent?, force?})
  // The PROJECT scope is bound to a cwd, not a session —
  // the daemon reads the cwd from the engine's appState
  // unless the caller passes one explicitly (used by
  // Settings → Memory's "force compress now" affordance).
  // The SESSION scope needs a sessionId; the renderer
  // always passes the active one to keep the engine and
  // the renderer looking at the same scope.
  getMemory(opts: {
    scope: 'USER' | 'PROJECT' | 'SESSION';
    key: string;
    sessionId?: string | null;
    cwd?: string | null;
  }): Promise<{
    ok: boolean;
    scope: string;
    sessionId?: string;
    cwd?: string;
    key: string;
    content?: string;
    value?: string;
    tags?: string[];
    createdAt?: string;
    updatedAt?: string;
    createdAtMs?: number;
    updatedAtMs?: number;
    reason?: string;
  }> {
    return this.call('getMemory', {
      scope: opts.scope,
      key: opts.key,
      sessionId: opts.sessionId ?? null,
      cwd: opts.cwd ?? null,
    });
  }
  setMemory(opts: {
    scope: 'USER' | 'PROJECT' | 'SESSION';
    key: string;
    content?: string;
    value?: string;
    tags?: string[];
    sessionId?: string | null;
    cwd?: string | null;
  }): Promise<{
    ok: boolean;
    scope: string;
    sessionId?: string;
    cwd?: string;
    key: string;
    autoCompress?: boolean;
    projectCompressThreshold?: number;
    currentSize?: number;
    reason?: string;
  }> {
    return this.call('setMemory', {
      scope: opts.scope,
      key: opts.key,
      content: opts.content ?? opts.value ?? null,
      value: opts.value ?? opts.content ?? null,
      tags: opts.tags ?? null,
      sessionId: opts.sessionId ?? null,
      cwd: opts.cwd ?? null,
    });
  }
  listMemory(opts: {
    scope: 'USER' | 'PROJECT' | 'SESSION';
    sessionId?: string | null;
    cwd?: string | null;
  }): Promise<{
    ok: boolean;
    scope: string;
    sessionId?: string;
    cwd?: string;
    count: number;
    autoCompress?: boolean;
    projectCompressThreshold?: number;
    keepRecent?: number;
    entries: Array<{
      key: string;
      content?: string;
      value?: string;
      tags?: string[];
      createdAt?: string;
      updatedAt?: string;
      createdAtMs?: number;
      updatedAtMs?: number;
    }>;
    reason?: string;
  }> {
    return this.call('listMemory', {
      scope: opts.scope,
      sessionId: opts.sessionId ?? null,
      cwd: opts.cwd ?? null,
    });
  }
  deleteMemory(opts: {
    scope: 'USER' | 'PROJECT' | 'SESSION';
    key: string;
    sessionId?: string | null;
    cwd?: string | null;
  }): Promise<{
    ok: boolean;
    scope: string;
    sessionId?: string;
    cwd?: string;
    key: string;
    reason?: string;
  }> {
    return this.call('deleteMemory', {
      scope: opts.scope,
      key: opts.key,
      sessionId: opts.sessionId ?? null,
      cwd: opts.cwd ?? null,
    });
  }
  compressProjectMemory(opts: {
    cwd: string;
    threshold?: number;
    keepRecent?: number;
    force?: boolean;
    sessionId?: string | null;
  }): Promise<{
    ok: boolean;
    compressed: boolean;
    beforeCount: number;
    afterCount: number;
    reason: string;
    cwd: string;
    file: string;
  }> {
    return this.call('compressProjectMemory', {
      cwd: opts.cwd,
      threshold: opts.threshold ?? null,
      keepRecent: opts.keepRecent ?? null,
      force: opts.force ?? null,
      sessionId: opts.sessionId ?? null,
    });
  }
  // bindSessionCwd replaces the Tauri-only setCwd
  // path so multi-session TUI/Desktop clients can pin
  // a cwd to a specific session. The daemon persists
  // the new cwd into the sessions.db table and emits a
  // NOTIFY_CWD_CHANGED notification that every
  // connected client can listen for.
  //
  // Note: there's also a legacy `switchProject(projectId)`
  // overload from R83 that operates on the ProjectInfo
  // list (projectId, not cwd). The two coexist because
  // they target different backend handlers (the legacy
  // one routes through the project registry, the new
  // one routes through the session memory store).
  bindSessionCwd(opts: {
    cwd: string;
    sessionId?: string | null;
  }): Promise<{
    ok: boolean;
    sessionId: string;
    oldCwd: string | null;
    newCwd: string;
    reason?: string;
  }> {
    return this.call('switchProject', {
      cwd: opts.cwd,
      sessionId: opts.sessionId ?? null,
    });
  }
}

export const rpc = new AetherCodeRpc();
