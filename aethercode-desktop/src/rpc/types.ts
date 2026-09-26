// Phase 3: shared types for the JSON-RPC surface.
//
// The desktop talks to a long-running supervisor over JSON-RPC. For
// tests we use the deterministic in-process mock that lives in
// `MockRpcServer.ts`. Field names use camelCase to match Jackson's
// `PropertyNamingStrategies.LOWER_CAMEL_CASE` on the wire.

export interface RpcRequest<P = unknown> {
  jsonrpc: '2.0';
  id: number;
  method: string;
  params?: P;
}

export interface RpcResponse<R = unknown> {
  jsonrpc: '2.0';
  id: number;
  result?: R;
  error?: { code: number; message: string; data?: unknown };
}

/** Discriminated error envelope (JSON-RPC 2.0 §5.1). The client
 *  throws {@link RpcError} on any non-empty `error` field; callers
 *  can `try { ... } catch (e) { if (e instanceof RpcError) ... }`. */
export class RpcError extends Error {
  readonly code: number;
  readonly data: unknown;
  readonly method: string;
  constructor(method: string, code: number, message: string, data?: unknown) {
    super(message);
    this.name = 'RpcError';
    this.code = code;
    this.data = data;
    this.method = method;
  }
}

/** Pre-built success / error envelopes. The mock and the test
 *  fixtures both use these to keep the wire shape identical to
 *  what the real supervisor emits. */
export function ok<R>(id: number, result: R): RpcResponse<R> {
  return { jsonrpc: '2.0', id, result };
}
export function err(id: number, code: number, message: string, data?: unknown): RpcResponse<never> {
  return { jsonrpc: '2.0', id, error: { code, message, data } };
}

// --- Domain DTOs ---------------------------------------------------------

export type RpcEventKind =
  | 'message_delta'
  | 'message_end'
  | 'run_start'
  | 'run_end'
  | 'tool_call'
  | 'tool_result'
  | 'subagent_spawn'
  | 'subagent_end'
  | 'file_edit'
  | 'todo_update'
  | 'state_change'
  | 'summary_missing'
  | 'consent_request'
  | 'reconnect'
  | 'usage';

export interface RpcEvent {
  /** Monotonic per-session sequence. The re-attach path uses it
   *  to ask for events since `lastSeq` and resume cleanly. */
  seq: number;
  kind: RpcEventKind;
  sessionId: string;
  ts: number;
  params: Record<string, unknown>;
}

export type SessionState = 'running' | 'paused' | 'completed' | 'failed' | 'cancelled';

export interface SessionListItem {
  id: string;
  title: string;
  cwd: string;
  model: string;
  state: SessionState;
  startedAt: number;
  lastActiveAt: number;
  tokensIn: number;
  tokensOut: number;
  parentId: string | null;
  preview?: string;
  trashedAt?: number | null;
  /** R270 (2026-09-15) — most recent agent activity. The
   *  daemon's listSessions populates this when the caller
   *  passes withPreview=true. Format:
   *    tool_use → "<tool_name> <input_path_tail>"
   *    text     → first 80 chars of last assistant text
   *  Empty string when the session is brand-new (no
   *  assistant message yet). The desktop's SessionListRow
   *  renders this as a third "→ ..." line so the user
   *  can see what the agent was just doing, not just the
   *  first user prompt. */
  lastAgentEvent?: string;
  /** R348 (2026-09-25): cumulative session cost in USD.
   *  Optional — sessions that haven't completed a turn yet
   *  (or older daemons that don't surface cost in
   *  listSessions) leave this undefined. */
  costUsd?: number;
  /** R348 (2026-09-25): files the agent created or modified
   *  during this session. Optional — sessions with no tool
   *  activity yet have an empty / undefined list. */
  filesChanged?: string[];
}

export interface SessionListResult {
  sessions: SessionListItem[];
  total: number;
  hasMore: boolean;
}

export interface SessionEvent {
  id: string;
  type: 'message' | 'tool_call' | 'subagent_spawn' | 'file_edit' | 'todo_update' | 'state_change' | 'summary_missing';
  ts: number;
  data?: unknown;
  toolName?: string;
  subagentId?: string;
}

export interface SessionDetail extends SessionListItem {
  effort: 'low' | 'medium' | 'high' | 'max';
  messages: Array<{ id: string; role: 'user' | 'assistant' | 'system' | 'tool'; content: string; ts: number; toolName?: string }>;
  todos: Array<{ id: string; title: string; status: 'pending' | 'in_progress' | 'completed' | 'cancelled'; owner?: 'parent' | string; startedAt?: number; completedAt?: number }>;
  events: RpcEvent[];
  toolCounters: Record<string, number>;
}

export interface SessionTokens {
  tokensIn: number;
  tokensOut: number;
  totalTokens: number;
  costUsd: number;
  contextWindow: number;
  effectiveWindow: number;
}

export interface SpawnArgs {
  prompt: string;
  cwd: string;
  model?: string;
  workflow?: string;
  parentId?: string;
}

export interface SpawnResult {
  id: string;
  title: string;
  startedAt: number;
}

export interface ResumeResult {
  ok: true;
  state: SessionState;
  resumedAt: number;
}

export interface ReattachArgs {
  sessionId: string;
  lastSeq: number;
  lastTs?: number;
}

export interface ReattachResult {
  from: number;
  to: number;
  events: RpcEvent[];
  /** True when the requested `lastSeq` predates the ring buffer
   *  and the caller will need a full reload. */
  gap: boolean;
}

export interface Grant {
  id: string;
  scope: 'session' | 'project' | 'user';
  category: string;
  decision: 'allow' | 'deny';
  pattern?: string;
  createdAt: number;
}

export type PermissionPreset = 'permissive' | 'cautious' | 'strict';

export interface ModelInfo {
  id: string;
  name: string;
  provider: string;
  tier: string;
  contextWindow: number;
  maxOutput: number;
  capabilities: { vision: boolean; tools: boolean; json: boolean };
  pricing: { inputPerM: number; outputPerM: number; cachedPerM?: number };
  lastUsedAt?: number;
}

export interface WorkflowSummary {
  name: string;
  description: string;
  scope: 'user' | 'project';
  builtin: boolean;
  /** Optional inputs declared by the workflow YAML. The
   *  `WorkflowPicker` reads this to render the input form. */
  inputs?: Array<{ name: string; type: string; required?: boolean; description?: string }>;
}

export interface TaskLimits {
  wallClockMs: number;
  tokens: number;
  calls: number;
  fileWrites: number;
  network: number;
}

export interface TaskInfo {
  id: string;
  sessionId: string;
  state: SessionState;
  limits: TaskLimits;
  startedAt: number;
  lastActiveAt: number;
}

export interface SummaryBlock {
  text: string;
  fallback: boolean;
}

/** Payload of a `todo_update` event. The daemon emits one
 *  such event per assistant turn, carrying the full
 *  current TODO list. The renderer's AgentTasksPanel
 *  replaces its snapshot on every event. R230: replaced
 *  the R200+ TodoBoard reference. */
export interface TodoUpdate {
  todos: Array<{
    id: string;
    title: string;
    status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
    owner?: string;
    startedAt?: number;
    completedAt?: number;
    category?: string;
  }>;
}

export interface ConsentRequest {
  requestId: string;
  toolName: string;
  category: string;
  args: unknown;
  riskLevel: 'low' | 'medium' | 'high' | 'critical';
}

export type ConsentChoice =
  | 'allow-once'
  | 'deny-once'
  | 'allow-session'
  | 'deny-session'
  | 'allow-project'
  | 'deny-project'
  | 'allow-user'
  | 'deny-user'
  | 'allow-category-project'
  | 'deny-category-project';

/** Build the full method set the supervisor accepts. Kept here so
 *  `queries.ts` / `mutations.ts` and the mock share the same
 *  string-literal union. */
export type RpcMethod =
  | 'listSessions'
  | 'session/show'
  | 'session/spawn'
  | 'session/resume'
  | 'session/delete'
  | 'session/restore'
  | 'session/trash'
  | 'session/events'
  | 'session/rename'
  | 'session/tokens'
  | 'task/spawn'
  | 'task/resume'
  | 'task/pause'
  | 'task/kill'
  | 'task/attach'
  | 'task/events'
  | 'task/list'
  | 'task/setLimits'
  | 'task/attached'
  | 'grants/list'
  | 'grants/revoke'
  | 'grants/clear'
  | 'grants/setPreset'
  | 'model/list'
  | 'model/get'
  | 'model/set'
  | 'workflow/list'
  | 'workflow/show'
  | 'workflow/run'
  | 'workflow/upsert'
  | 'workflow/delete'
  | 'compact/status'
  | 'compact/run';
