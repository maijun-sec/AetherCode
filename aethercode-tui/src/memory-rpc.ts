/**
 * T-096: typed `memory/*` RPC surface for the AetherCode TUI.
 *
 * Mirrors `aethercode-memory/src/rpc.ts` and `design.md §1.6`:
 *
 *   "memory/get":                  { scope, sessionId? }          → MemoryReadResult
 *   "memory/appendProjectChange":  { description }                → { ok, id, ts, compressed }
 *   "memory/appendSessionFact":    { sessionId, key, value, ... } → { ok, id }
 *   "memory/compact":              { force? }                     → MemoryCompactResult
 *   "memory/switchProject":        { cwd, placeholderTitle? }     → { ok, projectId }
 *   "memory/list":                 { scope, sessionId? }          → { entries, totalTokens }
 *
 * This is a thin wrapper around the existing `JsonRpcClient`
 * (prior round) that adds typed methods and a local fallback so the
 * TUI can be exercised without a live daemon. The MemoryPanel
 * (prior round) calls into this module — it never talks to the daemon
 * directly.
 *
 * Two modes:
 *   1. Remote (daemon present) — every method proxies through
 *      the supplied `JsonRpcClient`. The daemon implements the
 *      handlers in `aethercode-memory/src/rpc.ts` (or its Java
 *      mirror).
 *   2. Local (no daemon)        — a `LocalMemoryRpc` instance
 *      owns a `MemoryStore` from `aethercode-memory` and serves
 *      the same six methods in-process. The TUI uses this in
 *      tests and during the headless `--print` path.
 *
 * Both modes share the same `MemoryRpcClient` interface so the
 * TUI doesn't have to branch on the transport.
 *
 * Why a separate file (and not methods on `JsonRpcClient`)?
 * Following the same pattern as `compact-rpc.ts` (T-194):
 *   - keeps `jsonrpc.ts` transport-only
 *   - the typed surface can evolve independently of the wire
 *     format
 *   - tests can swap a `MemoryRpcShim` without spawning a daemon
 */

import { JsonRpcClient, RpcCallError } from "./jsonrpc.js";

/* ----------------------------- param/result types ---------------------- */

/** `memory/get` parameters. */
export type MemoryGetParams = {
  scope: "global" | "project" | "session" | string;
  sessionId?: string;
};

/** `memory/appendProjectChange` parameters. */
export type MemoryAppendProjectChangeParams = {
  description: string;
};

/** `memory/appendSessionFact` parameters. */
export type MemoryAppendSessionFactParams = {
  sessionId: string;
  key: string;
  value: string;
  source?: "user" | "llm" | "system" | "tool" | "imported";
  tags?: ReadonlyArray<string>;
};

/** `memory/compact` parameters. */
export type MemoryCompactParams = {
  force?: boolean;
};

/** `memory/compact` result. */
export type MemoryCompactResult = {
  ok: true;
  beforeTokens: number;
  afterTokens: number;
  changesCompressed: number;
  ms: number;
  skipped: boolean;
  resumed: boolean;
};

/** `memory/switchProject` parameters. */
export type MemorySwitchProjectParams = {
  cwd: string;
  placeholderTitle?: string;
  placeholderDescription?: string;
};

/** `memory/switchProject` result. */
export type MemorySwitchProjectResult = {
  ok: true;
  projectId: string;
};

/** `memory/list` parameters. */
export type MemoryListParams = {
  scope: "global" | "project" | "session" | string;
  sessionId?: string;
};

/** Flat entry shape that survives a JSON-RPC round trip. The
 *  aethercode-memory module's `MemoryEntry` is a discriminated
 *  union; we keep the wire shape identical and let the consumer
 *  narrow on `kind`. */
export type MemoryEntryWire = {
  kind: "fact" | "rule" | "change" | "breadcrumb";
  id: string;
  ts: number;
  scope: "global" | "project" | "session";
  source: "user" | "llm" | "system" | "tool" | "imported";
  tags: ReadonlyArray<string>;
  key?: string;
  value?: string;
  text?: string;
  description?: string;
  compressed?: boolean;
  message?: string;
};

/** `memory/get` / `memory/list` result shape. */
export type MemoryReadResultWire = {
  source: "cache" | "sqlite" | "file";
  entries: ReadonlyArray<MemoryEntryWire>;
  totalTokens: number;
  truncated: boolean;
};

/** `memory/list` result shape (lighter — no source). */
export type MemoryListResultWire = {
  entries: ReadonlyArray<MemoryEntryWire>;
  totalTokens: number;
};

/** `memory/appendProjectChange` result shape. */
export type MemoryAppendProjectChangeResult = {
  ok: true;
  id: string;
  ts: number;
  compressed: boolean;
};

/** `memory/appendSessionFact` result shape. */
export type MemoryAppendSessionFactResult = {
  ok: true;
  id: string;
};

/* ----------------------------- transport-agnostic client -------------- */

/** The transport-agnostic surface the TUI uses. Mirrors the
 *  six methods in `aethercode-memory/src/rpc.ts`. */
export interface MemoryRpcClient {
  readonly mode: "remote" | "local" | "null";
  /** T-070 / design §1.6 — read a memory layer. */
  get(params: MemoryGetParams): Promise<MemoryReadResultWire>;
  /** T-071 / design §1.6 — append a project change log entry. */
  appendProjectChange(
    params: MemoryAppendProjectChangeParams,
  ): Promise<MemoryAppendProjectChangeResult>;
  /** T-072 / design §1.6 — append a session-scoped fact. */
  appendSessionFact(
    params: MemoryAppendSessionFactParams,
  ): Promise<MemoryAppendSessionFactResult>;
  /** T-073 / design §1.6 — force a compression pass. */
  compact(params?: MemoryCompactParams): Promise<MemoryCompactResult>;
  /** T-074 / design §1.6 — switch the active project. */
  switchProject(
    params: MemorySwitchProjectParams,
  ): Promise<MemorySwitchProjectResult>;
  /** T-075 / design §1.6 — list entries of a layer. */
  list(params: MemoryListParams): Promise<MemoryListResultWire>;
}

/** A JSON-RPC-flavoured error wrapper. Mirrors `CompactRpcError`
 *  for surface consistency. */
export class MemoryRpcError extends Error {
  readonly code: number;
  readonly data: unknown;
  constructor(code: number, message: string, data?: unknown) {
    super(`memory-rpc error ${code}: ${message}`);
    this.code = code;
    this.data = data;
  }
}

/* ----------------------------- normalisers ----------------------------- */

function asNumber(v: unknown, fallback = 0): number {
  return typeof v === "number" ? v : fallback;
}
function asBool(v: unknown, fallback = false): boolean {
  return typeof v === "boolean" ? v : fallback;
}
function asString(v: unknown, fallback = ""): string {
  return typeof v === "string" ? v : fallback;
}
function asStringArray(v: unknown): ReadonlyArray<string> {
  if (!Array.isArray(v)) return [];
  return v.filter((x): x is string => typeof x === "string");
}

function normalizeEntry(v: unknown): MemoryEntryWire | null {
  if (typeof v !== "object" || v === null) return null;
  const r = v as Record<string, unknown>;
  const kind = asString(r["kind"]);
  if (kind !== "fact" && kind !== "rule" && kind !== "change" && kind !== "breadcrumb") {
    return null;
  }
  const scope = asString(r["scope"]);
  const validScope = scope === "global" || scope === "project" || scope === "session"
    ? scope
    : "project";
  const source = asString(r["source"]);
  const validSource = (source === "user" || source === "llm" || source === "system" ||
    source === "tool" || source === "imported") ? source : "system";
  const out: MemoryEntryWire = {
    kind,
    id: asString(r["id"]),
    ts: asNumber(r["ts"]),
    scope: validScope,
    source: validSource,
    tags: asStringArray(r["tags"]),
  };
  if (typeof r["key"] === "string") out.key = r["key"];
  if (typeof r["value"] === "string") out.value = r["value"];
  if (typeof r["text"] === "string") out.text = r["text"];
  if (typeof r["description"] === "string") out.description = r["description"];
  if (typeof r["compressed"] === "boolean") out.compressed = r["compressed"];
  if (typeof r["message"] === "string") out.message = r["message"];
  return out;
}

function normalizeRead(v: unknown): MemoryReadResultWire {
  if (typeof v !== "object" || v === null) {
    return { source: "file", entries: [], totalTokens: 0, truncated: false };
  }
  const r = v as Record<string, unknown>;
  const source = asString(r["source"]);
  const validSource = source === "cache" || source === "sqlite" || source === "file"
    ? source
    : "file";
  const rawEntries = Array.isArray(r["entries"]) ? r["entries"] : [];
  const entries: MemoryEntryWire[] = [];
  for (const e of rawEntries) {
    const ne = normalizeEntry(e);
    if (ne) entries.push(ne);
  }
  return {
    source: validSource,
    entries,
    totalTokens: asNumber(r["totalTokens"]),
    truncated: asBool(r["truncated"]),
  };
}

function normalizeList(v: unknown): MemoryListResultWire {
  if (typeof v !== "object" || v === null) {
    return { entries: [], totalTokens: 0 };
  }
  const r = v as Record<string, unknown>;
  const rawEntries = Array.isArray(r["entries"]) ? r["entries"] : [];
  const entries: MemoryEntryWire[] = [];
  for (const e of rawEntries) {
    const ne = normalizeEntry(e);
    if (ne) entries.push(ne);
  }
  return { entries, totalTokens: asNumber(r["totalTokens"]) };
}

function normalizeCompact(v: unknown): MemoryCompactResult {
  if (typeof v !== "object" || v === null) {
    return {
      ok: true,
      beforeTokens: 0,
      afterTokens: 0,
      changesCompressed: 0,
      ms: 0,
      skipped: true,
      resumed: false,
    };
  }
  const r = v as Record<string, unknown>;
  return {
    ok: true,
    beforeTokens: asNumber(r["beforeTokens"]),
    afterTokens: asNumber(r["afterTokens"]),
    changesCompressed: asNumber(r["changesCompressed"]),
    ms: asNumber(r["ms"]),
    skipped: asBool(r["skipped"], true),
    resumed: asBool(r["resumed"]),
  };
}

function normalizeAppendChange(v: unknown): MemoryAppendProjectChangeResult {
  if (typeof v !== "object" || v === null) {
    return { ok: true, id: "", ts: 0, compressed: false };
  }
  const r = v as Record<string, unknown>;
  return {
    ok: true,
    id: asString(r["id"]),
    ts: asNumber(r["ts"]),
    compressed: asBool(r["compressed"]),
  };
}

function normalizeAppendFact(v: unknown): MemoryAppendSessionFactResult {
  if (typeof v !== "object" || v === null) {
    return { ok: true, id: "" };
  }
  const r = v as Record<string, unknown>;
  return { ok: true, id: asString(r["id"]) };
}

function normalizeSwitchProject(v: unknown): MemorySwitchProjectResult {
  if (typeof v !== "object" || v === null) {
    return { ok: true, projectId: "" };
  }
  const r = v as Record<string, unknown>;
  return { ok: true, projectId: asString(r["projectId"]) };
}

/* ----------------------------- Remote (daemon) ------------------------- */

/**
 * Proxy implementation that talks to the daemon via the
 * generic `JsonRpcClient`. The TUI's `AppState` constructs one
 * of these on every daemon connect and discards it on
 * disconnect. The wire method names match `aethercode-memory`'s
 * `rpc.ts` 1:1, so the daemon (Java or Node) can forward them
 * without translation.
 */
export class RemoteMemoryRpc implements MemoryRpcClient {
  readonly mode = "remote" as const;
  private readonly client: JsonRpcClient;

  constructor(client: JsonRpcClient) {
    this.client = client;
  }

  async get(params: MemoryGetParams): Promise<MemoryReadResultWire> {
    const v = await this.client.request<unknown>("memory/get", params as never);
    return normalizeRead(v);
  }

  async appendProjectChange(
    params: MemoryAppendProjectChangeParams,
  ): Promise<MemoryAppendProjectChangeResult> {
    const v = await this.client.request<unknown>(
      "memory/appendProjectChange",
      params as never,
    );
    return normalizeAppendChange(v);
  }

  async appendSessionFact(
    params: MemoryAppendSessionFactParams,
  ): Promise<MemoryAppendSessionFactResult> {
    const v = await this.client.request<unknown>(
      "memory/appendSessionFact",
      params as never,
    );
    return normalizeAppendFact(v);
  }

  async compact(params: MemoryCompactParams = {}): Promise<MemoryCompactResult> {
    const v = await this.client.request<unknown>("memory/compact", params as never);
    return normalizeCompact(v);
  }

  async switchProject(
    params: MemorySwitchProjectParams,
  ): Promise<MemorySwitchProjectResult> {
    const v = await this.client.request<unknown>(
      "memory/switchProject",
      params as never,
    );
    return normalizeSwitchProject(v);
  }

  async list(params: MemoryListParams): Promise<MemoryListResultWire> {
    const v = await this.client.request<unknown>("memory/list", params as never);
    return normalizeList(v);
  }
}

/* ----------------------------- Local (in-process) --------------------- */

/**
 * In-process implementation. Used by tests, the headless
 * `--print` path, and any consumer that hasn't (yet) started a
 * daemon. The class accepts an opaque `invoke` function so the
 * caller owns the lifecycle of the underlying `MemoryStore`
 * from `aethercode-memory`. Tests pass a small adapter;
 * production passes one built from the daemon's transport.
 */
export class LocalMemoryRpc implements MemoryRpcClient {
  readonly mode = "local" as const;
  private readonly invoke: LocalInvoke;

  constructor(invoke: LocalInvoke) {
    this.invoke = invoke;
  }

  async get(params: MemoryGetParams): Promise<MemoryReadResultWire> {
    return this.invoke.get(params);
  }

  async appendProjectChange(
    params: MemoryAppendProjectChangeParams,
  ): Promise<MemoryAppendProjectChangeResult> {
    return this.invoke.appendProjectChange(params);
  }

  async appendSessionFact(
    params: MemoryAppendSessionFactParams,
  ): Promise<MemoryAppendSessionFactResult> {
    return this.invoke.appendSessionFact(params);
  }

  async compact(params: MemoryCompactParams = {}): Promise<MemoryCompactResult> {
    return this.invoke.compact(params);
  }

  async switchProject(
    params: MemorySwitchProjectParams,
  ): Promise<MemorySwitchProjectResult> {
    return this.invoke.switchProject(params);
  }

  async list(params: MemoryListParams): Promise<MemoryListResultWire> {
    return this.invoke.list(params);
  }
}

/** The minimal in-process adapter contract. A `MemoryStore`
 *  from `aethercode-memory` satisfies this once you map the
 *  six handlers into the typed shapes. */
export interface LocalInvoke {
  get(params: MemoryGetParams): Promise<MemoryReadResultWire>;
  appendProjectChange(
    params: MemoryAppendProjectChangeParams,
  ): Promise<MemoryAppendProjectChangeResult>;
  appendSessionFact(
    params: MemoryAppendSessionFactParams,
  ): Promise<MemoryAppendSessionFactResult>;
  compact(params: MemoryCompactParams): Promise<MemoryCompactResult>;
  switchProject(
    params: MemorySwitchProjectParams,
  ): Promise<MemorySwitchProjectResult>;
  list(params: MemoryListParams): Promise<MemoryListResultWire>;
}

/**
 * Build a `LocalInvoke` from a `MemoryRpcShim`-shaped object.
 * The caller (e.g. the TUI's `--print` boot path) constructs a
 * `MemoryStore` from `aethercode-memory`, hands it to this
 * factory, and gets a typed adapter back.
 *
 * The adapter is intentionally narrow: it does NOT take a
 * direct dependency on `aethercode-memory`'s classes so the
 * TUI bundle stays slim. The factory accepts a structural type
 * that matches the module's `MemoryStore` API without forcing
 * the import.
 *
 * Each method:
 *   1. invokes the shim (sync or async),
 *   2. unwraps the `{ result?, error? }` envelope via
 *      `unwrapMemoryEnvelope`, throwing `MemoryRpcError` on
 *      the error branch,
 *   3. passes the unwrapped value through a normaliser so
 *      malformed payloads become sensible defaults.
 */
export function buildLocalInvoke(impl: MemoryRpcShim): LocalInvoke {
  return {
    get: async (params) => {
      const v = await impl.get(params);
      return normalizeRead(unwrapMemoryEnvelope(v));
    },
    appendProjectChange: async (params) => {
      const v = await impl.appendProjectChange(params);
      return normalizeAppendChange(unwrapMemoryEnvelope(v));
    },
    appendSessionFact: async (params) => {
      const v = await impl.appendSessionFact(params);
      return normalizeAppendFact(unwrapMemoryEnvelope(v));
    },
    compact: async (params) => {
      const v = await impl.compact(params ?? {});
      return normalizeCompact(unwrapMemoryEnvelope(v));
    },
    switchProject: async (params) => {
      const v = await impl.switchProject(params);
      return normalizeSwitchProject(unwrapMemoryEnvelope(v));
    },
    list: async (params) => {
      const v = await impl.list(params);
      return normalizeList(unwrapMemoryEnvelope(v));
    },
  };
}

/** Structural type for an in-process `MemoryStore` from
 *  `aethercode-memory`. The six methods map 1:1 to the RPC
 *  handlers in `aethercode-memory/src/rpc.ts`. */
export interface MemoryRpcShim {
  get(params: MemoryGetParams): Promise<{ result?: unknown; error?: unknown }> | { result?: unknown; error?: unknown };
  appendProjectChange(
    params: MemoryAppendProjectChangeParams,
  ): Promise<{ result?: unknown; error?: unknown }> | { result?: unknown; error?: unknown };
  appendSessionFact(
    params: MemoryAppendSessionFactParams,
  ): Promise<{ result?: unknown; error?: unknown }> | { result?: unknown; error?: unknown };
  compact(
    params: MemoryCompactParams,
  ): Promise<{ result?: unknown; error?: unknown }> | { result?: unknown; error?: unknown };
  switchProject(
    params: MemorySwitchProjectParams,
  ): Promise<{ result?: unknown; error?: unknown }> | { result?: unknown; error?: unknown };
  list(
    params: MemoryListParams,
  ): Promise<{ result?: unknown; error?: unknown }> | { result?: unknown; error?: unknown };
}

/** Helper for unwrapping a `{ result?, error? }` envelope
 *  into a plain result. Throws `MemoryRpcError` on the error
 *  branch. The TUI's test wiring uses this to keep handler
 *  stubs short. */
export function unwrapMemoryEnvelope<T>(v: { result?: unknown; error?: unknown }): T {
  if (v && typeof v === "object" && "error" in v && v.error) {
    const e = v.error as { code?: number; message?: string; data?: unknown };
    throw new MemoryRpcError(e.code ?? -32603, e.message ?? "unknown", e.data);
  }
  return (v && typeof v === "object" && "result" in v ? v.result : v) as T;
}

/* ----------------------------- Null fallback --------------------------- */

/**
 * A tiny stand-in used by the TUI's headless boot path. Every
 * method returns a benign default so the UI can render an
 * empty MemoryPanel without first connecting to a daemon.
 */
export class NullMemoryRpc implements MemoryRpcClient {
  readonly mode = "null" as const;

  async get(_params: MemoryGetParams): Promise<MemoryReadResultWire> {
    return { source: "file", entries: [], totalTokens: 0, truncated: false };
  }

  async appendProjectChange(
    _params: MemoryAppendProjectChangeParams,
  ): Promise<MemoryAppendProjectChangeResult> {
    return { ok: true, id: "", ts: 0, compressed: false };
  }

  async appendSessionFact(
    _params: MemoryAppendSessionFactParams,
  ): Promise<MemoryAppendSessionFactResult> {
    return { ok: true, id: "" };
  }

  async compact(_params: MemoryCompactParams = {}): Promise<MemoryCompactResult> {
    return {
      ok: true,
      beforeTokens: 0,
      afterTokens: 0,
      changesCompressed: 0,
      ms: 0,
      skipped: true,
      resumed: false,
    };
  }

  async switchProject(
    _params: MemorySwitchProjectParams,
  ): Promise<MemorySwitchProjectResult> {
    return { ok: true, projectId: "" };
  }

  async list(_params: MemoryListParams): Promise<MemoryListResultWire> {
    return { entries: [], totalTokens: 0 };
  }
}

/* ----------------------------- factory --------------------------------- */

/**
 * Pick the right implementation based on what the caller
 * hands us. The TUI uses this from its boot path:
 *
 *   const memoryRpc = pickMemoryRpc({
 *     client: daemonClient,         // may be null
 *     buildLocal: () => { ... },    // factory returning a LocalInvoke
 *   });
 *
 * Returns `NullMemoryRpc` when no client is available AND no
 * local factory was provided — the safe fallback for the
 * headless `--print` path.
 */
export function pickMemoryRpc(opts: {
  client: JsonRpcClient | null;
  buildLocal?: () => LocalInvoke | null;
}): MemoryRpcClient {
  if (opts.client) return new RemoteMemoryRpc(opts.client);
  if (opts.buildLocal) {
    const inv = opts.buildLocal();
    if (inv) return new LocalMemoryRpc(inv);
  }
  return new NullMemoryRpc();
}

/** Re-export the JSON-RPC error class so consumers can catch
 *  both the raw `RpcCallError` and our `MemoryRpcError` with a
 *  single `instanceof` check (when desired). */
export { RpcCallError };
