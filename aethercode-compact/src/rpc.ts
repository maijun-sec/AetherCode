/**
 * Compact RPC surface (T-190 → T-194).
 *
 * Per `design.md §2.9` and `spec.md §2.7`:
 *
 *   "compact/run":    { from?, upTo?, force? } → { layer, beforeTokens, afterTokens, ms }
 *   "compact/status": void                     → { autoCompactDisabled, lastEvent }
 *   "compact/reset":  void                     → { ok: true }
 *   "compact/history":{ limit? }               → CompactEvent[]
 *
 * This module is intentionally transport-agnostic. The daemon
 * (Java `aethercode-protocol` or a local JSON-RPC server) calls
 * `handle(method, params)`; the `JsonRpcClient` in the TUI calls
 * the matching method over stdio. Both sides share the same types
 * from this file.
 *
 * The implementation is in-process: it composes the
 * `CompactPipeline` + `CircuitBreaker` from earlier rounds and
 * keeps an in-memory ring buffer of `CompactEvent` records that
 * the TUI's `ContextMeter` samples every 2 seconds (T-180/T-183).
 */

/* eslint-disable @typescript-eslint/no-explicit-any */

import { CircuitBreaker, type CircuitBreakerFailureReason, type CircuitBreakerStatus } from "./circuit-breaker.js";
import { CompactPipeline, type CompactOptions } from "./pipeline.js";
import type { HistoryStore } from "./history-store.js";
import type { CompactInput, CompactResult } from "./types.js";

/** A compact event: every time a compact pass runs we record one.
 *  The TUI's `ContextMeter` samples these to detect > 30 % drops. */
export type CompactEvent = {
  /** Wall-clock epoch ms when the pass finished. */
  ts: number;
  /** Layer that ran (1, 2, or 3). 2 is reserved for session-memory
   *  (Phase 4) and is never produced by this module today. */
  layer: 1 | 2 | 3;
  /** Token count going into the pass. */
  beforeTokens: number;
  /** Token count coming out. */
  afterTokens: number;
  /** Wall-clock duration of the pass in ms. */
  elapsedMs: number;
  /** True when the pass failed (LLM error, validation, timeout). */
  failed: boolean;
  /** When `failed === true`, a short reason code. */
  failureReason?: string;
};

/** Default ring-buffer size for the event log (T-193). */
export const DEFAULT_HISTORY_LIMIT = 200;

/** Options accepted by `CompactRpc`. */
export type CompactRpcOptions = {
  /** Pipeline used to run `compact/run` requests. */
  pipeline: CompactPipeline;
  /** Circuit breaker used by `compact/status` and `compact/reset`. */
  breaker: CircuitBreaker;
  /** Session id used for breaker lookups. */
  sessionId: string;
  /** Max number of events to keep in the ring buffer. */
  historyLimit?: number;
  /** Override clock (for tests). */
  now?: () => number;
  /** Optional callback fired after every recorded event. The
   *  TUI's `ContextMeter` subscribes to this for live updates. */
  onEvent?: (event: CompactEvent) => void;
  /** Optional persistent history store (T-193). When provided,
   *  every recorded event is forwarded to `store.append()` so it
   *  survives process restarts. The in-memory ring buffer is
   *  kept in sync; readers that need durability call
   *  `compact/history` and the implementation will consult the
   *  persistent store when the in-memory buffer is empty. */
  store?: HistoryStore;
};

/** `compact/run` request parameters. */
export type CompactRunParams = {
  /** Partial-compact anchor: keep prefix, compact after this id. */
  from?: string;
  /** Partial-compact anchor: compact everything before this id. */
  upTo?: string;
  /** Force Layer 3 to run even when below the trigger threshold. */
  force?: boolean;
};

/** `compact/run` response. */
export type CompactRunResult = {
  layer: 1 | 2 | 3;
  beforeTokens: number;
  afterTokens: number;
  ms: number;
  /** When the call failed, the reason (status code is still 200,
   *  the call itself succeeded — the *compact* is what failed). */
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
  /** Echo the cleared failure count for the caller's log. */
  clearedFailures: number;
};

/** `compact/history` request parameters. */
export type CompactHistoryParams = {
  /** How many of the most recent events to return. Default: all. */
  limit?: number;
};

/** Result of one RPC dispatch. Errors are returned as `{ error }`
 *  so the transport can convert them into JSON-RPC error objects
 *  without losing the structured payload. */
export type RpcDispatchResult<T> = { result: T } | { error: { code: number; message: string; data?: unknown } };

/** Standard error codes used by the compact RPC. */
export const RPC_ERR_UNKNOWN_METHOD = -32601;
export const RPC_ERR_INVALID_PARAMS = -32602;
export const RPC_ERR_INTERNAL = -32603;

/** A minimal `CompactInput` factory. The daemon already has a fully
 *  populated input; this helper is here so tests can spin up a
 *  `CompactRpc` without dragging in a real history. The `build`
 *  callback lets the daemon assemble the input from its own
 *  state when `compact/run` is called. */
export type BuildInputFn = () => CompactInput | Promise<CompactInput>;

/** The full constructor options. `buildInput` is required for
 *  `compact/run` — the RPC layer is intentionally transport-agnostic
 *  and does not own a history. */
export type CompactRpcFullOptions = CompactRpcOptions & {
  buildInput: BuildInputFn;
  /** LLM client for `compact/run` when Layer 3 is required. */
  llm?: CompactOptions["llm"];
};

export class CompactRpc {
  readonly pipeline: CompactPipeline;
  readonly breaker: CircuitBreaker;
  readonly sessionId: string;
  readonly historyLimit: number;
  readonly now: () => number;

  private readonly buildInput: BuildInputFn;
  private readonly llm: CompactOptions["llm"] | undefined;
  private readonly onEvent: ((event: CompactEvent) => void) | undefined;
  private readonly store: HistoryStore | undefined;
  private readonly events: CompactEvent[] = [];

  constructor(options: CompactRpcFullOptions) {
    this.pipeline = options.pipeline;
    this.breaker = options.breaker;
    this.sessionId = options.sessionId;
    this.historyLimit = Math.max(1, Math.floor(options.historyLimit ?? DEFAULT_HISTORY_LIMIT));
    this.now = options.now ?? Date.now;
    this.buildInput = options.buildInput;
    this.llm = options.llm;
    this.onEvent = options.onEvent;
    this.store = options.store;
  }

  /**
   * Dispatch a JSON-RPC-style request. The `method` is the
   * compact method name (e.g. `"compact/run"`); the `params` is
   * whatever the caller sent. Returns a structured result.
   *
   * The transport layer (Java daemon or in-process) is expected
   * to map JSON-RPC errors from this:
   *   - `{ error: { code: -32601, ... } }` → method not found
   *   - `{ error: { code: -32602, ... } }` → bad params
   *   - `{ error: { code: -32603, ... } }` → internal failure
   */
  async handle(method: string, params: unknown): Promise<RpcDispatchResult<unknown>> {
    switch (method) {
      case "compact/run":
        return await this.handleRun(params);
      case "compact/status":
        return this.handleStatus();
      case "compact/reset":
        return this.handleReset();
      case "compact/history":
        return this.handleHistory(params);
      default:
        return {
          error: {
            code: RPC_ERR_UNKNOWN_METHOD,
            message: `unknown method: ${method}`,
          },
        };
    }
  }

  /** Typed helper for `compact/run`. */
  run(params: CompactRunParams = {}): Promise<RpcDispatchResult<CompactRunResult>> {
    return this.handleRun(params);
  }

  /** Typed helper for `compact/status`. */
  status(): RpcDispatchResult<CompactStatusResult> {
    return this.handleStatus();
  }

  /** Typed helper for `compact/reset`. */
  reset(): RpcDispatchResult<CompactResetResult> {
    return this.handleReset();
  }

  /** Typed helper for `compact/history`. */
  history(params: CompactHistoryParams = {}): RpcDispatchResult<ReadonlyArray<CompactEvent>> {
    return this.handleHistory(params);
  }

  /** The list of method names this RPC server handles.
   *  Used by the daemon to register the routes and by tests
   *  to assert the surface is complete (T-194). */
  static methods(): ReadonlyArray<string> {
    return ["compact/run", "compact/status", "compact/reset", "compact/history"];
  }

  /**
   * Synchronous accessor for the recorded events. Used by the
   * daemon to expose them to the TUI's `ContextMeter` over a
   * notification channel (`compact/event`) if it prefers
   * push-based delivery instead of polling.
   */
  eventLog(): ReadonlyArray<CompactEvent> {
    return this.events;
  }

  /** Test helper: append a synthetic event. Production code should
   *  use the `handle("compact/run", ...)` path; tests use this to
   *  seed the history without spinning up an LLM. */
  pushTestEvent(event: CompactEvent): void {
    this.recordEvent(event);
  }

  // ---------- handlers -----------------------------------------

  private async handleRun(params: unknown): Promise<RpcDispatchResult<CompactRunResult>> {
    const parsed = parseRunParams(params);
    if (!parsed.ok) {
      return {
        error: {
          code: RPC_ERR_INVALID_PARAMS,
          message: "invalid compact/run params",
          data: { issues: parsed.issues },
        },
      };
    }

    if (this.breaker.isTripped(this.sessionId)) {
      // Bypass per design.md §2.6. We still return a structured
      // result with `failed: true` and a clear reason.
      const result: CompactRunResult = {
        layer: 1,
        beforeTokens: 0,
        afterTokens: 0,
        ms: 0,
        failed: true,
        failureReason: "auto_compact_disabled",
      };
      // Don't pollute the history with no-op runs while the
      // breaker is tripped. Status still surfaces the
      // disabled flag.
      return { result };
    }

    let input: CompactInput;
    try {
      input = await this.buildInput();
    } catch (e) {
      // buildInput failure: treat as a compact failure event so the
      // breaker is notified and the TUI's ContextMeter shows the drop.
      const reason = `buildInput: ${(e as Error).message}`;
      this.breaker.recordFailure(this.sessionId, classifyError(reason));
      const event: CompactEvent = {
        ts: this.now(),
        layer: 1,
        beforeTokens: 0,
        afterTokens: 0,
        elapsedMs: 0,
        failed: true,
        failureReason: reason,
      };
      this.recordEvent(event);
      return {
        result: {
          layer: event.layer,
          beforeTokens: 0,
          afterTokens: 0,
          ms: 0,
          failed: true,
          failureReason: reason,
        },
      };
    }

    const startedAt = this.now();
    let compact: CompactResult;
    try {
      compact = await this.pipeline.compact(input, {
        llm: this.llm,
        forceLayer3: parsed.value.force,
        fromId: parsed.value.from,
        upToId: parsed.value.upTo,
        now: this.now,
      });
    } catch (e) {
      const reason = (e as Error).message || "unknown";
      this.breaker.recordFailure(this.sessionId, classifyError(reason));
      const event: CompactEvent = {
        ts: this.now(),
        layer: 1,
        beforeTokens: 0,
        afterTokens: 0,
        elapsedMs: this.now() - startedAt,
        failed: true,
        failureReason: reason,
      };
      this.recordEvent(event);
      return {
        result: {
          layer: event.layer,
          beforeTokens: 0,
          afterTokens: 0,
          ms: event.elapsedMs,
          failed: true,
          failureReason: reason,
        },
      };
    }

    const event: CompactEvent = {
      ts: this.now(),
      layer: compact.layer,
      beforeTokens: compact.beforeTokens,
      afterTokens: compact.afterTokens,
      elapsedMs: this.now() - startedAt,
      failed: false,
    };
    this.breaker.recordSuccess(this.sessionId);
    this.recordEvent(event);

    return {
      result: {
        layer: compact.layer,
        beforeTokens: compact.beforeTokens,
        afterTokens: compact.afterTokens,
        ms: event.elapsedMs,
        failed: false,
      },
    };
  }

  private handleStatus(): RpcDispatchResult<CompactStatusResult> {
    const breaker: CircuitBreakerStatus = this.breaker.status(this.sessionId);
    const lastEvent = this.events.length > 0 ? (this.events[this.events.length - 1] ?? null) : null;
    return {
      result: {
        autoCompactDisabled: breaker.autoCompactDisabled,
        consecutiveCompactFailures: breaker.consecutiveCompactFailures,
        lastFailureTs: breaker.lastFailureTs,
        lastEvent,
      },
    };
  }

  private handleReset(): RpcDispatchResult<CompactResetResult> {
    const before = this.breaker.status(this.sessionId);
    this.breaker.reset(this.sessionId);
    return {
      result: {
        ok: true,
        clearedFailures: before.consecutiveCompactFailures,
      },
    };
  }

  private handleHistory(params: unknown): RpcDispatchResult<ReadonlyArray<CompactEvent>> {
    const parsed = parseHistoryParams(params);
    if (!parsed.ok) {
      return {
        error: {
          code: RPC_ERR_INVALID_PARAMS,
          message: "invalid compact/history params",
          data: { issues: parsed.issues },
        },
      };
    }
    const limit = parsed.value.limit ?? this.events.length;
    // When a persistent store is wired, consult it first (it
    // survives restarts). Fall back to the in-memory ring buffer
    // when the store is empty or unavailable.
    if (this.store) {
      try {
        const fromStore = this.store.recent({ sessionId: this.sessionId, limit });
        if (fromStore.length > 0) return { result: fromStore };
      } catch { /* fall through to ring buffer */ }
    }
    const slice = this.events.slice(Math.max(0, this.events.length - Math.max(0, limit)));
    return { result: slice };
  }

  // ---------- internals -----------------------------------------

  private recordEvent(event: CompactEvent): void {
    this.events.push(event);
    if (this.events.length > this.historyLimit) {
      this.events.splice(0, this.events.length - this.historyLimit);
    }
    if (this.store) {
      try {
        this.store.append({ ...event, sessionId: this.sessionId } as CompactEvent & { sessionId: string });
      } catch { /* swallow store errors so RPC stays live */ }
    }
    if (this.onEvent) {
      try { this.onEvent(event); } catch { /* swallow listener errors */ }
    }
  }
}

// ---------- param parsing helpers -------------------------------

type ParseOk<T> = { ok: true; value: T };
type ParseErr = { ok: false; issues: string[] };

function parseRunParams(params: unknown): ParseOk<CompactRunParams> | ParseErr {
  if (params === undefined || params === null) return { ok: true, value: {} };
  if (typeof params !== "object") return { ok: false, issues: ["params must be an object"] };
  const p = params as Record<string, unknown>;
  const issues: string[] = [];
  const from = p["from"];
  const upTo = p["upTo"];
  const force = p["force"];
  if (from !== undefined && typeof from !== "string") issues.push("from must be a string");
  if (upTo !== undefined && typeof upTo !== "string") issues.push("upTo must be a string");
  if (force !== undefined && typeof force !== "boolean") issues.push("force must be a boolean");
  if (typeof from === "string" && typeof upTo === "string") {
    issues.push("from and upTo are mutually exclusive");
  }
  if (issues.length > 0) return { ok: false, issues };
  const out: CompactRunParams = {};
  if (typeof from === "string") out.from = from;
  if (typeof upTo === "string") out.upTo = upTo;
  if (typeof force === "boolean") out.force = force;
  return { ok: true, value: out };
}

function parseHistoryParams(params: unknown): ParseOk<CompactHistoryParams> | ParseErr {
  if (params === undefined || params === null) return { ok: true, value: {} };
  if (typeof params !== "object") return { ok: false, issues: ["params must be an object"] };
  const p = params as Record<string, unknown>;
  const limit = p["limit"];
  if (limit !== undefined) {
    if (typeof limit !== "number" || !Number.isInteger(limit) || limit < 0) {
      return { ok: false, issues: ["limit must be a non-negative integer"] };
    }
    return { ok: true, value: { limit } };
  }
  return { ok: true, value: {} };
}

/** Map a thrown error's `.message` to a `CircuitBreakerFailureReason`. */
function classifyError(message: string): CircuitBreakerFailureReason {
  const m = message.toLowerCase();
  if (m.includes("timeout")) return "timeout";
  if (m.includes("validation")) return "validation_error";
  if (m.includes("backstop") || m.includes("retry")) return "backstop_retry_exhausted";
  if (m.includes("llm") || m.includes("model") || m.includes("network")) return "llm_error";
  return "unknown";
}
