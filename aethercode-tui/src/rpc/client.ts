/**
 * Phase 6.1 (T-6-01): `JsonRpcClient` over Unix socket /
 * Windows named pipe.
 *
 * The supervisor exposes a single bidirectional socket
 * (per spec §15):
 *
 *   POSIX     : `/tmp/aethercode-supervisor.sock`
 *   Windows   : `\\.\pipe\aethercode-supervisor`
 *
 * The wire is line-delimited JSON-RPC 2.0. One request per
 * line; one response per line. The supervisor also pushes
 * unsolicited notifications (no `id`) which the client
 * routes to `onNotification`.
 *
 * Design notes:
 *   - A monotonic `nextId` counter is kept per client
 *     instance so two `request()` calls in flight can be
 *     matched to their replies by `id`.
 *   - Per-request timeout: each `request()` schedules a
 *     setTimeout. On expiry the pending entry is removed
 *     and the promise rejects with `RpcCallError`.
 *   - Reconnect (T-6-04) is layered on top of this class
 *     via `reconnect.ts`. The client itself exposes
 *     `onClose` so the reconnection manager can react to
 *     socket close events.
 *   - The client never spawns a socket itself; it asks the
 *     configured `socketFactory`. Production wires Node's
 *     `net.createConnection`; tests wire a `MockRpcServer`
 *     peer.
 */

import type { SocketFactory, SocketLike } from "./types.js";
import {
  RpcCallError,
  RpcDisconnected,
  type ConnectionState,
  type JsonRpcClientConfig,
  type RpcNotification,
  type RpcRequest,
  type RpcResult,
  type RpcSuccess,
  type RpcValue,
} from "./types.js";

const DEFAULT_BASE_BACKOFF_MS = 1_000;
const DEFAULT_MAX_BACKOFF_MS = 30_000;
const DEFAULT_TIMEOUT_MS = 30_000;
const DEFAULT_MAX_ATTEMPTS = 5;

let _globalNextId = 1;

/** Allocate a monotonic JSON-RPC id. Module-scoped so two
 *  clients (test harness + main) don't collide. */
function nextId(): number {
  const id = _globalNextId;
  _globalNextId += 1;
  return id;
}

type Pending = {
  resolve: (v: RpcValue) => void;
  reject: (e: Error) => void;
  timer: ReturnType<typeof setTimeout> | null;
  method: string;
};

export interface JsonRpcClientDeps {
  /** When provided, the client never opens a real socket.
   *  Tests use this to inject a paired `MockRpcServer`. */
  socketFactory?: SocketFactory;
  /** Mock-only: hand the client a socket that is already
   *  in a "connected" state. The factory is still required
   *  (so a real cleanup path exists), but the client skips
   *  the connect handshake when this is set. */
  preConnectedSocket?: SocketLike;
}

/** Default socket factory. Resolves the platform-specific
 *  socket (Unix socket on POSIX, named pipe on Windows) and
 *  returns a `net.Socket` cast to `SocketLike`. Imports
 *  Node's `net` lazily so the test harness (which never
 *  opens real sockets) can avoid the dependency. */
export function defaultSocketFactory(socketPath: string): SocketFactory {
  return () => {
    // `require` keeps the bundle tree-shake-friendly: the
    // `net` module is only loaded when the factory is
    // actually called.
    const net = require("net") as typeof import("net");
    const sock = net.createConnection(socketPath);
    return sock as unknown as SocketLike;
  };
}

/** Pick the platform default socket path. Mirrors the spec
 *  §15 mapping. Exposed so callers (e.g. `ac-tui.ts`) can
 *  display the resolved path in the status bar. */
export function defaultSocketPath(): string {
  if (process.platform === "win32") {
    return "\\\\.\\pipe\\aethercode-supervisor";
  }
  return "/tmp/aethercode-supervisor.sock";
}

export class JsonRpcClient {
  private readonly cfg: {
    socketPath: string;
    transport: "auto" | "unix" | "pipe";
    socketFactory?: SocketFactory;
    timeoutMs: number;
    autoReconnect: boolean;
    maxReconnectAttempts: number;
    baseBackoffMs: number;
    maxBackoffMs: number;
    onConnectionState?: JsonRpcClientConfig["onConnectionState"];
    onNotification?: JsonRpcClientConfig["onNotification"];
    onClose?: JsonRpcClientConfig["onClose"];
  };
  /** Per-instance id counter. Starts at 1 so the first id
   *  is easy to spot in logs. */
  private nextId = 1;
  private socket: SocketLike | null = null;
  private readonly pending = new Map<number, Pending>();
  private state: ConnectionState = "connecting";
  private stopped = false;
  private connectAttempt = 0;
  private buffer = "";
  /** Notification handler. Set by the constructor and
   *  overridable by the `useRpcSubscription` hook (and
   *  `subscribeEvents`). Tests can read this field to
   *  assert the wiring. */
  public onNotification: ((method: string, params: RpcValue | undefined) => void) | undefined;
  /** Set by `reconnect.ts`. When non-null, the reconnect
   *  loop is active and is the source of truth for "should
   *  we try to reconnect". The client just calls back into
   *  it via the closure. */
  public onSocketClosed: ((reason: string) => void) | null = null;
  /** Set by `reconnect.ts` so the client can ask the
   *  reconnection manager to attempt a fresh connect. */
  public onForceReconnect: (() => void) | null = null;

  constructor(
    config: JsonRpcClientConfig = {},
    deps: JsonRpcClientDeps = {}
  ) {
    const transport = config.transport ?? "auto";
    const socketPath =
      config.socketPath ??
      (transport === "pipe"
        ? "\\\\.\\pipe\\aethercode-supervisor"
        : defaultSocketPath());
    this.cfg = {
      socketPath,
      transport,
      timeoutMs: config.timeoutMs ?? DEFAULT_TIMEOUT_MS,
      autoReconnect: config.autoReconnect ?? true,
      maxReconnectAttempts: config.maxReconnectAttempts ?? DEFAULT_MAX_ATTEMPTS,
      baseBackoffMs: config.baseBackoffMs ?? DEFAULT_BASE_BACKOFF_MS,
      maxBackoffMs: config.maxBackoffMs ?? DEFAULT_MAX_BACKOFF_MS,
      socketFactory: config.socketFactory ?? deps.socketFactory,
      onConnectionState: config.onConnectionState,
      onClose: config.onClose,
    };
    this.onNotification = config.onNotification;
    if (deps.preConnectedSocket) {
      // Test mode: skip the handshake and treat the socket
      // as already connected. The reconnect machinery is
      // still wired in case the test later destroys the
      // socket.
      this.socket = deps.preConnectedSocket;
      this.attachSocket(this.socket);
      this.setState("connected", { attempt: 0, lastError: null });
    }
  }

  /** Public read-only config (used by tests / reconnect
   *  loop). */
  get config(): Readonly<JsonRpcClientConfig> {
    return this.cfg;
  }

  /** Current connection state. Mirrors the desktop's
   *  `JsonRpcClient.currentConnectionState`. */
  currentConnectionState(): ConnectionState {
    return this.state;
  }

  /** Number of reconnect attempts since the last successful
   *  connect. Mirrors the desktop's API. */
  currentReconnectAttempt(): number {
    return this.connectAttempt;
  }

  /** Switch to a new socket and wire it up. Internal —
   *  called by the factory on initial connect and by
   *  `reconnect.ts` after a backoff fires. */
  private setSocket(sock: SocketLike): void {
    this.socket = sock;
    this.attachSocket(sock);
  }

  private attachSocket(sock: SocketLike): void {
    sock.on("data", (chunk) => this.handleData(chunk));
    sock.on("error", (err) => this.handleError(err));
    sock.on("close", () => this.handleClose("peer closed"));
    if (typeof (sock as any).setNoDelay === "function") {
      try { (sock as any).setNoDelay(true); } catch { /* ignore */ }
    }
  }

  /** Open the underlying socket. Idempotent. */
  connect(): void {
    if (this.stopped) return;
    if (this.socket) return;  // already connected (or connecting)
    if (!this.cfg.socketFactory) {
      throw new Error(
        "JsonRpcClient.connect: no socketFactory configured " +
        "(test the client by injecting a MockRpcServer via deps.socketFactory)"
      );
    }
    const sock = this.cfg.socketFactory();
    this.setSocket(sock);
    // For the test harness, the socket may emit 'connect'
    // synchronously (or never — pairs are pre-connected).
    // We listen to it for completeness; production sockets
    // are async.
    sock.on("connect", () => {
      this.setState("connected", { attempt: 0, lastError: null });
    });
    // Real `net.Socket` emits 'connect' after the
    // handshake; the test harness may emit it
    // synchronously. We optimistically mark "connecting"
    // here and let the 'connect' event flip us to
    // "connected".
    this.setState("connecting", {
      attempt: this.connectAttempt,
      lastError: null,
    });
  }

  /** Send a JSON-RPC request and await the typed result.
   *  Throws `RpcCallError` on a JSON-RPC error envelope,
   *  a timeout, or a socket close mid-flight. */
  request<T = RpcValue>(method: string, params?: RpcValue, opts: { timeoutMs?: number } = {}): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      this.connect();
      const id = this.nextId++;
      const req: RpcRequest = { jsonrpc: "2.0", id, method, params };
      const timeoutMs = opts.timeoutMs ?? this.cfg.timeoutMs;
      const pending: Pending = {
        resolve: (v) => resolve(v as T),
        reject,
        timer: null,
        method,
      };
      if (timeoutMs > 0) {
        pending.timer = setTimeout(() => {
          this.pending.delete(id);
          reject(new RpcCallError({
            code: -32000,
            message: `timeout after ${timeoutMs}ms`,
            method,
          }));
        }, timeoutMs);
      }
      this.pending.set(id, pending);
      const sock = this.socket;
      if (!sock) {
        this.pending.delete(id);
        if (pending.timer) clearTimeout(pending.timer);
        reject(new RpcDisconnected("no socket"));
        return;
      }
      try {
        sock.write(JSON.stringify(req) + "\n");
      } catch (e) {
        this.pending.delete(id);
        if (pending.timer) clearTimeout(pending.timer);
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    });
  }

  /** Send a notification (no `id`, no reply expected). The
   *  supervisor uses notifications to push events; the TUI
   *  uses them for fire-and-forget side-band channels
   *  (`task/typing`, etc.). */
  notify(method: string, params?: RpcValue): void {
    this.connect();
    const sock = this.socket;
    if (!sock) return;
    const n: RpcNotification = { jsonrpc: "2.0", method, params };
    try {
      sock.write(JSON.stringify(n) + "\n");
    } catch { /* ignore — the socket's error handler will reject pending requests */ }
  }

  /** Disconnect cleanly. After `disconnect()`, the client
   *  is in the `disconnected` state and no further
   *  reconnect attempts are scheduled. The pending map is
   *  drained with `RpcDisconnected` so callers see a
   *  consistent error. */
  async disconnect(): Promise<void> {
    if (this.stopped) return;
    this.stopped = true;
    this.failPending(new RpcDisconnected("user stopped"));
    this.setState("disconnected", {
      attempt: this.connectAttempt,
      lastError: "user stopped",
    });
    if (this.socket) {
      try { this.socket.end(); } catch { /* ignore */ }
      try { this.socket.destroy(); } catch { /* ignore */ }
      this.socket = null;
    }
  }

  /** True if a socket is currently open and "connected".
   *  Used by tests; production code should consult
   *  `currentConnectionState()` instead. */
  isConnected(): boolean {
    return this.state === "connected" && this.socket != null;
  }

  // --- internals -----------------------------------------------------

  private setState(
    next: ConnectionState,
    info?: { attempt: number; lastError: string | null }
  ): void {
    this.state = next;
    if (next === "connected") {
      this.connectAttempt = 0;
    }
    if (this.cfg.onConnectionState) {
      try {
        this.cfg.onConnectionState(next, {
          attempt: info?.attempt ?? this.connectAttempt,
          lastError: info?.lastError ?? null,
          at: Date.now(),
        });
      } catch { /* ignore */ }
    }
  }

  private handleData(chunk: Buffer | string): void {
    this.buffer += typeof chunk === "string" ? chunk : chunk.toString("utf-8");
    // Split on newline (the wire is line-delimited). The
    // supervisor never embeds a raw `\n` in a JSON payload,
    // so a single line is always one full message.
    let nl: number;
    while ((nl = this.buffer.indexOf("\n")) >= 0) {
      const line = this.buffer.slice(0, nl);
      this.buffer = this.buffer.slice(nl + 1);
      if (line.trim().length === 0) continue;
      this.dispatchLine(line);
    }
  }

  private dispatchLine(line: string): void {
    let msg: RpcResult<RpcValue> | RpcNotification;
    try {
      msg = JSON.parse(line) as RpcResult<RpcValue> | RpcNotification;
    } catch {
      // Malformed line — log + skip. A real client might
      // surface this as an `onError` event but for now the
      // supervisor never emits non-JSON.
      return;
    }
    if (this.isNotification(msg)) {
      try {
        this.onNotification?.(msg.method, msg.params as RpcValue);
      } catch (e) {
        // Don't let a bad handler tear down the socket.
        // We log to stderr in case the host is collecting
        // diagnostics.
        try { process.stderr.write(`[rpc] onNotification threw: ${(e as Error).message}\n`); } catch { /* ignore */ }
      }
      return;
    }
    const r = msg as RpcResult<RpcValue>;
    const p = this.pending.get(r.id);
    if (!p) return;
    this.pending.delete(r.id);
    if (p.timer) clearTimeout(p.timer);
    if ("error" in r) {
      p.reject(new RpcCallError({
        code: r.error.code,
        message: r.error.message,
        data: r.error.data as RpcValue,
        method: p.method,
      }));
    } else {
      p.resolve((r as RpcSuccess<RpcValue>).result);
    }
  }

  private isNotification(msg: any): msg is RpcNotification {
    return msg && typeof msg === "object"
      && typeof msg.method === "string"
      && (msg.id === undefined || msg.id === null);
  }

  private handleError(err: Error): void {
    this.setState("reconnecting", {
      attempt: this.connectAttempt,
      lastError: err.message,
    });
    this.failPending(new RpcCallError({
      code: -32000,
      message: `socket error: ${err.message}`,
      method: "(any)",
    }));
  }

  private handleClose(reason: string): void {
    this.socket = null;
    this.failPending(new RpcDisconnected(`socket closed: ${reason}`));
    this.cfg.onClose?.(reason);
    this.onSocketClosed?.(reason);
    if (this.cfg.autoReconnect && !this.stopped) {
      this.setState("reconnecting", {
        attempt: this.connectAttempt,
        lastError: reason,
      });
    } else {
      this.setState("disconnected", {
        attempt: this.connectAttempt,
        lastError: reason,
      });
    }
  }

  private failPending(err: Error): void {
    const list: Pending[] = [];
    this.pending.forEach((p) => { list.push(p); });
    this.pending.clear();
    for (const p of list) {
      if (p.timer) clearTimeout(p.timer);
      try { p.reject(err); } catch { /* ignore */ }
    }
  }

  /** Force an immediate reconnect attempt. The reconnect
   *  manager calls this from the reattach flow (T-7-12)
   *  when the user explicitly hits "retry". */
  forceReconnect(): void {
    if (this.stopped) return;
    this.connectAttempt = 0;
    this.onForceReconnect?.();
    this.connect();
  }
}

/** Module-singleton default client. Production code uses
 *  this; tests create their own. Matches the desktop's
 *  `defaultRpcClient` pattern. */
export const defaultRpcClient = new JsonRpcClient();

/** Helper used by `reconnect.ts` to set the reconnect
 *  callbacks without having to expose a setter on the
 *  client. Mirrors the desktop's `setAutoReconnect`. */
export function attachReconnectHooks(
  client: JsonRpcClient,
  hooks: { onSocketClosed: (reason: string) => void; onForceReconnect: () => void }
): void {
  client.onSocketClosed = hooks.onSocketClosed;
  client.onForceReconnect = hooks.onForceReconnect;
}

// Re-export the wire-level error classes so callers can
// `import { RpcCallError, RpcDisconnected } from "./rpc/client.js"`.
export { RpcCallError, RpcDisconnected };
// Re-export the type-only pieces for the same convenience.
export type {
  ConnectionState,
  JsonRpcClientConfig,
  RpcEvent,
  RpcEventKind,
  RpcFailure,
  RpcNotification,
  RpcObject,
  RpcRequest,
  RpcResult,
  RpcSuccess,
  RpcValue,
  SocketFactory,
  SocketLike,
  Subscription,
} from "./types.js";
