/**
 * T-194: typed `compact/*` RPC surface for the AetherCode TUI.
 *
 * Mirrors `aethercode-compact/src/rpc.ts` and `design.md §2.9`:
 *
 *   "compact/run":    { from?, upTo?, force? } → { layer, beforeTokens, afterTokens, ms }
 *   "compact/status": void                     → { autoCompactDisabled, lastEvent }
 *   "compact/reset":  void                     → { ok: true }
 *   "compact/history":{ limit? }               → CompactEvent[]
 *
 * This is a thin wrapper around the existing `JsonRpcClient`
 * (prior round) that adds typed methods and a local fallback so the
 * TUI can be exercised without a live daemon. The status bar's
 * `<ContextMeter>` calls into this module — it never talks to
 * the daemon directly.
 *
 * Two modes:
 *   1. Remote (daemon present) — every method proxies through
 *      the supplied `JsonRpcClient`. T-194 lands the wire.
 *   2. Local (no daemon)        — a `LocalCompactRpc` instance
 *      owns a `CompactRpc` from `aethercode-compact` and serves
 *      the same four methods. The TUI uses this in tests and
 *      during the headless `--print` path.
 *
 * Both modes share the same `CompactRpcClient` interface so the
 * TUI doesn't have to branch on the transport.
 */

import { JsonRpcClient, RpcCallError } from "./jsonrpc.js";

/** `compact/run` parameters. */
export type CompactRunParams = {
  from?: string;
  upTo?: string;
  force?: boolean;
};

/** `compact/run` response. */
export type CompactRunResult = {
  layer: 1 | 2 | 3;
  beforeTokens: number;
  afterTokens: number;
  ms: number;
  failed: boolean;
  failureReason?: string;
};

/** `compact/status` response. */
export type CompactStatusResult = {
  autoCompactDisabled: boolean;
  consecutiveCompactFailures: number;
  lastFailureTs: number | null;
  lastEvent: CompactEvent | null;
};

/** `compact/reset` response. */
export type CompactResetResult = {
  ok: true;
  clearedFailures: number;
};

/** `compact/history` parameters. */
export type CompactHistoryParams = {
  limit?: number;
};

/** A single compact event (same shape on both sides). */
export type CompactEvent = {
  ts: number;
  layer: 1 | 2 | 3;
  beforeTokens: number;
  afterTokens: number;
  elapsedMs: number;
  failed: boolean;
  failureReason?: string;
};

/** The transport-agnostic surface the TUI uses. */
export interface CompactRpcClient {
  readonly mode: "remote" | "local";
  run(params?: CompactRunParams): Promise<CompactRunResult>;
  status(): Promise<CompactStatusResult>;
  reset(): Promise<CompactResetResult>;
  history(params?: CompactHistoryParams): Promise<ReadonlyArray<CompactEvent>>;
  /** Subscribe to live events. Returns a dispose function. */
  onEvent(handler: (event: CompactEvent) => void): () => void;
}

/** A JSON-RPC-flavoured error wrapper. The TUI surfaces these
 *  in the status bar ("rpc error -32603: ...") when the daemon
 *  is unreachable or rejects the call. */
export class CompactRpcError extends Error {
  readonly code: number;
  readonly data: unknown;
  constructor(code: number, message: string, data?: unknown) {
    super(`compact-rpc error ${code}: ${message}`);
    this.code = code;
    this.data = data;
  }
}

function asNumber(v: unknown, fallback = 0): number {
  return typeof v === "number" ? v : fallback;
}

function asBool(v: unknown, fallback = false): boolean {
  return typeof v === "boolean" ? v : fallback;
}

function asString(v: unknown, fallback = ""): string {
  return typeof v === "string" ? v : fallback;
}

function normalizeEvent(v: unknown): CompactEvent | null {
  if (typeof v !== "object" || v === null) return null;
  const r = v as Record<string, unknown>;
  const layer = asNumber(r["layer"], 1);
  const out: CompactEvent = {
    ts: asNumber(r["ts"], 0),
    layer: (layer === 1 || layer === 2 || layer === 3) ? (layer as 1 | 2 | 3) : 1,
    beforeTokens: asNumber(r["beforeTokens"], 0),
    afterTokens: asNumber(r["afterTokens"], 0),
    elapsedMs: asNumber(r["elapsedMs"], 0),
    failed: asBool(r["failed"], false),
  };
  const fr = r["failureReason"];
  if (typeof fr === "string") out.failureReason = fr;
  return out;
}

function normalizeRun(v: unknown): CompactRunResult {
  if (typeof v !== "object" || v === null) {
    return { layer: 1, beforeTokens: 0, afterTokens: 0, ms: 0, failed: true, failureReason: "malformed" };
  }
  const r = v as Record<string, unknown>;
  const layer = asNumber(r["layer"], 1);
  const out: CompactRunResult = {
    layer: (layer === 1 || layer === 2 || layer === 3) ? (layer as 1 | 2 | 3) : 1,
    beforeTokens: asNumber(r["beforeTokens"], 0),
    afterTokens: asNumber(r["afterTokens"], 0),
    ms: asNumber(r["ms"], 0),
    failed: asBool(r["failed"], false),
  };
  const fr = r["failureReason"];
  if (typeof fr === "string") out.failureReason = fr;
  return out;
}

function normalizeStatus(v: unknown): CompactStatusResult {
  if (typeof v !== "object" || v === null) {
    return { autoCompactDisabled: false, consecutiveCompactFailures: 0, lastFailureTs: null, lastEvent: null };
  }
  const r = v as Record<string, unknown>;
  return {
    autoCompactDisabled: asBool(r["autoCompactDisabled"], false),
    consecutiveCompactFailures: asNumber(r["consecutiveCompactFailures"], 0),
    lastFailureTs: typeof r["lastFailureTs"] === "number" ? r["lastFailureTs"] : null,
    lastEvent: normalizeEvent(r["lastEvent"]),
  };
}

function normalizeReset(v: unknown): CompactResetResult {
  if (typeof v !== "object" || v === null) return { ok: true, clearedFailures: 0 };
  const r = v as Record<string, unknown>;
  return {
    ok: true,
    clearedFailures: asNumber(r["clearedFailures"], 0),
  };
}

function normalizeHistory(v: unknown): ReadonlyArray<CompactEvent> {
  if (!Array.isArray(v)) return [];
  const out: CompactEvent[] = [];
  for (const e of v) {
    const ne = normalizeEvent(e);
    if (ne) out.push(ne);
  }
  return out;
}

/**
 * Proxy implementation that talks to the daemon via the
 * generic `JsonRpcClient`. The TUI's `AppState` constructs one
 * of these on every daemon connect and discards it on
 * disconnect.
 */
export class RemoteCompactRpc implements CompactRpcClient {
  readonly mode = "remote" as const;
  private readonly client: JsonRpcClient;
  private readonly handlers = new Set<(event: CompactEvent) => void>();
  private attached = false;

  constructor(client: JsonRpcClient) {
    this.client = client;
  }

  async run(params: CompactRunParams = {}): Promise<CompactRunResult> {
    const v = await this.client.request<unknown>("compact/run", params as never);
    return normalizeRun(v);
  }

  async status(): Promise<CompactStatusResult> {
    const v = await this.client.request<unknown>("compact/status", undefined);
    return normalizeStatus(v);
  }

  async reset(): Promise<CompactResetResult> {
    const v = await this.client.request<unknown>("compact/reset", undefined);
    return normalizeReset(v);
  }

  async history(params: CompactHistoryParams = {}): Promise<ReadonlyArray<CompactEvent>> {
    const v = await this.client.request<unknown>("compact/history", params as never);
    return normalizeHistory(v);
  }

  /** Subscribe to `compact/event` notifications. The daemon
   *  pushes these on every recorded pass; the TUI's
   *  `ContextMeter` listens to update its lastCompactTs hint. */
  onEvent(handler: (event: CompactEvent) => void): () => void {
    this.handlers.add(handler);
    if (!this.attached) {
      this.attached = true;
      this.client.setNotificationHandler((method, params) => {
        if (method === "compact/event") {
          const ev = normalizeEvent(params);
          if (ev) {
            for (const h of [...this.handlers]) {
              try { h(ev); } catch { /* ignore */ }
            }
          }
        }
      });
    }
    return () => { this.handlers.delete(handler); };
  }
}

/**
 * In-process implementation. Used by tests, the headless
 * `--print` path, and any consumer that hasn't (yet) started
 * a daemon. The TUI's story is:
 *
 *   if (daemon started && compact rpc present) → RemoteCompactRpc
 *   else                                       → LocalCompactRpc
 *
 * The class accepts an opaque `invoke` function so the caller
 * owns the lifecycle of the underlying `aethercode-compact`
 * `CompactRpc`. Tests pass a small adapter; production passes
 * one built from the daemon's transport.
 */
export class LocalCompactRpc implements CompactRpcClient {
  readonly mode = "local" as const;
  private readonly invoke: LocalInvoke;
  private readonly handlers = new Set<(event: CompactEvent) => void>();

  constructor(invoke: LocalInvoke) {
    this.invoke = invoke;
  }

  async run(params: CompactRunParams = {}): Promise<CompactRunResult> {
    return this.invoke.run(params);
  }

  async status(): Promise<CompactStatusResult> {
    return this.invoke.status();
  }

  async reset(): Promise<CompactResetResult> {
    return this.invoke.reset();
  }

  async history(params: CompactHistoryParams = {}): Promise<ReadonlyArray<CompactEvent>> {
    return this.invoke.history(params);
  }

  onEvent(handler: (event: CompactEvent) => void): () => void {
    this.handlers.add(handler);
    this.invoke.onEvent((event) => {
      for (const h of [...this.handlers]) {
        try { h(event); } catch { /* ignore */ }
      }
    });
    return () => { this.handlers.delete(handler); };
  }
}

/** The minimal in-process adapter contract. A `CompactRpc`
 *  from `aethercode-compact` satisfies this once you map its
 *  `handle(method, params)` return into the typed shapes. */
export interface LocalInvoke {
  run(params?: CompactRunParams): Promise<CompactRunResult>;
  status(): Promise<CompactStatusResult>;
  reset(): Promise<CompactResetResult>;
  history(params?: CompactHistoryParams): Promise<ReadonlyArray<CompactEvent>>;
  /** Forward every recorded event to the listener. */
  onEvent(handler: (event: CompactEvent) => void): void;
}

/**
 * Build a `LocalInvoke` from a `CompactRpc`-shaped object. The
 * caller (e.g. the TUI's `--print` boot path) constructs the
 * `CompactRpc`, hands it to this factory, and gets a typed
 * adapter back.
 *
 * The adapter is intentionally narrow: it does NOT take a
 * direct dependency on `aethercode-compact` so the TUI bundle
 * stays slim. The factory accepts a structural type that
 * matches `aethercode-compact/rpc.CompactRpc` without forcing
 * the import.
 */
export function buildLocalInvoke(impl: CompactRpcShim): LocalInvoke {
  return {
    run: (params) => Promise.resolve(impl.run(params)).then(extractRun),
    status: () => Promise.resolve(impl.status()).then(extractStatus),
    reset: () => Promise.resolve(impl.reset()).then(extractReset),
    history: (params) => Promise.resolve(impl.history(params)).then(extractHistory),
    onEvent: (handler) => {
      impl.onEvent((event) => handler(event as CompactEvent));
      return () => { /* no-op; the underlying impl owns the listener */ };
    },
  };
}

/** Structural type for a `CompactRpc` from `aethercode-compact`.
 *  We declare it here (rather than importing the class) so this
 *  module stays free of cross-package coupling. */
export interface CompactRpcShim {
  run(params?: CompactRunParams): Promise<{ result?: unknown; error?: unknown }>;
  status(): { result?: unknown; error?: unknown };
  reset(): { result?: unknown; error?: unknown };
  history(params?: CompactHistoryParams): { result?: unknown; error?: unknown };
  onEvent(handler: (event: { ts: number; layer: number; beforeTokens: number; afterTokens: number; elapsedMs: number; failed: boolean; failureReason?: string }) => void): void;
}

function unwrap<T>(v: { result?: unknown; error?: unknown }): T {
  if (v && typeof v === "object" && "error" in v && v.error) {
    const e = v.error as { code?: number; message?: string; data?: unknown };
    throw new CompactRpcError(e.code ?? -32603, e.message ?? "unknown", e.data);
  }
  return (v && typeof v === "object" && "result" in v ? v.result : v) as T;
}

const extractRun = (v: { result?: unknown; error?: unknown }): CompactRunResult => unwrap<CompactRunResult>(v);
const extractStatus = (v: { result?: unknown; error?: unknown }): CompactStatusResult => unwrap<CompactStatusResult>(v);
const extractReset = (v: { result?: unknown; error?: unknown }): CompactResetResult => unwrap<CompactResetResult>(v);
const extractHistory = (v: { result?: unknown; error?: unknown }): ReadonlyArray<CompactEvent> => unwrap<ReadonlyArray<CompactEvent>>(v);

/** Re-export the JSON-RPC error class so consumers can catch
 *  both the raw `RpcCallError` and our `CompactRpcError` with a
 *  single `instanceof` check (when desired). */
export { RpcCallError };

/** A tiny stand-in used by the TUI's headless boot path. */
export class NullCompactRpc implements CompactRpcClient {
  readonly mode = "local" as const;
  async run(): Promise<CompactRunResult> {
    return { layer: 1, beforeTokens: 0, afterTokens: 0, ms: 0, failed: true, failureReason: "no daemon" };
  }
  async status(): Promise<CompactStatusResult> {
    return { autoCompactDisabled: false, consecutiveCompactFailures: 0, lastFailureTs: null, lastEvent: null };
  }
  async reset(): Promise<CompactResetResult> {
    return { ok: true, clearedFailures: 0 };
  }
  async history(): Promise<ReadonlyArray<CompactEvent>> {
    return [];
  }
  onEvent(): () => void {
    return () => undefined;
  }
}
