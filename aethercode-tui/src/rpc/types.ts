/**
 * Phase 6.1 (T-6-01..04): shared JSON-RPC 2.0 types for the TUI.
 *
 * Mirrors the desktop `rpc/types.ts` shape so a future
 * `aethercode-rpc/` shared package can consume both sides.
 * The transport differences (HTTP+SSE on the desktop, Unix
 * socket / Windows named pipe on the TUI) are hidden behind
 * the `JsonRpcClient` class — the types here are wire-only.
 */

/** JSON-RPC 2.0 request envelope. The TUI never sends
 *  positional params; `params` is always an object. */
export interface RpcRequest<P = unknown> {
  jsonrpc: "2.0";
  id: number;
  method: string;
  params?: P;
}

/** JSON-RPC 2.0 reply. The mock + the Java supervisor both
 *  populate `result` (success) or `error` (failure). The
 *  client only ever returns one of the two. */
export interface RpcSuccess<T = unknown> {
  jsonrpc: "2.0";
  id: number;
  result: T;
}

export interface RpcFailure {
  jsonrpc: "2.0";
  id: number;
  error: {
    code: number;
    message: string;
    data?: unknown;
  };
}

export type RpcResult<T = unknown> = RpcSuccess<T> | RpcFailure;

/** JSON-RPC 2.0 notification (no `id`). The supervisor uses
 *  these to push session events to the TUI. */
export interface RpcNotification<P = unknown> {
  jsonrpc: "2.0";
  method: string;
  params?: P;
}

/** Wire-side value type. Mirrors the desktop's
 *  `RpcValue` so the same JSON parsing path can be used.
 *  The recursion uses a named alias so the type is
 *  representable in TypeScript. */
export interface RpcObject {
  [k: string]: RpcValue;
}
export type RpcValue =
  | string
  | number
  | boolean
  | null
  | RpcValue[]
  | RpcObject
  | undefined;

/** Thrown by `JsonRpcClient.request` when the supervisor
 *  returns a JSON-RPC error envelope or the socket dies. */
export class RpcCallError extends Error {
  readonly code: number;
  readonly data: RpcValue;
  readonly method: string;
  constructor(opts: { code: number; message: string; data?: RpcValue; method: string }) {
    super(`rpc error ${opts.code}: ${opts.message} (${opts.method})`);
    this.name = "RpcCallError";
    this.code = opts.code;
    this.data = opts.data;
    this.method = opts.method;
  }
}

/** Thrown by `JsonRpcClient.request` when the underlying
 *  socket is closed (after a reconnect has been scheduled
 *  or the client is stopped). Distinct from a JSON-RPC
 *  error envelope. */
export class RpcDisconnected extends Error {
  constructor(message: string = "rpc disconnected") {
    super(message);
    this.name = "RpcDisconnected";
  }
}

/** The four high-level states the socket connection can be
 *  in. Surfaced to the renderer (status bar, reconnect
 *  modal) so the user knows whether to retry or wait. */
export type ConnectionState =
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected";

/** A single `task/event` (or `transcript_event`, `rpc_event`,
 *  `todo_update`, etc.) streamed from the supervisor. The
 *  `kind` discriminator mirrors the desktop's `RpcEventKind`
 *  (see ../../aethercode-desktop/src/rpc/types.ts). New
 *  kinds are added on the Java side; the renderer treats
 *  unknown kinds as opaque. */
export type RpcEventKind =
  | "tool_call"
  | "tool_result"
  | "message_delta"
  | "message_end"
  | "run_start"
  | "run_end"
  | "subagent_spawn"
  | "subagent_end"
  | "todo_update"
  | "permission_request"
  | "permission_resolved"
  | "loop_warn"
  | "workflow_step"
  | "transcript_event"
  | "summary_missing"
  | "usage"
  | "limit_reached"
  | "session_state"
  | "log";

export interface RpcEvent<P = unknown> {
  kind: RpcEventKind | string;
  sessionId?: string;
  /** Monotonic sequence number per session. Used by the
   *  re-attach handshake (T-7-12). -1 means "not sequenced"
   *  (e.g. a fire-and-forget log event). */
  seq: number;
  ts: number;
  params: P;
  name?: string;
}

/** Subscription handle returned by the client. Mirrors the
 *  desktop's `Subscription` so the same React hooks can be
 *  re-shaped later. */
export interface Subscription {
  unsubscribe(): void;
}

/** Configuration for `JsonRpcClient`. All fields are
 *  optional; the defaults match the spec (§15) — Unix
 *  socket at `/tmp/aethercode-supervisor.sock` on POSIX,
 *  Windows named pipe `\\.\pipe\aethercode-supervisor` on
 *  Windows. */
export interface JsonRpcClientConfig {
  /** Socket path. If omitted, `transport: "auto"` picks
   *  the platform default. */
  socketPath?: string;
  /** Transport selector. `auto` (default) picks
   *  `unix` on Linux/macOS, `pipe` on Windows. */
  transport?: "auto" | "unix" | "pipe";
  /** Per-request timeout in ms. 0 disables. Default 30 000. */
  timeoutMs?: number;
  /** Auto-reconnect on socket close. Default true. */
  autoReconnect?: boolean;
  /** Max consecutive reconnect attempts before giving up.
   *  Default 5 (≈ 5 minutes at 30 s cap). */
  maxReconnectAttempts?: number;
  /** Initial backoff in ms. Default 1 000. The actual
   *  delay doubles on each attempt up to 30 000. */
  baseBackoffMs?: number;
  /** Hard cap for the per-attempt backoff. Default 30 000. */
  maxBackoffMs?: number;
  /** Hook for state changes. Called on every transition. */
  onConnectionState?: (
    state: ConnectionState,
    info?: { attempt: number; lastError: string | null; at: number }
  ) => void;
  /** Hook for incoming notifications (events from the
   *  supervisor that aren't replies to in-flight requests). */
  onNotification?: (method: string, params: RpcValue | undefined) => void;
  /** Hook for socket close (peer hung up). The reconnect
   *  logic listens to this; tests use it to assert the
   *  full disconnect → reconnect cycle. */
  onClose?: (reason: string) => void;
  /** Optional test-only hook: if set, the client does NOT
   *  open a real socket — instead it asks the supplied
   *  factory for a `net.Socket`-shaped transport. The
   *  `MockRpcServer` uses this to wire a paired stub. */
  socketFactory?: SocketFactory;
}

export interface SocketLike {
  write(data: string | Buffer, cb?: (err?: Error | null) => void): boolean;
  on(event: "data", listener: (chunk: Buffer | string) => void): this;
  on(event: "error", listener: (err: Error) => void): this;
  on(event: "close", listener: () => void): this;
  on(event: "connect", listener: () => void): this;
  end(): void;
  destroy(): void;
  setNoDelay?(noDelay: boolean): void;
}

export type SocketFactory = () => SocketLike;
