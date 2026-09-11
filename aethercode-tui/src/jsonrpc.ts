/**
 * minimal JSON-RPC 2.0 client over stdio.
 *
 * Spawns the AetherCode daemon as a child process with
 * `--daemon` and exchanges line-delimited JSON-RPC 2.0
 * messages. Notifications (server → client) are routed
 * through `onNotification`; requests (client → server)
 * return promises that resolve when the matching response
 * arrives.
 *
 * The client is the same shape as the prior round build (so the
 * TUI is the only thing that changed).
 */

import { spawn, type ChildProcess } from "node:child_process";
import { createInterface } from "node:readline";

export type RpcValue = string | number | boolean | null | RpcValue[] | { [k: string]: RpcValue } | undefined;

export interface RpcRequest {
  jsonrpc: "2.0";
  id: number;
  method: string;
  params?: RpcValue;
}

export interface RpcResponse {
  jsonrpc: "2.0";
  id: number;
  result?: RpcValue;
  error?: { code: number; message: string; data?: RpcValue };
}

export interface RpcNotification {
  jsonrpc: "2.0";
  method: string;
  params?: RpcValue;
}

export interface RpcError {
  code: number;
  message: string;
  data?: RpcValue;
}

export class RpcCallError extends Error {
  readonly code: number;
  readonly data: RpcValue | undefined;
  constructor(code: number, message: string, data?: RpcValue) {
    super(`rpc error ${code}: ${message}`);
    this.code = code;
    this.data = data;
  }
}

export class DaemonDisconnected extends Error {
  constructor() { super("daemon disconnected"); }
}

export interface RequestOptions {
  timeoutMs?: number;
}

export interface ClientOptions {
  jarPath: string;
  cwd: string;
  javaBinary?: string;
  jvmArgs?: string[];
  onNotification?: (method: string, params: RpcValue | undefined) => void;
  onExit?: (code: number | null) => void;
}

type Pending = {
  resolve: (v: RpcValue) => void;
  reject: (e: Error) => void;
  timer: NodeJS.Timeout | null;
};

export class JsonRpcClient {
  public readonly jarPath: string;
  public readonly cwd: string;
  private readonly javaBinary: string;
  private readonly jvmArgs: string[];
  private readonly onNotification: (method: string, params: RpcValue | undefined) => void;
  private readonly onExit: (code: number | null) => void;
  /** connection state surfaced to the renderer so the
   *  status bar can show "reconnecting (2/5)..." when the
   *  daemon dies and we're auto-respawning. */
  private readonly onConnectionState: (state: ConnectionState, info?: { attempt: number; lastError: string | null }) => void;
  // cached reference to the most recent connection
  // state for synchronous reads (the status bar polls
  // currentConnectionState() on every tick). The setter
  // is below; the field is updated by the onConnectionState
  // callback.
  private lastState: ConnectionState = "connecting";

  private child: ChildProcess | null = null;
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();
  private stopped = false;
  /** when set, the client respawns the daemon after an
   *  unexpected exit. The TUI calls stop() to break the loop
   *  (a stop() is a clean shutdown, not a disconnect). */
  private autoReconnect: boolean = false;
  private reconnectAttempt: number = 0;
  private reconnectTimer: NodeJS.Timeout | null = null;
  /** max reconnect attempts before giving up. The
   *  exponential backoff caps at 30s; after 10 attempts the
   *  client surfaces "disconnected" and stops trying. The
   *  user can press Ctrl-R to retry. */
  private maxReconnectAttempts: number = 10;

  constructor(opts: ClientOptions & {
    onConnectionState?: (state: ConnectionState, info?: { attempt: number; lastError: string | null }) => void;
    autoReconnect?: boolean;
    maxReconnectAttempts?: number;
  }) {
    this.jarPath = opts.jarPath;
    this.cwd = opts.cwd;
    this.javaBinary = opts.javaBinary ?? "java";
    this.jvmArgs = opts.jvmArgs ?? ["-Xmx1g"];
    this.onNotification = opts.onNotification ?? (() => undefined);
    this.onExit = opts.onExit ?? (() => undefined);
    this.onConnectionState = opts.onConnectionState ?? (() => undefined);
    this.autoReconnect = opts.autoReconnect ?? false;
    this.maxReconnectAttempts = opts.maxReconnectAttempts ?? 10;
  }

  setNotificationHandler(fn: (method: string, params: RpcValue | undefined) => void): void {
    (this as unknown as { onNotification: typeof fn }).onNotification = fn;
  }

  /** opt-in auto-reconnect. When true, an unexpected
   *  child exit triggers a backoff + respawn loop. stop()
   *  is a clean shutdown and breaks the loop. */
  setAutoReconnect(enabled: boolean, maxAttempts?: number): void {
    this.autoReconnect = enabled;
    if (typeof maxAttempts === "number") this.maxReconnectAttempts = maxAttempts;
  }

  /** current connection state. Surfaced in the status
   *  bar so the user knows when the daemon is being
   *  respawned. */
  currentConnectionState(): ConnectionState {
    return this.lastState;
  }

  /** how many reconnect attempts have been made in
   *  the current cycle (resets to 0 on a successful connect). */
  currentReconnectAttempt(): number {
    return this.reconnectAttempt;
  }

  start(): void {
    if (this.child) return;
    const initialState: ConnectionState = this.reconnectAttempt > 0 ? "reconnecting" : "connecting";
    this.lastState = initialState;
    this.onConnectionState(initialState, {
      attempt: this.reconnectAttempt,
      lastError: null,
    });
    // pass the cwd BOTH as the process working directory
    // (so subprocesses inherit it) AND as a JVM system property
    // (so the engine's FileWriteTool, FileEditTool, etc. can
    // resolve relative paths against it without having to
    // query the JVM cwd). Without the system property, relative
    // paths in model output can land in an unexpected location
    // if any tool decides to resolve them from System.getProperty
    // ("user.dir") rather than the spawn cwd.
    const cwdProp = this.cwd ? `-Daethercode.cwd=${this.cwd}` : null;
    const args = [
      ...this.jvmArgs,
      ...(cwdProp ? [cwdProp] : []),
      "-jar", this.jarPath, "--daemon",
    ];
    const child = spawn(this.javaBinary, args, {
      cwd: this.cwd,
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    this.child = child;

    const rl = createInterface({ input: child.stdout!, crlfDelay: Infinity });
    rl.on("line", (line) => {
      const trimmed = line.trim();
      if (trimmed.length === 0) return;
      let msg: RpcResponse | RpcNotification;
      try { msg = JSON.parse(trimmed); } catch { return; }
      if ("id" in msg && msg.id !== undefined && msg.id !== null) {
        const r = msg as RpcResponse;
        const p = this.pending.get(r.id);
        if (!p) return;
        this.pending.delete(r.id);
        if (p.timer) clearTimeout(p.timer);
        if (r.error) p.reject(new RpcCallError(r.error.code, r.error.message, r.error.data));
        else p.resolve(r.result);
      } else {
        const n = msg as RpcNotification;
        this.onNotification(n.method, n.params);
      }
    });

    // route stderr to the connection-state callback so
    // a slow / wedged daemon (e.g. a stuck model call) shows
    // up as "last error: <stderr tail>". The renderer can
    // show this in the status bar.
    let stderrTail = "";
    child.stderr?.on("data", (chunk) => {
      stderrTail += chunk.toString();
      // Keep only the last 2 KB to avoid unbounded growth.
      if (stderrTail.length > 2048) {
        stderrTail = stderrTail.slice(-2048);
      }
    });

    child.on("exit", (code) => {
      this.child = null;
      const wasConnected = this.reconnectAttempt === 0;
      const pending = [...this.pending];
      this.pending.clear();
      for (const [, p] of pending) {
        if (p.timer) clearTimeout(p.timer);
        p.reject(new DaemonDisconnected());
      }
      this.onExit(code);
      // trigger reconnect on unexpected exit. We
      // distinguish "clean" (stop() called) from "crash"
      // (process died). Clean shutdown has this.stopped=true;
      // a crash doesn't.
      if (this.autoReconnect && !this.stopped) {
        this.scheduleReconnect(stderrTail || `exit code ${code}`);
      } else {
        this.lastState = "disconnected";
        this.onConnectionState("disconnected", {
          attempt: this.reconnectAttempt,
          lastError: stderrTail || (code != null ? `exit code ${code}` : null),
        });
      }
      // Mark that we've had at least one cycle.
      void wasConnected;
    });
    child.on("error", (err) => {
      // Spawn failure (e.g. java not on PATH). Same
      // disconnect path as exit.
      if (this.autoReconnect && !this.stopped) {
        this.scheduleReconnect(err.message);
      } else {
        this.lastState = "disconnected";
        this.onConnectionState("disconnected", {
          attempt: this.reconnectAttempt,
          lastError: err.message,
        });
      }
    });
    // surface "connected" once stdout is open and
    // the readline interface has fired. The legacy path
    // never explicitly told the TUI "we're now connected";
    // a TUI that polled isConnected() could only check
    // the child handle which was misleading (the child
    // existed before the daemon was ready).
    setImmediate(() => {
      if (this.child === child) {
        this.reconnectAttempt = 0;
        this.lastState = "connected";
        this.onConnectionState("connected", { attempt: 0, lastError: null });
      }
    });
  }

  /** schedule a respawn with exponential backoff.
   *  Caps at 30s between attempts; gives up after
   *  maxReconnectAttempts. The TUI is informed via
   *  onConnectionState("reconnecting", {attempt, lastError}).
   */
  private scheduleReconnect(lastError: string): void {
    if (this.stopped) return;
    this.reconnectAttempt += 1;
    if (this.reconnectAttempt > this.maxReconnectAttempts) {
      this.lastState = "disconnected";
      this.onConnectionState("disconnected", {
        attempt: this.reconnectAttempt,
        lastError: `gave up after ${this.maxReconnectAttempts} attempts: ${lastError}`,
      });
      return;
    }
    this.lastState = "reconnecting";
    this.onConnectionState("reconnecting", {
      attempt: this.reconnectAttempt,
      lastError,
    });
    // Backoff: 0.5s, 1s, 2s, 4s, 8s, 16s, 30s, 30s, 30s, 30s.
    const backoffMs = Math.min(30_000, 500 * Math.pow(2, this.reconnectAttempt - 1));
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      // Re-attach onExit / spawn-failure so the loop
      // continues if the next attempt also fails.
      try {
        this.start();
      } catch (e) {
        this.scheduleReconnect(`respawn threw: ${(e as Error).message}`);
      }
    }, backoffMs);
  }

  /** force a reconnect attempt NOW (Ctrl-R in the
   *  status bar calls this when the user wants to retry
   *  before the next backoff fires). Resets the attempt
   *  counter so a manual retry doesn't immediately give up. */
  forceReconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.reconnectAttempt = 0;
    if (this.stopped) return;
    try { this.start(); }
    catch (e) {
      this.scheduleReconnect(`manual retry threw: ${(e as Error).message}`);
    }
  }

  request<T = RpcValue>(method: string, params?: RpcValue, options: RequestOptions = {}): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      this.start();
      const id = this.nextId++;
      // T-7-19: the daemon's JSON-RPC handler is strict about
      // `params` — it rejects `undefined` (R-32602: "Invalid
      // params: params object is required"). Pre-fix we sent
      // `params: undefined` whenever the caller omitted the
      // second arg (e.g. `client.request("getState")`), which
      // serialised as `{"method":"getState"}` (no `params`
      // key) and made the daemon drop the call. Defaulting to
      // `{}` here means every caller that doesn't need
      // arguments still gets a valid params object on the
      // wire (`"params":{}`).
      const req: RpcRequest = { jsonrpc: "2.0", id, method, params: params ?? {} };
      const pending: Pending = { resolve: (v) => resolve(v as T), reject, timer: null };
      const timeoutMs = options.timeoutMs ?? 60_000;
      if (timeoutMs > 0) {
        pending.timer = setTimeout(() => {
          this.pending.delete(id);
          reject(new Error(`rpc timeout after ${timeoutMs}ms: ${method}`));
        }, timeoutMs);
      }
      this.pending.set(id, pending);
      const child = this.child;
      if (!child || !child.stdin || child.stdin.writableEnded) {
        this.pending.delete(id);
        if (pending.timer) clearTimeout(pending.timer);
        reject(new DaemonDisconnected());
        return;
      }
      child.stdin.write(JSON.stringify(req) + "\n");
    });
  }

  notify(method: string, params?: RpcValue): void {
    this.start();
    const n: RpcNotification = { jsonrpc: "2.0", method, params };
    const child = this.child;
    if (!child || !child.stdin || child.stdin.writableEnded) return;
    child.stdin.write(JSON.stringify(n) + "\n");
  }

  async stop(): Promise<void> {
    if (this.stopped) return;
    this.stopped = true;
    // clear any pending reconnect timer so a
    // user-initiated stop doesn't get overridden by a
    // backoff that fires a moment later.
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.lastState = "disconnected";
    this.onConnectionState("disconnected", { attempt: 0, lastError: "user stopped" });
    const child = this.child;
    this.child = null;
    if (!child) return;
    try {
      child.stdin?.end();
    } catch { /* ignore */ }
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => { try { child.kill("SIGTERM"); } catch { /* ignore */ } resolve(); }, 2000);
      child.on("exit", () => { clearTimeout(timer); resolve(); });
    });
  }
}

/**
 * the four states a daemon connection can be in. The
 * TUI status bar uses these to render the connection
 * indicator. Surfaced to the user as:
 *
 *   connecting     →  "○ connecting..."
 *   connected      →  "● connected"  (default, often hidden)
 *   reconnecting   →  "↻ reconnecting (2/10) — last error: <tail>"
 *   disconnected   →  "✕ disconnected (10/10 failed) — Ctrl-R to retry"
 */
export type ConnectionState = "connecting" | "connected" | "reconnecting" | "disconnected";
