/**
 * T-6-02 / T-7-06 / T-7-12: in-memory JSON-RPC + event peer
 * for tests.
 *
 * Two test surfaces are merged here:
 *
 *   - The wire-protocol side (T-6-02). Implements
 *     `SocketLike` so the `JsonRpcClient` can be wired
 *     with `socketFactory: () => mock.peerSocket()`. The
 *     `handle(method, params)` / `dispatch(req)` /
 *     `pushEvent(sessionId, ev)` API matches the desktop
 *     mock (so a future shared `aethercode-rpc/` module
 *     can drop in cleanly).
 *
 *   - The high-level side (T-7-06, T-7-12). Exposes
 *     `sessionSpawn` / `taskResume` / `taskPause` /
 *     `taskKill` / `pushToolCallEvents` so the E2E
 *     tests can drive a full session lifecycle without
 *     spinning up the Java supervisor. The `state`
 *     property is a `MockRpcState` that the tests read
 *     directly (sessions, events, grants, etc.).
 *
 * The two surfaces don't conflict: the high-level methods
 * update the same `state` (sessions + events) that the
 * wire-protocol side dispatches against. The E2E tests
 * can therefore use either API.
 *
 * Per-session state lives in a `MockSession` record. The
 * mock ships a tiny session model (id + transcript +
 * lastSeq + state) so tests that exercise re-attach can
 * ask the mock to "forget events 0..N" and verify the
 * client falls back to `getTranscript`.
 */

import { EventEmitter } from "node:events";
import { JsonRpcClient, type JsonRpcClientDeps } from "./client.js";
import type { RpcNotification, SocketLike, Subscription } from "./types.js";

// --------------------------------------------------------------------
//  Wire-protocol side
// --------------------------------------------------------------------

export type MockHandler = (
  params: any,
  ctx: { session: MockSession | null }
) => any | Promise<any>;

export interface MockSession {
  id: string;
  /** Cached transcript. The mock doesn't enforce shape; the
   *  per-method handler decides which keys it expects. */
  transcript: unknown[];
  /** Highest seq the mock has emitted. Re-attach logic
   *  uses this to decide whether the ring buffer still
   *  covers the caller's `lastSeq`. */
  lastSeq: number;
  state: "running" | "paused" | "completed" | "failed" | "cancelled" | "trashed";
  /** Cached tokens. `useSessionTokens` reads this. */
  tokensIn: number;
  tokensOut: number;
  tokensByKind: Record<string, number>;
  cwd?: string;
  model?: string;
  startedAt?: number;
  lastActiveAt?: number;
  title?: string;
  parentSessionId?: string | null;
  messageCount?: number;
  preview?: string;
}

export interface MockEvent {
  kind: string;
  sessionId?: string;
  seq?: number;
  ts?: number;
  params: any;
  name?: string;
}

export interface MockSocket extends SocketLike {
  /** Inject a line into the client. The mock calls this
   *  when a handler wants to send a reply. */
  injectLine(line: string): void;
  /** Inject a parsed envelope (request/notification). */
  injectEnvelope(env: any): void;
  /** Set the close handler. The mock invokes this when
   *  `closeSession` is called. */
  setOnClose(fn: () => void): void;
  /** True once the client has called `end()` /
   *  `destroy()`. */
  closed: boolean;
  /** True once the client has attached the "data"
   *  listener. */
  attached: boolean;
}

/** Construct a wire-level success / error envelope. */
export function ok<T>(id: number, result: T): { jsonrpc: "2.0"; id: number; result: T } {
  return { jsonrpc: "2.0", id, result };
}
export function err(
  id: number,
  code: number,
  message: string,
  data?: unknown
): { jsonrpc: "2.0"; id: number; error: { code: number; message: string; data?: unknown } } {
  return { jsonrpc: "2.0", id, error: { code, message, data } };
}

interface PeerSide {
  socket: MockSocket;
  /** All envelopes the *client* has sent to the mock. */
  outgoing: any[];
}

// --------------------------------------------------------------------
//  High-level side (T-7-06, T-7-12)
// --------------------------------------------------------------------

export type SessionState = "running" | "paused" | "completed" | "failed" | "cancelled";

export interface Session {
  id: string;
  title: string;
  state: SessionState;
  tokensIn: number;
  tokensOut: number;
  totalCostUsd: number;
  startedAt: number;
  lastActiveAt: number;
}

export interface TaskEvent {
  seq: number;
  sessionId: string;
  type: "tool_call" | "todo_update" | "summary" | "state_change" | "text_delta";
  payload: Record<string, unknown>;
  ts: number;
}

export interface Grant {
  id: string;
  scope: "session" | "project" | "user";
  scopeId: string;
  category: string;
  decision: "allow" | "deny";
  reason: string;
  createdAt: number;
}

export interface MockRpcState {
  sessions: Map<string, Session>;
  events: TaskEvent[];
  grants: Map<string, Grant>;
  nextSeq: number;
  currentSessionId: string | null;
  modelName: string;
  presets: Set<"permissive" | "cautious" | "strict">;
}

export class MockRpcServer extends EventEmitter {
  // -- wire-protocol side ----------------------------------------
  private handlers = new Map<string, MockHandler>();
  private eventQueues = new Map<string, MockEvent[]>();
  private eventWaiters = new Map<string, Array<(ev: MockEvent) => void>>();
  private closed = new Map<string, boolean>();
  /** Indexed by id. The wire-protocol API and the
   *  high-level API both read/write this map, so
   *  `sessionSpawn` followed by a `pushEvent` on the
   *  same id is consistent. */
  private wireSessions = new Map<string, MockSession>();
  private defaultSessionId: string;
  private nowFn: () => number = () => Date.now();
  private peers: PeerSide[] = [];
  private readonly sessionListeners = new Map<string, Set<(ev: MockEvent) => void>>();

  // -- high-level side (T-7-06, T-7-12) --------------------------
  public readonly state: MockRpcState;

  constructor(opts: { sessionId?: string } = {}) {
    super();
    this.defaultSessionId = opts.sessionId ?? "mock-session-1";
    this.wireSessions.set(this.defaultSessionId, this.newWireSession(this.defaultSessionId));
    this.state = {
      sessions: new Map(),
      events: [],
      grants: new Map(),
      nextSeq: 1,
      currentSessionId: null,
      modelName: "claude-sonnet-4-5",
      presets: new Set(),
    };
  }

  // ===================================================================
  //  Wire-protocol side (T-6-02)
  // ===================================================================

  reset(): void {
    this.handlers.clear();
    this.eventQueues.clear();
    this.eventWaiters.clear();
    this.closed.clear();
    this.wireSessions.clear();
    this.sessionListeners.clear();
    this.peers = [];
    this.wireSessions.set(this.defaultSessionId, this.newWireSession(this.defaultSessionId));
    // Don't touch the high-level state — the E2E tests
    // build a fresh MockRpcServer per test.
  }

  setNow(fn: () => number): void { this.nowFn = fn; }
  now(): number { return this.nowFn(); }

  // --- Handler registration -----------------------------------------

  handle(method: string, handler: MockHandler): void {
    this.handlers.set(method, handler);
  }

  remove(method: string): void { this.handlers.delete(method); }

  // --- Wire-level entry points --------------------------------------

  peerSocket(): MockSocket {
    const peer: PeerSide = {
      socket: this.makeSocket(),
      outgoing: [],
    };
    this.peers.push(peer);
    peer.socket.setOnClose(() => {
      const idx = this.peers.indexOf(peer);
      if (idx >= 0) this.peers.splice(idx, 1);
    });
    return peer.socket;
  }

  outgoing(): any[] {
    return this.peers.flatMap((p) => p.outgoing);
  }

  pushEvent(
    sessionId: string,
    ev: Omit<MockEvent, "seq" | "ts"> & { seq?: number; ts?: number }
  ): MockEvent {
    const session = this.wireSessions.get(sessionId);
    if (!session) throw new Error(`unknown session: ${sessionId}`);
    if (this.closed.get(sessionId)) {
      throw new Error(`session closed: ${sessionId}`);
    }
    const full: MockEvent = {
      ...ev,
      sessionId,
      seq: ev.seq ?? session.lastSeq + 1,
      ts: ev.ts ?? this.nowFn(),
    };
    session.lastSeq = Math.max(session.lastSeq, full.seq as number);
    const queue = this.eventQueues.get(sessionId) ?? [];
    queue.push(full);
    this.eventQueues.set(sessionId, queue);
    this.broadcastEvent(full);
    const env: RpcNotification = {
      jsonrpc: "2.0",
      method: full.kind,
      params: {
        kind: full.kind,
        sessionId: full.sessionId,
        seq: full.seq,
        ts: full.ts,
        event: full.params,
        params: full.params,
      },
    };
    for (const peer of this.peers) {
      peer.socket.injectEnvelope(env);
    }
    return full;
  }

  async *streamEvents(
    sessionId: string,
    sinceSeq: number = -1
  ): AsyncGenerator<MockEvent> {
    const queue = this.eventQueues.get(sessionId) ?? [];
    let i = 0;
    if (sinceSeq >= 0) {
      for (const ev of queue) {
        if ((ev.seq as number) > sinceSeq) {
          yield ev;
          i++;
        }
      }
    } else {
      for (const ev of queue) yield ev;
    }
    while (!this.closed.get(sessionId)) {
      const next = queue[i];
      if (next) {
        i++;
        yield next;
        continue;
      }
      const ev = await this.waitForEvent(sessionId);
      yield ev;
    }
  }

  subscribe(sessionId: string, onEvent: (ev: MockEvent) => void): Subscription {
    const set = this.sessionListeners.get(sessionId) ?? new Set();
    this.sessionListeners.set(sessionId, set);
    set.add(onEvent);
    return {
      unsubscribe: () => {
        set.delete(onEvent);
        if (set.size === 0) this.sessionListeners.delete(sessionId);
      },
    };
  }

  closeSession(sessionId: string): void {
    this.closed.set(sessionId, true);
  }

  newSession(id?: string): MockSession {
    const sid = id ?? this.defaultSessionId;
    const s: MockSession = {
      id: sid,
      transcript: [],
      lastSeq: 0,
      state: "running",
      tokensIn: 0,
      tokensOut: 0,
      tokensByKind: {},
    };
    this.wireSessions.set(sid, s);
    this.eventQueues.set(sid, []);
    return s;
  }

  getSession(id: string): MockSession | undefined { return this.wireSessions.get(id); }
  listSessions(): MockSession[] {
    const out: MockSession[] = [];
    this.wireSessions.forEach((s) => { out.push(s); });
    return out;
  }
  ensureSession(id?: string): MockSession {
    return this.wireSessions.get(id ?? this.defaultSessionId) ?? this.newSession(id);
  }

  // -- Internal helpers --------------------------------------------

  private newWireSession(id: string): MockSession {
    return {
      id,
      transcript: [],
      lastSeq: 0,
      state: "running",
      tokensIn: 0,
      tokensOut: 0,
      tokensByKind: {},
    };
  }

  private makeSocket(): MockSocket {
    let dataListener: ((chunk: Buffer | string) => void) | null = null;
    let errorListener: ((err: Error) => void) | null = null;
    let closeListener: (() => void) | null = null;
    const sock: MockSocket = {
      closed: false,
      attached: false,
      injectLine: (line: string) => {
        if (dataListener) dataListener(Buffer.from(line, "utf-8"));
      },
      injectEnvelope: (env: any) => {
        if (dataListener) dataListener(Buffer.from(JSON.stringify(env) + "\n", "utf-8"));
      },
      setOnClose: (fn) => { closeListener = fn; },
      write: (data: string | Buffer, _cb?: (err?: Error | null) => void) => {
        const text = typeof data === "string" ? data : data.toString("utf-8");
        for (const line of text.split("\n")) {
          if (line.trim().length === 0) continue;
          let parsed: any;
          try { parsed = JSON.parse(line); } catch { continue; }
          this.handleIncoming(parsed);
        }
        return true;
      },
      on: (event: string, listener: any) => {
        if (event === "data") { dataListener = listener; sock.attached = true; }
        else if (event === "error") { errorListener = listener; }
        else if (event === "close") { closeListener = listener; }
        return sock;
      },
      end: () => {
        sock.closed = true;
        if (closeListener) closeListener();
      },
      destroy: () => {
        sock.closed = true;
        if (closeListener) closeListener();
      },
      setNoDelay: () => { /* noop for mock */ },
    };
    void errorListener;
    return sock;
  }

  private handleIncoming(req: any): void {
    for (const peer of this.peers) {
      peer.outgoing.push(req);
    }
    if (!this.isRequest(req)) {
      return;
    }
    const handler = this.handlers.get(req.method);
    if (!handler) {
      this.sendReply(err(req.id, -32601, `Method not found: ${req.method}`));
      return;
    }
    const session = this.findSessionForParams(req.params);
    Promise.resolve()
      .then(() => handler(req.params, { session }))
      .then((result) => {
        this.sendReply(ok(req.id, result));
      })
      .catch((e: any) => {
        if (e && typeof e === "object" && "code" in e && "message" in e) {
          this.sendReply(err(req.id, Number((e as any).code), String((e as any).message), (e as any).data));
        } else {
          this.sendReply(err(req.id, -32603, e?.message ?? String(e)));
        }
      });
  }

  private sendReply(env: any): void {
    const line = JSON.stringify(env) + "\n";
    const peer = this.peers[this.peers.length - 1];
    if (peer) peer.socket.injectLine(line);
  }

  private isRequest(env: any): env is { jsonrpc: "2.0"; id: number; method: string; params?: any } {
    return env && typeof env === "object"
      && typeof env.method === "string"
      && typeof env.id === "number";
  }

  private broadcastEvent(ev: MockEvent): void {
    const waiters = this.eventWaiters.get(ev.sessionId ?? this.defaultSessionId);
    if (waiters && waiters.length > 0) {
      const w = waiters.shift()!;
      w(ev);
    }
    const listeners = this.sessionListeners.get(ev.sessionId ?? this.defaultSessionId);
    if (listeners) {
      const arr: Array<(ev: MockEvent) => void> = [];
      listeners.forEach((h) => { arr.push(h); });
      for (const h of arr) {
        try { h(ev); } catch (e) { /* ignore */ }
      }
    }
  }

  private findSessionForParams(params: any): MockSession | null {
    if (!params || typeof params !== "object") return null;
    const id = (params as any).sessionId ?? (params as any).id;
    if (typeof id !== "string") return null;
    return this.wireSessions.get(id) ?? null;
  }

  private waitForEvent(sessionId: string): Promise<MockEvent> {
    return new Promise((resolve) => {
      const arr = this.eventWaiters.get(sessionId) ?? [];
      arr.push((ev) => resolve(ev));
      this.eventWaiters.set(sessionId, arr);
    });
  }

  // -- Client bridge ------------------------------------------------

  buildClient(overrides: Partial<ConstructorParameters<typeof JsonRpcClient>[0]> = {}): JsonRpcClient {
    const sock = this.peerSocket();
    const client = new JsonRpcClient(
      {
        autoReconnect: false,
        timeoutMs: 5_000,
        ...overrides,
      },
      { socketFactory: () => sock, preConnectedSocket: sock } satisfies JsonRpcClientDeps
    );
    return client;
  }

  // ===================================================================
  //  High-level side (T-7-06, T-7-12) — the E2E lifecycle
  // ===================================================================

  // -- session lifecycle ---------------------------------------------

  /** `session/spawn` — create a session and start it. */
  async sessionSpawn(opts: { title?: string; cwd?: string; model?: string }): Promise<{ id: string }> {
    const id = `ses-${this.state.sessions.size + 1}-${Date.now()}`;
    const now = Date.now();
    const session: Session = {
      id,
      title: opts.title ?? `Session ${this.state.sessions.size + 1}`,
      state: "running",
      tokensIn: 0,
      tokensOut: 0,
      totalCostUsd: 0,
      startedAt: now,
      lastActiveAt: now,
    };
    this.state.sessions.set(id, session);
    this.state.currentSessionId = id;
    if (opts.model) this.state.modelName = opts.model;
    this.emitEvent({
      sessionId: id,
      type: "state_change",
      payload: { from: null, to: "running" },
    });
    return { id };
  }

  /** `task/resume` — resume a paused session. */
  async taskResume(opts: { id?: string }): Promise<{ ok: true }> {
    const id = opts.id ?? this.state.currentSessionId;
    if (!id) throw new Error("no active session");
    const s = this.state.sessions.get(id);
    if (!s) throw new Error(`unknown session: ${id}`);
    const from = s.state;
    s.state = "running";
    s.lastActiveAt = Date.now();
    this.emitEvent({
      sessionId: id,
      type: "state_change",
      payload: { from, to: "running" },
    });
    return { ok: true };
  }

  /** `task/pause` — pause a running session. */
  async taskPause(opts: { id?: string }): Promise<{ ok: true }> {
    const id = opts.id ?? this.state.currentSessionId;
    if (!id) throw new Error("no active session");
    const s = this.state.sessions.get(id);
    if (!s) throw new Error(`unknown session: ${id}`);
    const from = s.state;
    s.state = "paused";
    s.lastActiveAt = Date.now();
    this.emitEvent({
      sessionId: id,
      type: "state_change",
      payload: { from, to: "paused" },
    });
    return { ok: true };
  }

  /** `task/kill` — kill a session (graceful). */
  async taskKill(opts: { id?: string }): Promise<{ ok: true }> {
    const id = opts.id ?? this.state.currentSessionId;
    if (!id) throw new Error("no active session");
    const s = this.state.sessions.get(id);
    if (!s) throw new Error(`unknown session: ${id}`);
    const from = s.state;
    s.state = "cancelled";
    s.lastActiveAt = Date.now();
    this.emitEvent({
      sessionId: id,
      type: "state_change",
      payload: { from, to: "cancelled" },
    });
    return { ok: true };
  }

  /** `session/tokens` — return token usage for the active session. */
  async sessionTokens(opts: { id?: string }): Promise<{
    input: number; output: number; total: number; costUsd: number;
  }> {
    const id = opts.id ?? this.state.currentSessionId;
    if (!id) throw new Error("no active session");
    const s = this.state.sessions.get(id);
    if (!s) throw new Error(`unknown session: ${id}`);
    return {
      input: s.tokensIn,
      output: s.tokensOut,
      total: s.tokensIn + s.tokensOut,
      costUsd: s.totalCostUsd,
    };
  }

  // -- grants ---------------------------------------------------------

  /** `grants/list` — list active grants (all scopes). */
  async grantsList(): Promise<Grant[]> {
    return Array.from(this.state.grants.values());
  }

  /** `grants/revoke` — remove a grant by id. */
  async grantsRevoke(opts: { id: string }): Promise<{ ok: true; id: string }> {
    if (!this.state.grants.has(opts.id)) {
      throw new Error(`unknown grant: ${opts.id}`);
    }
    this.state.grants.delete(opts.id);
    return { ok: true, id: opts.id };
  }

  /** `grants/setPreset` — record the active preset. */
  async grantsSetPreset(opts: { preset: "permissive" | "cautious" | "strict" }): Promise<{ ok: true }> {
    this.state.presets.clear();
    this.state.presets.add(opts.preset);
    return { ok: true };
  }

  // -- model ----------------------------------------------------------

  /** `model/set` — switch the active model. */
  async modelSet(opts: { name: string }): Promise<{ ok: true; name: string }> {
    this.state.modelName = opts.name;
    return { ok: true, name: opts.name };
  }

  // -- event stream ---------------------------------------------------

  private emitEvent(partial: Omit<TaskEvent, "seq" | "ts">): void {
    const ev: TaskEvent = {
      ...partial,
      seq: this.state.nextSeq++,
      ts: Date.now(),
    };
    this.state.events.push(ev);
    this.emit("event", ev);
  }

  /** Synthesize N tool-call events for the active session. */
  pushToolCallEvents(n: number, sessionId?: string): TaskEvent[] {
    const sid = sessionId ?? this.state.currentSessionId;
    if (!sid) throw new Error("no active session");
    const s = this.state.sessions.get(sid);
    if (!s) throw new Error(`unknown session: ${sid}`);
    const out: TaskEvent[] = [];
    for (let i = 0; i < n; i++) {
      s.tokensIn += 200;
      s.tokensOut += 100;
      s.totalCostUsd += 0.001;
      s.lastActiveAt = Date.now();
      const ev: TaskEvent = {
        seq: this.state.nextSeq++,
        sessionId: sid,
        type: "tool_call",
        payload: { tool: "bash", category: "shell.command.ls" },
        ts: Date.now(),
      };
      this.state.events.push(ev);
      this.emit("event", ev);
      // Also push through the wire so attached
      // JsonRpcClients receive it. The TUI's
      // useRpcSubscription will filter to kind=tool_call
      // and the ring buffer will bound it to 200.
      this.broadcastWireEvent(sid, "tool_call", ev);
      out.push(ev);
    }
    return out;
  }

  /** Internal: push a single event through the wire
   *  (notification envelope) to all attached peers. */
  private broadcastWireEvent(sessionId: string, kind: string, ev: { seq: number; ts: number; payload: any }): void {
    const env: RpcNotification = {
      jsonrpc: "2.0",
      method: kind,
      params: {
        kind,
        sessionId,
        seq: ev.seq,
        ts: ev.ts,
        event: ev.payload,
        params: ev.payload,
      },
    };
    for (const peer of this.peers) {
      peer.socket.injectEnvelope(env);
    }
  }
}

export default MockRpcServer;
