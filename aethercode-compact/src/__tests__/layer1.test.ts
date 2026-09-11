/**
 * Tests for Layer 1 — Microcompact (T-110, T-111, T-112).
 *
 * Covers:
 *  - read-class tool detection
 *  - body replacement above threshold
 *  - body kept when below threshold
 *  - non-read-class tool bodies (TodoWrite etc.) preserved
 *  - cache-warm branch: no local edit, cacheReference set
 *  - cache-cool / unknown branch: local edit applied
 *  - byte-size measurement is against the body, not the message
 */
import { describe, expect, it } from "vitest";

import { isCacheWarm, isReadClassTool, microCompact, shouldClearBody, buildCacheReference } from "../layer1.js";
import {
  CLEARED_PLACEHOLDER,
  READ_CLASS_TOOL_NAMES,
  type Message,
} from "../types.js";
import { makeInput, toolMsg, toolResult } from "./fixtures.js";

const LAYER1_OPTS = { clearThresholdBytes: 4_096, cacheWarmWindowMs: 30_000 };

describe("isReadClassTool", () => {
  it("returns true for every read-class tool name", () => {
    for (const name of READ_CLASS_TOOL_NAMES) {
      expect(isReadClassTool(name)).toBe(true);
    }
  });

  it("returns false for non-read-class tools (TodoWrite)", () => {
    expect(isReadClassTool("TodoWrite")).toBe(false);
    expect(isReadClassTool("TodoRead")).toBe(false);
    expect(isReadClassTool("update_plan")).toBe(false);
  });

  it("returns false for undefined / empty", () => {
    expect(isReadClassTool(undefined)).toBe(false);
    expect(isReadClassTool("")).toBe(false);
  });
});

describe("shouldClearBody", () => {
  it("is true for bodies over the threshold", () => {
    expect(shouldClearBody("a".repeat(4_097), 4_096)).toBe(true);
  });

  it("is false for bodies at or under the threshold", () => {
    expect(shouldClearBody("a".repeat(4_096), 4_096)).toBe(false);
    expect(shouldClearBody("short", 4_096)).toBe(false);
  });
});

describe("isCacheWarm", () => {
  const now = () => 1_000_000;

  it("returns true when input.cacheStatus is 'warm'", () => {
    expect(isCacheWarm(makeInput({ cacheStatus: "warm" }), 30_000, now)).toBe(true);
  });

  it("returns false when input.cacheStatus is 'cool'", () => {
    expect(isCacheWarm(makeInput({ cacheStatus: "cool" }), 30_000, now)).toBe(false);
    expect(
      isCacheWarm(makeInput({ cacheStatus: "cool", lastAssistantTs: now() }), 30_000, now),
    ).toBe(false);
  });

  it("derives warm from lastAssistantTs when status is unknown", () => {
    const recent = now() - 5_000;
    const stale = now() - 60_000;
    expect(
      isCacheWarm(makeInput({ cacheStatus: "unknown", lastAssistantTs: recent }), 30_000, now),
    ).toBe(true);
    expect(
      isCacheWarm(makeInput({ cacheStatus: "unknown", lastAssistantTs: stale }), 30_000, now),
    ).toBe(false);
  });
});

describe("microCompact", () => {
  it("replaces a large read_file tool message with the placeholder", () => {
    const big = "x".repeat(5_000);
    const history: Message[] = [
      toolMsg("m1", "read_file", big, "call-1"),
      toolMsg("m2", "read_file", "small body", "call-2"),
    ];
    const input = makeInput({ history, cacheStatus: "cool" });
    const result = microCompact(input, LAYER1_OPTS);

    expect(result.layer).toBe(1);
    expect(result.history[0]?.content).toBe(CLEARED_PLACEHOLDER);
    expect(result.history[0]?.tokens).toBe(0);
    expect(result.history[1]?.content).toBe("small body");
    expect(result.afterTokens).toBeLessThan(result.beforeTokens);
    expect(result.clearedToolResultIds).toContain("call-1");
    expect(result.clearedToolResultIds).not.toContain("call-2");
  });

  it("keeps TodoWrite outputs intact", () => {
    const big = "x".repeat(5_000);
    const history: Message[] = [toolMsg("m1", "TodoWrite", big, "call-1")];
    const input = makeInput({ history, cacheStatus: "cool" });
    const result = microCompact(input, LAYER1_OPTS);

    expect(result.history[0]?.content).toBe(big);
    expect(result.clearedToolResultIds).toBeUndefined();
  });

  it("clears bodies on the toolResults array, too", () => {
    const big = "x".repeat(5_000);
    const results = [toolResult("r1", "bash", big, "call-r1")];
    const input = makeInput({ toolResults: results, cacheStatus: "cool" });
    const result = microCompact(input, LAYER1_OPTS);

    expect(result.toolResults[0]?.body).toBe(CLEARED_PLACEHOLDER);
    expect(result.toolResults[0]?.cleared).toBe(true);
  });

  it("skips local edit and sets cacheReference when warm", () => {
    const big = "x".repeat(5_000);
    const history: Message[] = [toolMsg("m1", "read_file", big, "call-1")];
    const results = [toolResult("r1", "bash", big, "call-r1")];
    const input = makeInput({ history, toolResults: results, cacheStatus: "warm" });
    const result = microCompact(input, LAYER1_OPTS);

    // Local state is unchanged.
    expect(result.history[0]?.content).toBe(big);
    expect(result.toolResults[0]?.body).toBe(big);
    // Directive is set.
    expect(result.cacheReference).not.toBeNull();
    expect(result.cacheReference).toContain("call-1");
    expect(result.cacheReference).toContain("call-r1");
    expect(result.afterTokens).toBe(result.beforeTokens);
  });

  it("derives warm state from lastAssistantTs when status is unknown", () => {
    const big = "x".repeat(5_000);
    const history: Message[] = [toolMsg("m1", "read_file", big, "call-1")];
    const input = makeInput({
      history,
      cacheStatus: "unknown",
      lastAssistantTs: Date.now() - 5_000, // 5 s ago → warm
    });
    const result = microCompact(input, LAYER1_OPTS);
    expect(result.cacheReference).not.toBeNull();
  });

  it("is idempotent: a second call on already-cleared messages is a no-op", () => {
    const big = "x".repeat(5_000);
    const first = microCompact(
      makeInput({ history: [toolMsg("m1", "read_file", big, "call-1")], cacheStatus: "cool" }),
      LAYER1_OPTS,
    );
    const second = microCompact(
      makeInput({ history: first.history, cacheStatus: "cool" }),
      LAYER1_OPTS,
    );
    expect(second.history[0]?.content).toBe(CLEARED_PLACEHOLDER);
    expect(second.clearedToolResultIds).toBeUndefined();
  });

  it("reports elapsedMs >= 0", () => {
    const result = microCompact(makeInput(), LAYER1_OPTS);
    expect(result.elapsedMs).toBeGreaterThanOrEqual(0);
  });
});

describe("buildCacheReference", () => {
  it("emits a drop directive for cleared tool messages", () => {
    const history: Message[] = [
      { ...toolMsg("m1", "read_file", "small", "call-1"), content: CLEARED_PLACEHOLDER },
      toolMsg("m2", "bash", "ok", "call-2"),
    ];
    expect(buildCacheReference(history)).toBe("cache_reference:drop=call-1");
  });

  it("returns an empty string when no tool messages are cleared", () => {
    expect(buildCacheReference([])).toBe("");
  });
});
