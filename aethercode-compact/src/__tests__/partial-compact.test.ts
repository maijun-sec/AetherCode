/**
 * Tests for partial compact (T-160 → T-162).
 *
 * Covers:
 *  - `findMessageIndex` locates the right id
 *  - `planCompactFrom` returns the kept prefix and compacted slice
 *  - `planCompactUpTo` returns the compacted slice and kept suffix
 *  - the cache impact is "keeps prefix" for `--from` and "breaks
 *    prefix" for `--up-to`
 *  - the CLI option parser rejects `--from` + `--up-to` together
 *  - `CompactPipeline.compact({ fromId })` runs Layer 3 only over
 *    the slice after the anchor and emits a summary-anchored history
 *  - the post-Layer-1 history is the input to the LLM (so big
 *    bodies are already cleared by the time the anchor is located)
 *  - fallback to full compact when the anchor is missing
 *  - cache impact is exposed via `cacheReference`
 */
import { describe, expect, it } from "vitest";

import { CompactPipeline } from "../pipeline.js";
import {
  applyPartialCompact,
  applyPartialCompactFrom,
  applyPartialCompactUpTo,
  findMessageIndex,
  parsePartialCompactOptions,
  partialCompactCacheImpact,
  planCompactFrom,
  planCompactUpTo,
} from "../partial-compact.js";
import {
  assistantMsg,
  makeInput,
  mockLlm,
  toolMsg,
  userMsg,
  VALID_SUMMARY,
} from "./fixtures.js";
import type { CompactSummary, Message } from "../types.js";

const PIPELINE_OPTS = CompactPipeline.defaults();
const FIXED_NOW = () => 1_700_000_000_000;

function makeSummary(): CompactSummary {
  return { ...VALID_SUMMARY };
}

function bigReadFile(id: string, body: string, callId?: string): Message {
  return toolMsg(id, "read_file", body, callId ?? `${id}-call`);
}

describe("findMessageIndex", () => {
  it("returns the index of the matching id", () => {
    const h = [userMsg("a", "1"), userMsg("b", "2"), userMsg("c", "3")];
    expect(findMessageIndex(h, "a")).toBe(0);
    expect(findMessageIndex(h, "b")).toBe(1);
    expect(findMessageIndex(h, "c")).toBe(2);
  });

  it("returns -1 when the id is not found", () => {
    const h = [userMsg("a", "1")];
    expect(findMessageIndex(h, "nope")).toBe(-1);
  });
});

describe("planCompactFrom (T-160)", () => {
  it("returns keptPrefix up to and including the anchor; compacted slice is everything after", () => {
    const h = [
      userMsg("a", "1"),
      assistantMsg("b", "2"),
      userMsg("c", "anchor"),
      userMsg("d", "4"),
      assistantMsg("e", "5"),
    ];
    const plan = planCompactFrom(h, "c");
    expect(plan.found).toBe(true);
    if (plan.mode !== "from" || !plan.found) {
      throw new Error("expected from plan");
    }
    expect(plan.anchorIndex).toBe(2);
    expect(plan.keptPrefix.map((m) => m.id)).toEqual(["a", "b", "c"]);
    expect(plan.compactedSlice.map((m) => m.id)).toEqual(["d", "e"]);
  });

  it("returns found=false and falls back to compacting everything when the anchor is missing", () => {
    const h = [userMsg("a", "1"), userMsg("b", "2")];
    const plan = planCompactFrom(h, "nope");
    expect(plan.found).toBe(false);
    expect(plan.compactedSlice).toHaveLength(2);
  });

  it("handles anchor at the end of the history (empty slice)", () => {
    const h = [userMsg("a", "1"), userMsg("b", "anchor")];
    const plan = planCompactFrom(h, "b");
    expect(plan.found).toBe(true);
    if (plan.mode !== "from" || !plan.found) {
      throw new Error("expected from plan");
    }
    expect(plan.compactedSlice).toHaveLength(0);
  });
});

describe("planCompactUpTo (T-161)", () => {
  it("compacts the prefix; the anchor and everything after are kept as the suffix", () => {
    const h = [
      userMsg("a", "1"),
      assistantMsg("b", "2"),
      userMsg("c", "anchor"),
      userMsg("d", "4"),
      assistantMsg("e", "5"),
    ];
    const plan = planCompactUpTo(h, "c");
    expect(plan.found).toBe(true);
    if (plan.mode !== "upTo" || !plan.found) {
      throw new Error("expected upTo plan");
    }
    expect(plan.anchorIndex).toBe(2);
    expect(plan.compactedSlice.map((m) => m.id)).toEqual(["a", "b"]);
    expect(plan.keptSuffix.map((m) => m.id)).toEqual(["c", "d", "e"]);
  });

  it("returns found=false and falls back to compacting everything when the anchor is missing", () => {
    const h = [userMsg("a", "1"), userMsg("b", "2")];
    const plan = planCompactUpTo(h, "nope");
    expect(plan.found).toBe(false);
    expect(plan.compactedSlice).toHaveLength(2);
    if (plan.mode !== "upTo") {
      throw new Error("expected upTo plan");
    }
    expect(plan.keptSuffix).toHaveLength(0);
  });
});

describe("applyPartialCompact", () => {
  const summary = makeSummary();

  it("splices the summary between kept prefix and kept suffix", () => {
    const prefix = [userMsg("a", "1")];
    const suffix = [userMsg("b", "2")];
    const out = applyPartialCompact(prefix, summary, suffix, { now: FIXED_NOW });
    expect(out).toHaveLength(3);
    expect(out[0]?.id).toBe("a");
    expect(out[1]?.role).toBe("summary");
    expect(out[2]?.id).toBe("b");
  });

  it("summary message id is deterministic for the same `now`", () => {
    const a = applyPartialCompact([], summary, [], { now: FIXED_NOW });
    const b = applyPartialCompact([], summary, [], { now: FIXED_NOW });
    expect(a[0]?.id).toBe(b[0]?.id);
  });
});

describe("applyPartialCompactFrom / applyPartialCompactUpTo", () => {
  const summary = makeSummary();

  it("from-mode discards the kept suffix (none, in the from plan)", () => {
    const h = [userMsg("a", "anchor"), userMsg("b", "x")];
    const plan = planCompactFrom(h, "a");
    if (plan.mode !== "from" || !plan.found) {
      throw new Error("expected from plan");
    }
    const out = applyPartialCompactFrom(plan, summary, { now: FIXED_NOW });
    expect(out).toHaveLength(2);
    expect(out[0]?.id).toBe("a");
    expect(out[1]?.role).toBe("summary");
  });

  it("upTo-mode keeps the anchor in the kept suffix", () => {
    const h = [userMsg("a", "x"), userMsg("b", "anchor")];
    const plan = planCompactUpTo(h, "b");
    if (plan.mode !== "upTo" || !plan.found) {
      throw new Error("expected upTo plan");
    }
    const out = applyPartialCompactUpTo(plan, summary, { now: FIXED_NOW });
    // The compacted slice was just [a], replaced by the summary. The
    // anchor b is the head of the kept suffix.
    expect(out).toHaveLength(2);
    expect(out[0]?.role).toBe("summary");
    expect(out[1]?.id).toBe("b");
  });
});

describe("partialCompactCacheImpact (T-162)", () => {
  it("--from keeps the prefix cache (T-160)", () => {
    const h = [userMsg("a", "anchor"), userMsg("b", "x")];
    const plan = planCompactFrom(h, "a");
    const impact = partialCompactCacheImpact(plan);
    expect(impact.breaksPrefixCache).toBe(false);
    expect(impact.reason).toMatch(/prefix/);
  });

  it("--up-to breaks the prefix cache (T-161)", () => {
    const h = [userMsg("a", "x"), userMsg("b", "anchor")];
    const plan = planCompactUpTo(h, "b");
    const impact = partialCompactCacheImpact(plan);
    expect(impact.breaksPrefixCache).toBe(true);
    expect(impact.reason).toMatch(/replaces/);
  });

  it("an un-found anchor reports fallback to full compact", () => {
    const h = [userMsg("a", "x")];
    const plan = planCompactFrom(h, "nope");
    const impact = partialCompactCacheImpact(plan);
    expect(impact.breaksPrefixCache).toBe(true);
    expect(impact.reason).toMatch(/fallback/);
  });
});

describe("parsePartialCompactOptions (T-160/T-161)", () => {
  it("returns null when neither --from nor --up-to is given", () => {
    expect(parsePartialCompactOptions({})).toBeNull();
  });

  it("returns { mode: 'from', anchorId } for --from", () => {
    expect(parsePartialCompactOptions({ from: "abc" })).toEqual({
      mode: "from",
      anchorId: "abc",
    });
  });

  it("returns { mode: 'upTo', anchorId } for --up-to", () => {
    expect(parsePartialCompactOptions({ upTo: "abc" })).toEqual({
      mode: "upTo",
      anchorId: "abc",
    });
  });

  it("rejects --from + --up-to together", () => {
    expect(() => parsePartialCompactOptions({ from: "a", upTo: "b" })).toThrow(
      /mutually exclusive/,
    );
  });
});

describe("CompactPipeline.compact with fromId (T-160)", () => {
  it("compacts only the slice after the anchor; keeps the prefix; emits a summary message", async () => {
    let receivedPrompt = "";
    const llm = {
      complete: async (prompt: string) => {
        receivedPrompt = prompt;
        return JSON.stringify(makeSummary());
      },
    };
    const pipeline = new CompactPipeline(PIPELINE_OPTS);
    const history: Message[] = [
      userMsg("a", "first user turn"),
      assistantMsg("b", "first assistant reply"),
      userMsg("c", "anchor turn"),
      userMsg("d", "old user turn to compact"),
      assistantMsg("e", "old assistant turn to compact"),
      userMsg("f", "another old turn"),
    ];
    const result = await pipeline.compact(
      makeInput({ model: { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 }, history }),
      { llm, fromId: "c" },
    );
    expect(result.layer).toBe(3);
    expect(result.summary).toBeDefined();
    // The LLM should only see the compacted slice (d, e, f), not (a, b, c).
    expect(receivedPrompt).not.toContain("first user turn");
    expect(receivedPrompt).toContain("old user turn to compact");
    // The new history keeps the prefix and replaces the slice with the
    // summary.
    const ids = result.history.map((m) => m.id);
    expect(ids).toContain("a");
    expect(ids).toContain("b");
    expect(ids).toContain("c");
    expect(result.history.some((m) => m.role === "summary")).toBe(true);
    // The summary message is between the prefix and the slice (which is
    // empty after compaction).
    expect(ids.filter((i) => i === "d" || i === "e" || i === "f")).toEqual([]);
    // The cache reference advertises the partial compact.
    expect(result.cacheReference).toMatch(/partial=from/);
  });

  it("falls back to a full Layer 3 compact when the anchor is not found", async () => {
    let calls = 0;
    const llm = {
      complete: async () => {
        calls += 1;
        return JSON.stringify(makeSummary());
      },
    };
    const pipeline = new CompactPipeline(PIPELINE_OPTS);
    const history: Message[] = [userMsg("a", "1"), userMsg("b", "2")];
    const result = await pipeline.compact(
      makeInput({ model: { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 }, history }),
      { llm, fromId: "nope" },
    );
    expect(calls).toBe(1);
    expect(result.layer).toBe(3);
    expect(result.history.some((m) => m.role === "summary")).toBe(true);
  });

  it("returns a Layer 1 result with a note in cacheReference when no llm is supplied", async () => {
    const pipeline = new CompactPipeline(PIPELINE_OPTS);
    const history: Message[] = [userMsg("a", "1"), userMsg("c", "anchor"), userMsg("d", "x")];
    const result = await pipeline.compact(
      makeInput({ model: { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 }, history }),
      { fromId: "c" },
    );
    expect(result.layer).toBe(1);
    expect(result.cacheReference).toMatch(/partial_compact/);
  });

  it("emits an empty slice result when the anchor is at the end of the history", async () => {
    const pipeline = new CompactPipeline(PIPELINE_OPTS);
    const history: Message[] = [userMsg("a", "1"), userMsg("b", "anchor")];
    const result = await pipeline.compact(
      makeInput({ model: { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 }, history }),
      { llm: mockLlm(), fromId: "b" },
    );
    // Nothing to compact; the LLM is not called and the result is the
    // post-Layer-1 pass.
    expect(result.layer).toBe(1);
  });

  it("clears big read-class bodies in the kept prefix via Layer 1", async () => {
    const big = "x".repeat(5_000);
    const llm = {
      complete: async () => JSON.stringify(makeSummary()),
    };
    const pipeline = new CompactPipeline(PIPELINE_OPTS);
    const history: Message[] = [
      userMsg("a", "first turn"),
      bigReadFile("read-1", big, "call-1"),
      userMsg("c", "anchor"),
      userMsg("d", "to compact"),
    ];
    const result = await pipeline.compact(
      makeInput({
        model: { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 },
        history,
        cacheStatus: "cool",
      }),
      { llm, fromId: "c" },
    );
    expect(result.layer).toBe(3);
    // The big body is replaced by a placeholder in the kept prefix.
    const readEntry = result.history.find((m) => m.id === "read-1");
    expect(readEntry?.content).toBe("[Old tool result content cleared]");
  });
});

describe("CompactPipeline.compact with upToId (T-161)", () => {
  it("compacts the prefix; keeps the suffix (including the anchor); emits a summary message", async () => {
    let receivedPrompt = "";
    const llm = {
      complete: async (prompt: string) => {
        receivedPrompt = prompt;
        return JSON.stringify(makeSummary());
      },
    };
    const pipeline = new CompactPipeline(PIPELINE_OPTS);
    const history: Message[] = [
      userMsg("a", "old turn to compact"),
      assistantMsg("b", "old assistant to compact"),
      userMsg("c", "anchor turn"),
      userMsg("d", "recent turn to keep"),
      assistantMsg("e", "recent assistant to keep"),
    ];
    const result = await pipeline.compact(
      makeInput({ model: { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 }, history }),
      { llm, upToId: "c" },
    );
    expect(result.layer).toBe(3);
    // The LLM should only see the prefix slice (a, b), not (c, d, e).
    expect(receivedPrompt).toContain("old turn to compact");
    expect(receivedPrompt).not.toContain("recent turn to keep");
    // The new history keeps the suffix: the anchor and everything after.
    const ids = result.history.map((m) => m.id);
    expect(ids).toContain("c");
    expect(ids).toContain("d");
    expect(ids).toContain("e");
    // The old prefix is gone.
    expect(ids).not.toContain("a");
    expect(ids).not.toContain("b");
    // The summary message is at the head of the new history.
    expect(result.history[0]?.role).toBe("summary");
  });

  it("upToId breaks the prefix cache (T-162)", async () => {
    const llm = {
      complete: async () => JSON.stringify(makeSummary()),
    };
    const pipeline = new CompactPipeline(PIPELINE_OPTS);
    const history: Message[] = [
      userMsg("a", "1"),
      userMsg("b", "anchor"),
      userMsg("c", "2"),
    ];
    const result = await pipeline.compact(
      makeInput({ model: { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 }, history }),
      { llm, upToId: "b" },
    );
    expect(result.cacheReference).toBeNull();
  });
});

describe("CompactPipeline.compact — partial invalid arg", () => {
  it("throws when both fromId and upToId are supplied", async () => {
    const pipeline = new CompactPipeline(PIPELINE_OPTS);
    const history: Message[] = [userMsg("a", "1")];
    await expect(
      pipeline.compact(
        makeInput({ model: { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 }, history }),
        { fromId: "a", upToId: "a" },
      ),
    ).rejects.toThrow(/mutually exclusive/);
  });
});
