// Phase 3: in-process mock of the supervisor's JSON-RPC surface.
//
// The mock is the test contract for the desktop's RPC layer.
// Tests construct one, install it into a `JsonRpcClient` (so the
// client's `call` / `subscribe` go through the mock), and then
// drive the full client surface without any real network.
//
// The mock's API is shaped by the TS-D1 test suite (see
// `__tests__/MockRpcServer.test.ts`): `handle` registers a method,
// `remove` unregisters, `reset` clears, `pushEvent` enqueues an
// event, `streamEvents` exposes the AsyncGenerator the subscribe
// path reads from, and `installInto` swaps the client's
// `fetchImpl` / `eventSourceImpl` for the in-process equivalents.

import {
  err as buildErr,
  ok as buildOk,
  RpcError,
  type Grant,
  type ModelInfo,
  type RpcEvent,
  type RpcRequest,
  type SessionDetail,
  type SessionListItem,
  type SessionState,
  type TaskInfo,
  type WorkflowSummary,
} from './types';
import type { EventSourceImpl, EventSourceLike, FetchImpl, JsonRpcClient } from './client';

interface SessionEntry {
  id: string;
  state: SessionState;
  /** Per-session log of events. The AsyncGenerator yields them in
   *  push order; `subscribeEvents` registers a listener that
   *  flushes any new entries when `pushEvent` runs. */
  log: RpcEvent[];
  /** Per-session seq counter. Bumped on every `pushEvent`. */
  lastSeq: number;
  /** Active subscriptions — they each hold a queued-until-next-tick
   *  promise that the mock resolves after every push. */
  subs: Set<{
    sinceSeq: number;
    listener: (ev: RpcEvent) => void;
    buffer: RpcEvent[];
    closed: boolean;
  }>;
}

interface MockContext {
  session: SessionEntry | null;
}

type Handler = (params: unknown, ctx: MockContext) => unknown | Promise<unknown>;

/** R282: subset of {@link ProviderInfo} the test mock
 *  accepts. We don't reuse the full type so the test
 *  fixtures stay small and so the contract (the
 *  `hasApiKey` flag in particular) is enforced at the
 *  mock's boundary. */
export interface MockProviderInfo {
  name: string;
  type: string;
  baseUrl: string;
  apiKeyEnv: string;
  defaultModel: string | null;
  hasApiKey: boolean;
  models: {
    id: string;
    inputPer1k: number;
    outputPer1k: number;
    context: number;
    default: boolean;
  }[];
}

export interface MockSeed {
  /** Convenience: the first session to create. Subsequent
   *  `newSession` calls append. */
  sessionId?: string;
  sessions?: SessionDetail[];
  grants?: Grant[];
  models?: ModelInfo[];
  /** R282: provider catalog returned by listProviders +
   *  listAvailableModels. Each entry carries a
   *  {@code hasApiKey} flag the mock uses verbatim
   *  (rather than reading the host's env vars). */
  providers?: MockProviderInfo[];
  workflows?: WorkflowSummary[];
  tasks?: TaskInfo[];
  lastUsedModelId?: string;
  events?: RpcEvent[];
  /** R284: per-session pre-compaction snapshots for the
   *  MessageList's "View original" affordance. Tests
   *  populate this when a session's transcript contains
   *  a compaction-summary message so
   *  {@code compact/getSnapshot} returns a fixture
   *  without needing a real daemon SnapshotStore. The
   *  key is sessionId; the value is the list of
   *  snapshots in {@code compactionIndex} order (0, 1,
   *  2, ...). */
  snapshots?: Record<string, MockSnapshot[]>;
}

/** R284: a single pre-compaction snapshot row, the
 *  shape {@code compact/getSnapshot} returns. Matches
 *  the daemon's wire contract (kept loose so a future
 *  server-side field addition doesn't break tests). */
export interface MockSnapshot {
  compactionIndex: number;
  originalMessageCount: number;
  keptMessageCount: number;
  createdAt?: string;
  fileName?: string;
  summary?: string;
  messages?: Array<Record<string, unknown>>;
}

/** The in-process mock. Tests construct one per `it()` block so
 *  state doesn't leak between cases. */
export class MockRpcServer {
  private sessions = new Map<string, SessionEntry>();
  private handlers = new Map<string, Handler>();
  private store: {
    sessions: SessionDetail[];
    grants: Grant[];
    models: ModelInfo[];
    providers: MockProviderInfo[];
    workflows: WorkflowSummary[];
    tasks: TaskInfo[];
    lastUsedModelId?: string;
    snapshots: Record<string, MockSnapshot[]>;
    /** R284: the {@code MockSeed.sessionId} the mock was
     *  constructed with. The compact/* RPCs resolve an
     *  omitted {@code sessionId} param to this value (the
     *  daemon defaults to the engine's active session).
     *  Stored separately from {@code sessions} because the
     *  engine's "active session" is a higher-level concept
     *  than the sessions list. */
    defaultSessionId?: string;
    /** R285: mock-side mirror of the engine's
     *  currentProvider / currentModel. Set by the
     *  switchProvider handler and echoed by
     *  listAvailableModels so the MessageInput
     *  dropdown's active row reflects the latest
     *  pick on first paint. */
    currentProvider?: string;
    currentModel?: string;
    /** R285: mock-side mirror of the active
     *  variant row. Set by switchVariant +
     *  switchProvider(variant). Echoed by
     *  listAvailableModels so the Quality pill
     *  can render the right active state
     *  before the user clicks anything. */
    currentVariant?: string;
    activeVariant?: Record<string, unknown> | null;
  } = {
    sessions: [], grants: [], models: [],
    providers: [], workflows: [], tasks: [],
    snapshots: {},
  };
  private idCounter = 1;
  private now = 1_700_000_000_000;
  private installHandle: { restore: () => void } | null = null;
  /** Per-handler call log. Tests can assert on what was sent. */
  public callLog: { method: string; params: unknown; ts: number }[] = [];

  constructor(seed: MockSeed = {}) {
    this.store = {
      // Deep-clone via JSON so a `session/restore` or
      // `session/trash` call in one test doesn't mutate
      // the test's module-level seed object and leak
      // into the next test's fixture. The clone is
      // cheap (sessions are small DTOs) and keeps the
      // mock's behaviour predictable across test cases.
      sessions: seed.sessions ? JSON.parse(JSON.stringify(seed.sessions)) : [],
      grants: seed.grants ? JSON.parse(JSON.stringify(seed.grants)) : [],
      models: seed.models ? JSON.parse(JSON.stringify(seed.models)) : [],
      providers: seed.providers ? JSON.parse(JSON.stringify(seed.providers)) : [],
      workflows: seed.workflows ? JSON.parse(JSON.stringify(seed.workflows)) : [],
      tasks: seed.tasks ? JSON.parse(JSON.stringify(seed.tasks)) : [],
      lastUsedModelId: seed.lastUsedModelId,
      // R284: per-session pre-compaction snapshots. Same
      // JSON deep-clone so a test that mutates the
      // response (e.g. optimistic append) doesn't leak
      // into the next test's fixture.
      snapshots: seed.snapshots
        ? JSON.parse(JSON.stringify(seed.snapshots))
        : {},
      defaultSessionId: seed.sessionId,
    };
    if (seed.sessionId) this.ensureSession(seed.sessionId);
    this.registerDefaults();
  }

  // --- Session management ----------------------------------------------

  newSession(id: string): SessionEntry {
    return this.ensureSession(id);
  }

  ensureSession(id: string): SessionEntry {
    let e = this.sessions.get(id);
    if (!e) {
      e = { id, state: 'running', log: [], lastSeq: 0, subs: new Set() };
      this.sessions.set(id, e);
    }
    return e;
  }

  // --- Handler lifecycle ----------------------------------------------

  /** Register (or replace) the handler for `method`. The handler's
   *  return value becomes the JSON-RPC `result`; a thrown error
   *  is wrapped in an error envelope. */
  handle(method: string, fn: Handler): this {
    this.handlers.set(method, fn);
    return this;
  }

  remove(method: string): this {
    this.handlers.delete(method);
    return this;
  }

  reset(): this {
    this.handlers.clear();
    this.registerDefaults();
    return this;
  }

  /** Convenience for tests that want a fully-resolved session
   *  view to compare against. Returns a copy so the caller can
   *  mutate without disturbing the in-process state. */
  closeSession(id: string): boolean {
    const idx = this.store.sessions.findIndex((s) => s.id === id);
    if (idx < 0) return false;
    this.store.sessions.splice(idx, 1);
    this.sessions.delete(id);
    return true;
  }

  // --- Event queue ----------------------------------------------------

  /** Push an event onto the session's log. The seq is auto-
   *  assigned; the returned envelope carries the new number. */
  pushEvent(sessionId: string, ev: Omit<RpcEvent, 'seq' | 'ts' | 'sessionId'> & Partial<Pick<RpcEvent, 'seq' | 'ts'>>): RpcEvent {
    const e = this.ensureSession(sessionId);
    e.lastSeq += 1;
    const next: RpcEvent = {
      seq: e.lastSeq,
      kind: ev.kind,
      sessionId,
      ts: ev.ts ?? this.now,
      params: ev.params ?? {},
    };
    e.log.push(next);
    // Drain subscribers: each sub's `buffer` is appended, and the
    // listener is invoked on the next microtask (matches the
    // real SSE cadence).
    for (const sub of e.subs) {
      if (sub.closed) continue;
      if (next.seq <= sub.sinceSeq) continue;
      sub.buffer.push(next);
      queueMicrotask(() => {
        if (sub.closed) return;
        while (sub.buffer.length > 0) {
          const ev2 = sub.buffer.shift()!;
          sub.listener(ev2);
        }
      });
    }
    return next;
  }

  /** Async generator the `subscribe` path reads from. Yields
   *  events >= `sinceSeq`, then blocks for new pushes. */
  async *streamEvents(sessionId: string, sinceSeq: number = 0): AsyncGenerator<RpcEvent, void, void> {
    const e = this.ensureSession(sessionId);
    let cursor = sinceSeq;
    while (true) {
      // Flush all events > cursor.
      const ready = e.log.filter((ev) => ev.seq > cursor);
      for (const ev of ready) {
        cursor = ev.seq;
        yield ev;
      }
      // Wait for the next push. The promise resolves on
      // `pushEvent`; if the session is torn down (no such
      // method here, but kept for symmetry) it rejects.
      await new Promise<void>((resolve) => {
        const sub = {
          sinceSeq: cursor,
          listener: () => { resolve(); },
          buffer: [],
          closed: false,
        };
        e.subs.add(sub);
        // Also resolve after a 1ms tick so a missed push doesn't
        // hang the test forever.
        setTimeout(() => { resolve(); }, 1);
      });
      // Loop again — the next iteration flushes the new events.
    }
  }

  /** Listener-style subscription for non-generator consumers. */
  subscribe(sessionId: string, listener: (ev: RpcEvent) => void, sinceSeq: number = 0): () => void {
    const e = this.ensureSession(sessionId);
    const sub = { sinceSeq, listener, buffer: [], closed: false };
    e.subs.add(sub);
    // Replay any backlog synchronously.
    for (const ev of e.log) {
      if (ev.seq > sinceSeq) {
        queueMicrotask(() => listener(ev));
      }
    }
    return () => {
      sub.closed = true;
      e.subs.delete(sub);
    };
  }

  // --- Install into a real client -------------------------------------

  installInto(client: JsonRpcClient): void {
    if (this.installHandle) this.installHandle.restore();
    const fetchImpl: FetchImpl = async (_input, init) => {
      const body = JSON.parse(String(init?.body ?? '{}')) as RpcRequest;
      const id = body.id;
      const params = body.params;
      this.callLog.push({ method: body.method, params, ts: this.now });
      // Special-cased: stream URL (the client builds a
      // different endpoint for `subscribe`). We handle the
      // method name encoded in the URL — the test's client
      // just uses `method` and `params` from the body, so the
      // path doesn't matter here.
      try {
        const result = await this.dispatch(body.method, params);
        return {
          ok: true,
          status: 200,
          statusText: 'OK',
          text: async () => JSON.stringify(buildOk(id, result)),
          json: async () => buildOk(id, result),
        };
      } catch (e: any) {
        const code = e instanceof RpcError ? e.code : -32603;
        const message = e?.message ?? 'Internal error';
        return {
          ok: true,
          status: 200,
          statusText: 'OK',
          text: async () => JSON.stringify(buildErr(id, code, message)),
          json: async () => buildErr(id, code, message),
        };
      }
    };
    const eventSourceImpl: EventSourceImpl = (url) => {
      const u = new URL(url, 'http://localhost');
      const method = u.searchParams.get('method') ?? '';
      const since = Number(u.searchParams.get('since') ?? '0');
      const sessionId = (() => {
        // The client encodes the session id in the params
        // (e.g. `params.sessionId=...`). When the test uses
        // `subscribe` with a path like `?method=session/events&sessionId=...`,
        // the session id is `sessionId`. Fall back to the seed
        // session.
        const sid = u.searchParams.get('sessionId') ?? u.searchParams.get('id');
        if (sid) return sid;
        // Otherwise the test's seed.sessionId.
        return [...this.sessions.keys()][0] ?? 's-1';
      })();
      const listeners: Array<(ev: { data: string }) => void> = [];
      const openListeners: Array<(ev: Event) => void> = [];
      const errorListeners: Array<(ev: Event) => void> = [];
      // Hook into the stream — translate every RpcEvent into
      // an onmessage with the JSON envelope the client parses.
      const unsub = this.subscribe(sessionId, (ev) => {
        const data = JSON.stringify({ jsonrpc: '2.0', method, params: ev });
        for (const fn of listeners) fn({ data });
      }, since);
      const es: EventSourceLike = {
        url,
        readyState: 1,
        onmessage: null,
        onerror: null,
        onopen: null,
        close() {
          unsub();
          listeners.length = 0;
        },
      };
      Object.defineProperty(es, 'onmessage', {
        get() { return listeners[0] ?? null; },
        set(fn) {
          listeners.length = 0;
          if (fn) listeners.push(fn);
        },
      });
      Object.defineProperty(es, 'onerror', {
        get() { return errorListeners[0] ?? null; },
        set(fn) {
          errorListeners.length = 0;
          if (fn) errorListeners.push(fn);
        },
      });
      Object.defineProperty(es, 'onopen', {
        get() { return openListeners[0] ?? null; },
        set(fn) {
          openListeners.length = 0;
          if (fn) openListeners.push(fn);
        },
      });
      // Fire open asynchronously so callers can register
      // listeners first.
      queueMicrotask(() => {
        for (const fn of openListeners) fn(new Event('open'));
      });
      return es;
    };
    // Reach into the private fields. The mock is a test-only
    // file so the dependency is intentional.
    (client as any).fetchImpl = fetchImpl;
    (client as any).eventSourceImpl = eventSourceImpl;
    this.installHandle = {
      restore: () => {
        (client as any).fetchImpl = undefined;
        (client as any).eventSourceImpl = undefined;
      },
    };
  }

  // --- Dispatch -------------------------------------------------------

  private async dispatch(method: string, params: unknown): Promise<unknown> {
    const handler = this.handlers.get(method);
    if (!handler) {
      throw new RpcError(method, -32601, `Method not found: ${method}`);
    }
    // Resolve the ctx.session from params if a sessionId is present.
    const ctx: MockContext = {
      session: this.resolveCtxSession(params),
    };
    try {
      return await handler(params ?? {}, ctx);
    } catch (e: any) {
      if (e instanceof RpcError) throw e;
      // Wrap non-RpcError throws as -32603 Internal error.
      throw new RpcError(method, -32603, e?.message ?? String(e));
    }
  }

  private resolveCtxSession(params: unknown): SessionEntry | null {
    if (!params || typeof params !== 'object') return null;
    const id = (params as Record<string, unknown>).sessionId
      ?? (params as Record<string, unknown>).id;
    if (typeof id !== 'string') return null;
    return this.sessions.get(id) ?? null;
  }

  // --- Default handlers -----------------------------------------------

  private registerDefaults(): void {
    // The default handlers reproduce the supervisor's behaviour
    // for the methods Phase 3-7 components call. Tests that need
    // different responses can override with `handle()`.
    // real daemon registers `listSessions`; the
    // mock must use the same wire name so tests and
    // production exercise the same code path.
    this.handle('listSessions', (params) => {
      const p = (params ?? {}) as { includeTrashed?: boolean; limit?: number; withPreview?: boolean };
      const all = this.store.sessions
        .filter((s) => (p.includeTrashed ? true : !s.trashedAt))
        .sort((a, b) => b.lastActiveAt - a.lastActiveAt);
      const total = all.length;
      const limit = p.limit ?? 200;
      const slice = all.slice(0, limit);
      const items: SessionListItem[] = slice.map((d) => toSummary(d, !!p.withPreview));
      return { sessions: items, total, hasMore: total > limit };
    });
    this.handle('session/show', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const s = this.store.sessions.find((x) => x.id === id);
      if (!s) throw new RpcError('session/show', -32004, `session ${id} not found`);
      return s;
    });
    this.handle('session/spawn', (params) => {
      const { prompt, cwd, model } = (params ?? {}) as { prompt: string; cwd: string; model?: string };
      const id = `sess-${this.idCounter++}`;
      const now = this.now;
      const detail: SessionDetail = {
        id,
        title: prompt.slice(0, 60),
        cwd,
        model: model ?? this.store.lastUsedModelId ?? this.store.models[0]?.id ?? 'm-default',
        state: 'running',
        startedAt: now,
        lastActiveAt: now,
        tokensIn: 0,
        tokensOut: 0,
        parentId: null,
        preview: prompt.slice(0, 200),
        effort: 'medium',
        messages: [{ id: 'm-1', role: 'user', content: prompt, ts: now }],
        todos: [],
        events: [],
        toolCounters: {},
      };
      this.store.sessions.push(detail);
      this.ensureSession(id);
      return { id, title: detail.title, startedAt: now };
    });
    this.handle('session/resume', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const s = this.store.sessions.find((x) => x.id === id);
      if (!s) throw new RpcError('session/resume', -32004, `session ${id} not found`);
      s.state = 'running';
      s.lastActiveAt = this.now;
      return { ok: true, state: s.state, resumedAt: this.now };
    });
    this.handle('session/delete', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const s = this.store.sessions.find((x) => x.id === id);
      if (s) s.trashedAt = this.now;
      return { ok: true };
    });
    this.handle('session/restore', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const s = this.store.sessions.find((x) => x.id === id);
      if (s) s.trashedAt = null;
      return { ok: true };
    });
    this.handle('session/trash', (params) => {
      const p = (params ?? {}) as { id?: string; empty?: boolean; restoreId?: string };
      if (p.empty) {
        this.store.sessions = this.store.sessions.filter((s) => !s.trashedAt);
        return { ok: true, emptied: true };
      }
      if (p.restoreId) {
        const s = this.store.sessions.find((x) => x.id === p.restoreId);
        if (s) s.trashedAt = null;
        return { ok: true, restored: p.restoreId };
      }
      if (p.id) {
        const s = this.store.sessions.find((x) => x.id === p.id);
        if (s) s.trashedAt = this.now;
      }
      return { ok: true };
    });
    this.handle('session/rename', (params) => {
      const { id, title } = (params ?? {}) as { id: string; title: string };
      const s = this.store.sessions.find((x) => x.id === id);
      if (s) s.title = title;
      return { ok: true };
    });
    this.handle('session/events', (params) => {
      const { id } = (params ?? {}) as { id: string };
      return this.store.sessions.find((x) => x.id === id)?.events ?? [];
    });
    this.handle('session/tokens', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const s = this.store.sessions.find((x) => x.id === id);
      const m = this.store.models.find((x) => x.id === s?.model) ?? this.store.models[0];
      const inT = s?.tokensIn ?? 0;
      const outT = s?.tokensOut ?? 0;
      const win = m?.contextWindow ?? 200_000;
      const costUsd = m
        ? (inT * m.pricing.inputPerM + outT * m.pricing.outputPerM) / 1_000_000
        : 0;
      return { tokensIn: inT, tokensOut: outT, totalTokens: inT + outT, costUsd, contextWindow: win, effectiveWindow: win };
    });
    this.handle('task/spawn', (params) => {
      const { sessionId, limits } = (params ?? {}) as { sessionId: string; limits?: Partial<TaskInfo['limits']> };
      const id = `task-${this.idCounter++}`;
      const task: TaskInfo = {
        id,
        sessionId,
        state: 'running',
        limits: { wallClockMs: 4 * 60 * 60_000, tokens: 4_000_000, calls: 200, fileWrites: 5_000, network: 200, ...(limits ?? {}) },
        startedAt: this.now,
        lastActiveAt: this.now,
      };
      this.store.tasks = [...this.store.tasks, task];
      return task;
    });
    this.handle('task/resume', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const t = this.store.tasks.find((x) => x.id === id);
      if (t) { t.state = 'running'; t.lastActiveAt = this.now; }
      return { ok: true, task: t };
    });
    this.handle('task/pause', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const t = this.store.tasks.find((x) => x.id === id);
      if (t) { t.state = 'paused'; t.lastActiveAt = this.now; }
      return { ok: true, task: t };
    });
    this.handle('task/kill', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const t = this.store.tasks.find((x) => x.id === id);
      if (t) { t.state = 'cancelled'; t.lastActiveAt = this.now; }
      return { ok: true, task: t };
    });
    this.handle('task/attach', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const t = this.store.tasks.find((x) => x.id === id);
      if (!t) throw new RpcError('task/attach', -32004, `task ${id} not found`);
      return { task: t, events: this.sessions.get(t.sessionId)?.log ?? [], fromSeq: 0 };
    });
    this.handle('task/attached', (params) => {
      const { sessionId, lastSeq } = (params ?? {}) as { sessionId: string; lastSeq: number; lastTs?: number };
      const e = this.sessions.get(sessionId);
      if (!e) {
        return { from: 0, to: 0, events: [], gap: false };
      }
      const from = lastSeq ?? 0;
      const events = e.log.filter((ev) => ev.seq > from);
      const to = e.lastSeq;
      // Heuristic: a gap when lastSeq is older than the oldest
      // event we still have. The ring buffer's lifetime isn't
      // modelled in the mock, so we just return gap=false unless
      // the caller asks for an explicit pre-history seq.
      return { from, to, events, gap: false };
    });
    this.handle('task/list', () => this.store.tasks);
    this.handle('task/events', (params) => {
      const { id } = (params ?? {}) as { id: string };
      return this.store.tasks.find((t) => t.id === id);
    });
    this.handle('task/setLimits', (params) => {
      const { id, limits } = (params ?? {}) as { id: string; limits: Partial<TaskInfo['limits']> };
      const t = this.store.tasks.find((x) => x.id === id);
      if (t) t.limits = { ...t.limits, ...limits };
      return { ok: true, task: t };
    });
    this.handle('grants/list', () => this.store.grants);
    this.handle('grants/revoke', (params) => {
      const { id } = (params ?? {}) as { id: string };
      this.store.grants = this.store.grants.filter((g) => g.id !== id);
      return { ok: true };
    });
    this.handle('grants/clear', (params) => {
      const { scope } = (params ?? {}) as { scope?: Grant['scope'] };
      this.store.grants = scope ? this.store.grants.filter((g) => g.scope !== scope) : [];
      return { ok: true };
    });
    this.handle('grants/setPreset', (params) => {
      const { preset } = (params ?? {}) as { preset: 'permissive' | 'cautious' | 'strict' };
      if (!['permissive', 'cautious', 'strict'].includes(preset)) {
        throw new RpcError('grants/setPreset', -32602, `unknown preset: ${preset}`);
      }
      return { ok: true, preset };
    });
    this.handle('model/list', () =>
      this.store.models.map((m) => ({
        ...m,
        lastUsedAt: m.id === this.store.lastUsedModelId ? this.now : m.lastUsedAt,
      })),
    );
    // R282: registry-backed catalog. Returns every
    // provider the mock was seeded with (regardless of
    // hasApiKey — the renderer filters client-side).
    // Each provider carries the hasApiKey flag so the
    // Settings panel can drop rows for providers the
    // user hasn't configured.
    this.handle('listProviders', () => ({
      ok: true,
      providers: this.store.providers,
      currentProvider: null,
      currentModel: null,
    }));
    this.handle('listAvailableModels', () => {
      const models: Array<Record<string, unknown>> = [];
      for (const p of this.store.providers) {
        for (const m of p.models) {
          models.push({
            id: m.id,
            name: m.id,
            provider: p.name,
            apiKeyEnv: p.apiKeyEnv,
            hasApiKey: p.hasApiKey,
            inputPer1k: m.inputPer1k,
            outputPer1k: m.outputPer1k,
            context: m.context,
            maxOutput: m.context,
            default: m.default,
          });
        }
      }
      return {
        ok: true,
        models,
        providers: this.store.providers.map((p) => ({
          name: p.name,
          apiKeyEnv: p.apiKeyEnv,
          hasApiKey: p.hasApiKey,
          defaultModel: p.defaultModel,
        })),
        currentProvider: this.store.currentProvider ?? null,
        currentModel: this.store.currentModel ?? null,
        // R285: surface the active variant row so the
        // MessageInput Quality pills can highlight the
        // active state on first paint without an extra
        // round-trip. Defaults to null when nothing
        // has been picked yet.
        currentVariant: this.store.currentVariant ?? null,
        activeVariant: this.store.activeVariant ?? null,
      };
    });
    // R285: provider/model swap. Mirrors the daemon's
    // switchProvider RPC: persists the new (provider,
    // model) on the mock store, optionally carries
    // a variant argument, and echoes the active
    // variant row back so the renderer's Quality
    // pill highlights it. Tests rely on this to
    // exercise `/model y:y` syntax + provider
    // dropdown changes end-to-end.
    this.handle('switchProvider', (params) => {
      const { provider, model, variant } = (params ?? {}) as {
        provider?: string | null;
        model?: string | null;
        variant?: string | null;
      };
      if (provider) this.store.currentProvider = provider;
      const targetModel = model || (this.store.currentModel ?? '');
      if (targetModel) this.store.currentModel = targetModel;
      if (variant) {
        this.store.currentVariant = variant;
        this.store.activeVariant = resolveVariant(variant);
      }
      return {
        ok: true,
        provider: this.store.currentProvider,
        model: this.store.currentModel,
        variant: this.store.currentVariant ?? null,
        activeVariant: this.store.activeVariant ?? null,
      };
    });
    // R285: variant-only swap. Mirrors the daemon's
    // switchVariant RPC: keeps the current
    // provider / model, just rotates the variant.
    // Returns the active variant row (with knob
    // values like temperature / maxTokens /
    // reasoningBudget) so the Quality pill can
    // echo the new state immediately.
    this.handle('switchVariant', (params) => {
      const { variant } = (params ?? {}) as { variant?: string | null };
      const name = (variant ?? '').toString().trim().toLowerCase();
      const resolved = resolveVariant(name);
      this.store.currentVariant = resolved.name;
      this.store.activeVariant = resolved;
      return {
        ok: true,
        variant: resolved.name,
        activeVariant: resolved,
      };
    });
    this.handle('model/get', (params) => {
      const { id } = (params ?? {}) as { id: string };
      return this.store.models.find((m) => m.id === id);
    });
    // R284: pre-compaction snapshot access for the
    // MessageList's "View original" affordance. Mirrors
    // the daemon's compact/listSnapshots +
    // compact/getSnapshot contract so tests don't need a
    // real SnapshotStore on disk.
    this.handle('compact/listSnapshots', (params) => {
      const p = (params ?? {}) as { sessionId?: string | null };
      const sid = p.sessionId || this.store.defaultSessionId || 'default';
      const rows = this.store.snapshots[sid] ?? [];
      return {
        ok: true,
        sessionId: sid,
        snapshots: rows.map((s) => ({
          compactionIndex: s.compactionIndex,
          originalMessageCount: s.originalMessageCount,
          keptMessageCount: s.keptMessageCount,
          createdAt: s.createdAt ?? null,
          fileName: s.fileName ?? `${sid}__${s.compactionIndex}.json`,
          summary: s.summary ?? '',
        })),
      };
    });
    this.handle('compact/getSnapshot', (params) => {
      const p = (params ?? {}) as { sessionId?: string | null; compactionIndex?: number };
      const sid = p.sessionId || this.store.defaultSessionId || 'default';
      const idx = p.compactionIndex ?? -1;
      const rows = this.store.snapshots[sid] ?? [];
      const hit = rows.find((s) => s.compactionIndex === idx);
      if (!hit) {
        return { ok: false, error: 'not-found', sessionId: sid, compactionIndex: idx };
      }
      return {
        ok: true,
        snapshot: {
          compactionIndex: hit.compactionIndex,
          originalMessageCount: hit.originalMessageCount,
          keptMessageCount: hit.keptMessageCount,
          createdAt: hit.createdAt ?? null,
          fileName: hit.fileName ?? `${sid}__${hit.compactionIndex}.json`,
          summary: hit.summary ?? '',
          messages: hit.messages ?? [],
        },
      };
    });
    this.handle('model/set', (params) => {
      const { id, sessionId } = (params ?? {}) as { id: string; sessionId?: string };
      this.store.lastUsedModelId = id;
      for (const s of this.store.sessions) {
        if (!sessionId || s.id === sessionId) s.model = id;
      }
      return { ok: true };
    });
    this.handle('workflow/list', () => this.store.workflows);
    this.handle('workflow/show', (params) => {
      const { name } = (params ?? {}) as { name: string };
      const w = this.store.workflows.find((x) => x.name === name);
      if (!w) throw new RpcError('workflow/show', -32004, `workflow ${name} not found`);
      return w;
    });
    this.handle('workflow/run', (params) => {
      const { name } = (params ?? {}) as { name: string };
      const w = this.store.workflows.find((x) => x.name === name);
      if (!w) throw new RpcError('workflow/run', -32004, `workflow ${name} not found`);
      const id = `sess-${this.idCounter++}`;
      const detail: SessionDetail = {
        id,
        title: `${name} run`,
        cwd: '/tmp/proj',
        model: this.store.lastUsedModelId ?? this.store.models[0]?.id ?? 'm-default',
        state: 'running',
        startedAt: this.now,
        lastActiveAt: this.now,
        tokensIn: 0,
        tokensOut: 0,
        parentId: null,
        effort: 'medium',
        messages: [],
        todos: [],
        events: [],
        toolCounters: {},
      };
      this.store.sessions.push(detail);
      this.ensureSession(id);
      return detail;
    });
    this.handle('workflow/upsert', (params) => {
      const { name, description, yaml } = (params ?? {}) as { name: string; description: string; yaml?: string };
      const existing = this.store.workflows.findIndex((w) => w.name === name);
      const entry: WorkflowSummary = { name, description, scope: 'user', builtin: false };
      if (existing >= 0) this.store.workflows[existing] = entry;
      else this.store.workflows.push(entry);
      return { ok: true, yaml: yaml ?? '' };
    });
    this.handle('workflow/delete', (params) => {
      const { name } = (params ?? {}) as { name: string };
      this.store.workflows = this.store.workflows.filter((w) => w.name !== name);
      return { ok: true };
    });
    this.handle('compact/status', (params) => {
      const { id } = (params ?? {}) as { id: string };
      const s = this.store.sessions.find((x) => x.id === id);
      const m = this.store.models.find((x) => x.id === s?.model) ?? this.store.models[0];
      const inT = s?.tokensIn ?? 0;
      const outT = s?.tokensOut ?? 0;
      const win = m?.contextWindow ?? 200_000;
      const costUsd = m
        ? (inT * m.pricing.inputPerM + outT * m.pricing.outputPerM) / 1_000_000
        : 0;
      return { tokensIn: inT, contextWindow: win, effectiveWindow: win, totalTokens: inT + outT, costUsd };
    });
    this.handle('compact/run', () => ({ ok: true }));
  }
}

function toSummary(d: SessionDetail, withPreview: boolean): SessionListItem {
  return {
    id: d.id,
    title: d.title,
    cwd: d.cwd,
    model: d.model,
    state: d.state,
    startedAt: d.startedAt,
    lastActiveAt: d.lastActiveAt,
    tokensIn: d.tokensIn,
    tokensOut: d.tokensOut,
    parentId: d.parentId,
    preview: withPreview ? d.preview : undefined,
    trashedAt: d.trashedAt,
  };
}

// Re-export so the tests can `import { ok, err } from
// '../MockRpcServer'` and pull the same builders the client uses.
export function ok<R>(id: number, result: R) { return buildOk(id, result); }
export function err(id: number, code: number, message: string, data?: unknown) {
  return buildErr(id, code, message, data);
}

// R285: variant resolver. Mirrors the daemon's
// Variant.byName() — opencode-style aliases
// ("default"/"medium"/"med" → MEDIUM,
// "low"/"fast" → LOW, "high"/"deep" → HIGH,
// "xhigh" → XHIGH), falling back to a generic
// default when nothing matches. Each preset
// carries the same temperature / maxTokens /
// reasoningBudget / extendedThinking knobs
// the daemon ships. Tests use this to
// exercise switchVariant end-to-end without
// needing a real ChatClient.
const VARIANT_PRESETS: Record<string, {
  name: string;
  description: string;
  temperature: number | null;
  maxTokens: number | null;
  reasoningBudget: number | null;
  extendedThinking: boolean | null;
}> = {
  low: {
    name: 'low', description: 'Low — fastest, lowest temperature.',
    temperature: 0.3, maxTokens: 16_000,
    reasoningBudget: null, extendedThinking: false,
  },
  medium: {
    name: 'medium', description: 'Medium — balanced (default).',
    temperature: 0.7, maxTokens: 32_000,
    reasoningBudget: null, extendedThinking: false,
  },
  high: {
    name: 'high', description: 'High — sharper, larger max.',
    temperature: 1.0, maxTokens: 48_000,
    reasoningBudget: null, extendedThinking: false,
  },
  xhigh: {
    name: 'xhigh', description: 'XHIGH — sharpest, with extended thinking.',
    temperature: 1.0, maxTokens: 64_000,
    reasoningBudget: 8_192, extendedThinking: true,
  },
};
export function resolveVariant(name: string | null | undefined): {
  name: string;
  description: string;
  temperature: number | null;
  maxTokens: number | null;
  reasoningBudget: number | null;
  extendedThinking: boolean | null;
} {
  const key = (name ?? '').trim().toLowerCase();
  if (key === 'default' || key === 'med') return VARIANT_PRESETS.medium;
  if (key === 'fast') return VARIANT_PRESETS.low;
  if (key === 'deep') return VARIANT_PRESETS.high;
  return VARIANT_PRESETS[key] ?? VARIANT_PRESETS.medium;
}
