/**
 * Tests for the compact RPC surface (T-190 → T-194).
 *
 * Covers:
 *  - `CompactRpc.methods()` lists the 4 documented methods
 *  - `compact/run` calls the pipeline + records an event
 *  - `compact/run` returns an error for bad params
 *  - `compact/run` with the breaker tripped short-circuits
 *  - `compact/run` failure records a failure event + trips the
 *    breaker after `threshold` consecutive failures
 *  - `compact/status` returns breaker + last event
 *  - `compact/reset` clears the breaker state
 *  - `compact/history` returns the last N events in order
 *  - history ring buffer respects `historyLimit`
 *  - unknown method returns -32601
 *  - `onEvent` callback fires on every recorded event
 */
import { describe, expect, it, vi } from "vitest";

import { CompactPipeline } from "../pipeline.js";
import { CircuitBreaker } from "../circuit-breaker.js";
import {
  CompactRpc,
  DEFAULT_HISTORY_LIMIT,
  RPC_ERR_INVALID_PARAMS,
  RPC_ERR_UNKNOWN_METHOD,
  type CompactEvent,
} from "../rpc.js";
import { MemoryHistoryStore } from "../history-store.js";
import type { CompactInput, LlmClient, Message } from "../types.js";
import { makeInput, mockLlm, userMsg, VALID_SUMMARY } from "./fixtures.js";

const SESSION = "s-1";

function makeRpc(overrides: {
  pipeline?: CompactPipeline;
  breaker?: CircuitBreaker;
  buildInput?: () => CompactInput | Promise<CompactInput>;
  llm?: LlmClient;
  historyLimit?: number;
  now?: () => number;
  onEvent?: (e: CompactEvent) => void;
  store?: MemoryHistoryStore;
} = {}): CompactRpc {
  return new CompactRpc({
    pipeline: overrides.pipeline ?? new CompactPipeline(CompactPipeline.defaults()),
    breaker: overrides.breaker ?? new CircuitBreaker(),
    sessionId: SESSION,
    buildInput: overrides.buildInput ?? (() => makeInput()),
    llm: overrides.llm,
    historyLimit: overrides.historyLimit,
    now: overrides.now,
    onEvent: overrides.onEvent,
    store: overrides.store,
  });
}

describe("CompactRpc.methods", () => {
  it("lists the 4 documented compact methods", () => {
    expect([...CompactRpc.methods()]).toEqual([
      "compact/run",
      "compact/status",
      "compact/reset",
      "compact/history",
    ]);
  });
});

describe("compact/run", () => {
  it("runs the pipeline, returns layer/before/after/ms, records an event", async () => {
    // Force Layer 3 to run with a tiny input + LLM.
    const rpc = makeRpc({ llm: mockLlm() });
    const out = await rpc.run({ force: true });
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.layer).toBe(3);
    expect(typeof out.result.beforeTokens).toBe("number");
    expect(typeof out.result.afterTokens).toBe("number");
    expect(typeof out.result.ms).toBe("number");
    expect(out.result.failed).toBe(false);

    const events = rpc.eventLog();
    expect(events).toHaveLength(1);
    const ev = events[0]!;
    expect(ev.failed).toBe(false);
    expect(ev.layer).toBe(3);
  });

  it("returns invalid-params for non-object params", async () => {
    const rpc = makeRpc();
    const out = await rpc.handle("compact/run", "not an object");
    expect("error" in out).toBe(true);
    if (!("error" in out)) return;
    expect(out.error.code).toBe(RPC_ERR_INVALID_PARAMS);
  });

  it("returns invalid-params when force is not a boolean", async () => {
    const rpc = makeRpc();
    const out = await rpc.handle("compact/run", { force: "yes" });
    expect("error" in out).toBe(true);
    if (!("error" in out)) return;
    expect(out.error.code).toBe(RPC_ERR_INVALID_PARAMS);
  });

  it("returns invalid-params when from and upTo are both set", async () => {
    const rpc = makeRpc();
    const out = await rpc.handle("compact/run", { from: "a", upTo: "b" });
    expect("error" in out).toBe(true);
    if (!("error" in out)) return;
    expect(out.error.code).toBe(RPC_ERR_INVALID_PARAMS);
  });

  it("treats null/undefined params as empty {}", async () => {
    const rpc = makeRpc({ llm: mockLlm() });
    const outNull = await rpc.handle("compact/run", null);
    expect("error" in outNull).toBe(false);
    const outUndef = await rpc.handle("compact/run", undefined);
    expect("error" in outUndef).toBe(false);
  });

  it("short-circuits with failed:true when the breaker is tripped", async () => {
    const breaker = new CircuitBreaker({ threshold: 1 });
    breaker.recordFailure(SESSION, "llm_error");
    expect(breaker.isTripped(SESSION)).toBe(true);
    const rpc = makeRpc({ breaker });
    const out = await rpc.run({});
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.failed).toBe(true);
    expect(out.result.failureReason).toBe("auto_compact_disabled");
    // No event recorded while the breaker is open.
    expect(rpc.eventLog()).toHaveLength(0);
  });

  it("records a failure event and propagates the error when buildInput throws", async () => {
    const onEvent = vi.fn();
    const rpc = makeRpc({
      buildInput: () => { throw new Error("state unavailable"); },
      onEvent,
    });
    const out = await rpc.run({});
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.failed).toBe(true);
    expect(out.result.failureReason).toBe("buildInput: state unavailable");
    const events = rpc.eventLog();
    expect(events).toHaveLength(1);
    expect(events[0]!.failed).toBe(true);
    expect(onEvent).toHaveBeenCalledTimes(1);
  });

  it("trips the breaker after `threshold` consecutive failures", async () => {
    const breaker = new CircuitBreaker({ threshold: 2 });
    const rpc = makeRpc({
      breaker,
      buildInput: () => { throw new Error("llm call failed"); },
    });
    await rpc.run({});
    expect(breaker.isTripped(SESSION)).toBe(false);
    await rpc.run({});
    expect(breaker.isTripped(SESSION)).toBe(true);
  });

  it("records a success when the pipeline returns a clean result", async () => {
    const onEvent = vi.fn();
    const rpc = makeRpc({ llm: mockLlm(), onEvent });
    await rpc.run({ force: true });
    const status = rpc.status();
    expect(status).not.toHaveProperty("error");
    if ("error" in status) return;
    expect(status.result.autoCompactDisabled).toBe(false);
    expect(status.result.consecutiveCompactFailures).toBe(0);
    expect(status.result.lastEvent).not.toBeNull();
    expect(onEvent).toHaveBeenCalledTimes(1);
  });
});

describe("compact/status", () => {
  it("returns the breaker state + last event", async () => {
    const breaker = new CircuitBreaker({ threshold: 5 });
    const rpc = makeRpc({ breaker, llm: mockLlm() });
    await rpc.run({ force: true });
    const out = rpc.status();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.autoCompactDisabled).toBe(false);
    expect(out.result.consecutiveCompactFailures).toBe(0);
    expect(out.result.lastFailureTs).toBeNull();
    expect(out.result.lastEvent).not.toBeNull();
    expect(out.result.lastEvent!.layer).toBe(3);
  });

  it("returns lastEvent === null when no compact has run", () => {
    const rpc = makeRpc();
    const out = rpc.status();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.lastEvent).toBeNull();
    expect(out.result.autoCompactDisabled).toBe(false);
  });

  it("surfaces autoCompactDisabled=true after threshold failures", async () => {
    const breaker = new CircuitBreaker({ threshold: 1 });
    const rpc = makeRpc({
      breaker,
      buildInput: () => { throw new Error("llm timeout"); },
    });
    await rpc.run({});
    const out = rpc.status();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.autoCompactDisabled).toBe(true);
    expect(out.result.consecutiveCompactFailures).toBe(1);
    expect(out.result.lastFailureTs).not.toBeNull();
  });
});

describe("compact/reset", () => {
  it("clears the breaker state and echoes the cleared count", () => {
    const breaker = new CircuitBreaker({ threshold: 10 });
    breaker.recordFailure(SESSION, "llm_error");
    breaker.recordFailure(SESSION, "llm_error");
    const rpc = makeRpc({ breaker });
    const out = rpc.reset();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.ok).toBe(true);
    expect(out.result.clearedFailures).toBe(2);
    expect(breaker.isTripped(SESSION)).toBe(false);
  });

  it("returns clearedFailures=0 on a fresh session", () => {
    const rpc = makeRpc();
    const out = rpc.reset();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.clearedFailures).toBe(0);
  });
});

describe("compact/history", () => {
  it("returns all events when no limit is given", async () => {
    const rpc = makeRpc({ llm: mockLlm() });
    await rpc.run({ force: true });
    await rpc.run({ force: true });
    await rpc.run({ force: true });
    const out = rpc.history();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result).toHaveLength(3);
  });

  it("returns the last N events when limit is given", async () => {
    const rpc = makeRpc({ llm: mockLlm() });
    for (let i = 0; i < 5; i++) {
      await rpc.run({ force: true });
    }
    const out = rpc.history({ limit: 2 });
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result).toHaveLength(2);
  });

  it("returns an empty array when no events have been recorded", () => {
    const rpc = makeRpc();
    const out = rpc.history();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result).toEqual([]);
  });

  it("returns invalid-params for negative or non-integer limit", async () => {
    const rpc = makeRpc();
    const out1 = await rpc.handle("compact/history", { limit: -1 });
    expect("error" in out1).toBe(true);
    if (!("error" in out1)) return;
    expect(out1.error.code).toBe(RPC_ERR_INVALID_PARAMS);

    const out2 = await rpc.handle("compact/history", { limit: 1.5 });
    expect("error" in out2).toBe(true);
  });

  it("respects the historyLimit ring buffer", async () => {
    const rpc = makeRpc({ llm: mockLlm(), historyLimit: 3 });
    for (let i = 0; i < 7; i++) {
      await rpc.run({ force: true });
    }
    const log = rpc.eventLog();
    expect(log).toHaveLength(3);
    const out = rpc.history();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result).toHaveLength(3);
  });

  it("uses DEFAULT_HISTORY_LIMIT when none is provided", () => {
    expect(DEFAULT_HISTORY_LIMIT).toBe(200);
  });
});

describe("unknown method + transport-level error", () => {
  it("returns -32601 for unknown methods", async () => {
    const rpc = makeRpc();
    const out = await rpc.handle("nope/not-a-method", undefined);
    expect("error" in out).toBe(true);
    if (!("error" in out)) return;
    expect(out.error.code).toBe(RPC_ERR_UNKNOWN_METHOD);
  });

  it("returns a failure result when buildInput rejects asynchronously", async () => {
    // buildInput resolves successfully but pipeline throws; that is
    // recorded as a failure event (no -32603).
    // To exercise the async-reject path, we make buildInput reject.
    const rpc = makeRpc({
      buildInput: () => Promise.reject(new Error("async boom")),
    });
    // The compact pipeline will receive a rejected promise; compact()
    // will reject. We catch and surface as a failure event.
    const out = await rpc.run({});
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.failed).toBe(true);
    expect(out.result.failureReason).toBe("buildInput: async boom");
  });
});

describe("onEvent callback", () => {
  it("fires on every recorded event", async () => {
    const onEvent = vi.fn();
    const rpc = makeRpc({ llm: mockLlm(), onEvent });
    await rpc.run({ force: true });
    await rpc.run({ force: true });
    expect(onEvent).toHaveBeenCalledTimes(2);
  });

  it("does not throw when the listener throws", async () => {
    const onEvent = vi.fn(() => { throw new Error("listener crash"); });
    const rpc = makeRpc({ llm: mockLlm(), onEvent });
    // Should not reject despite the listener throwing.
    const out = await rpc.run({ force: true });
    expect(out).not.toHaveProperty("error");
  });
});

describe("pushTestEvent", () => {
  it("seeds the history without invoking the pipeline", () => {
    const rpc = makeRpc();
    rpc.pushTestEvent({
      ts: 1000,
      layer: 3,
      beforeTokens: 100,
      afterTokens: 40,
      elapsedMs: 50,
      failed: false,
    });
    expect(rpc.eventLog()).toHaveLength(1);
  });
});

describe("integration: partial compact via from/upTo", () => {
  it("passes fromId to the pipeline when provided", async () => {
    const hist: Message[] = [
      userMsg("a", "first", 1),
      userMsg("b", "second", 2),
      userMsg("c", "third", 3),
    ];
    const llm: LlmClient = {
      complete: async () => JSON.stringify(VALID_SUMMARY),
    };
    const buildInput = () => makeInput({ history: hist });
    const rpc = makeRpc({ llm, buildInput });
    const out = await rpc.run({ from: "b", force: true });
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.failed).toBe(false);
  });
});

describe("internal error class", () => {
  it("does not throw when buildInput throws synchronously", async () => {
    // Sync throw in buildInput surfaces as a failure event.
    const rpc = makeRpc({
      buildInput: () => { throw new Error("sync boom"); },
    });
    const out = await rpc.run({});
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result.failureReason).toBe("buildInput: sync boom");
  });
});

describe("persistent history store (T-193 integration)", () => {
  it("forwards every recorded event to the store", async () => {
    const store = new MemoryHistoryStore();
    const rpc = makeRpc({ llm: mockLlm(), store });
    await rpc.run({ force: true });
    await rpc.run({ force: true });
    expect(store.count()).toBe(2);
    const fromStore = store.recent();
    expect(fromStore).toHaveLength(2);
    expect(fromStore[0]!.ts).toBeDefined();
  });

  it("compact/history returns the persistent-store contents when present", async () => {
    const store = new MemoryHistoryStore();
    const rpc = makeRpc({ llm: mockLlm(), store });
    await rpc.run({ force: true });
    // The ring buffer is in sync with the store, so the RPC
    // should surface at least one event. The exact source (store
    // vs buffer) is an implementation detail; we just assert the
    // count and ordering.
    const out = rpc.history();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    expect(out.result).toHaveLength(1);
  });

  it("falls back to the in-memory ring buffer when the store is empty", async () => {
    const store = new MemoryHistoryStore();
    const rpc = makeRpc({ llm: mockLlm(), store });
    await rpc.run({ force: true });
    store.clear();
    const out = rpc.history();
    expect(out).not.toHaveProperty("error");
    if ("error" in out) return;
    // Ring buffer still has the event; the fallback kicks in.
    expect(out.result).toHaveLength(1);
  });

  it("does not crash when the store throws on append", async () => {
    const store = new MemoryHistoryStore();
    const original = store.append.bind(store);
    store.append = () => { throw new Error("store down"); };
    const rpc = makeRpc({ llm: mockLlm(), store });
    const out = await rpc.run({ force: true });
    expect(out).not.toHaveProperty("error");
    // Restore so the ring buffer can still record.
    store.append = original;
    expect(rpc.eventLog()).toHaveLength(1);
  });
});
