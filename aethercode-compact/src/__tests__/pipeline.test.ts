// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

/**
 * Tests for CompactPipeline (T-101) — orchestration of Layers 1 + 3.
 *
 * Covers:
 *  - smoke: build and run on a tiny input
 *  - Layer 1 always runs (zero cost) — big bodies get cleared
 *  - if post-Layer-1 fits the budget, no LLM call
 *  - if post-Layer-1 exceeds the trigger, Layer 3 runs
 *  - defaults() exposes the 4 documented knobs
 *  - effectiveWindow() / layer3Trigger() math
 */
import { describe, expect, it, vi } from "vitest";

import { CompactPipeline, effectiveWindow, layer3Trigger } from "../pipeline.js";
import { CLEARED_PLACEHOLDER } from "../types.js";
import {
  makeInput,
  mockLlm,
  TEST_MODEL,
  toolMsg,
  userMsg,
  VALID_SUMMARY,
} from "./fixtures.js";

const BASE_OPTS = CompactPipeline.defaults();

describe("CompactPipeline.defaults", () => {
  it("exposes the 4 documented knobs", () => {
    const d = CompactPipeline.defaults();
    expect(d.cacheWarmWindowMs).toBe(30_000);
    expect(d.layer3RecentKeep).toBe(5);
    expect(d.layer3MaxOutputTokens).toBe(8_000);
    expect(d.layer1ClearThresholdBytes).toBe(4_096);
  });
});

describe("effectiveWindow / layer3Trigger", () => {
  it("effective window = maxTokens - min(maxOutputTokens, 20_000)", () => {
    expect(effectiveWindow({ name: "m", maxTokens: 200_000, maxOutputTokens: 8_000 })).toBe(
      200_000 - 8_000,
    );
    // When maxOutput > 20K, it's floored at 20K.
    expect(effectiveWindow({ name: "m", maxTokens: 200_000, maxOutputTokens: 64_000 })).toBe(
      200_000 - 20_000,
    );
  });

  it("layer3Trigger = effective_window - 13_000 headroom", () => {
    const m = { name: "m", maxTokens: 200_000, maxOutputTokens: 8_000 };
    expect(layer3Trigger(m)).toBe(200_000 - 8_000 - 13_000);
  });
});

describe("CompactPipeline.compact", () => {
  it("smoke: runs Layer 1 on a small input without an LLM", async () => {
    const pipeline = new CompactPipeline(BASE_OPTS);
    const result = await pipeline.compact(makeInput());
    expect(result.layer).toBe(1);
    expect(result.beforeTokens).toBeGreaterThanOrEqual(0);
    expect(result.elapsedMs).toBeGreaterThanOrEqual(0);
  });

  it("clears large read-class tool bodies via Layer 1", async () => {
    const pipeline = new CompactPipeline(BASE_OPTS);
    const big = "x".repeat(5_000);
    const result = await pipeline.compact(
      makeInput({
        history: [toolMsg("m1", "read_file", big, "call-1")],
        cacheStatus: "cool",
      }),
    );
    expect(result.layer).toBe(1);
    expect(result.history[0]?.content).toBe(CLEARED_PLACEHOLDER);
  });

  it("does not call the LLM when Layer 1 alone fits the budget", async () => {
    const llm = {
      complete: vi.fn(async () => JSON.stringify(VALID_SUMMARY)),
    };
    const pipeline = new CompactPipeline(BASE_OPTS);
    await pipeline.compact(makeInput(), { llm });
    expect(llm.complete).not.toHaveBeenCalled();
  });

  it("calls the LLM (Layer 3) when the post-Layer-1 prompt is over the trigger", async () => {
    const llm = {
      complete: vi.fn(async () => JSON.stringify(VALID_SUMMARY)),
    };
    // Make a model with a tiny window so any input exceeds the trigger.
    const tinyModel = { name: "tiny", maxTokens: 100, maxOutputTokens: 8_000 };
    const pipeline = new CompactPipeline(BASE_OPTS);
    const history = [userMsg("u1", "hi")];
    const result = await pipeline.compact(
      makeInput({ model: tinyModel, history }),
      { llm },
    );
    expect(llm.complete).toHaveBeenCalledTimes(1);
    expect(result.layer).toBe(3);
    expect(result.summary).toBeDefined();
  });

  it("forceLayer1=true flips a warm cache to cool so local edits apply", async () => {
    const pipeline = new CompactPipeline(BASE_OPTS);
    const big = "x".repeat(5_000);
    const result = await pipeline.compact(
      makeInput({
        history: [toolMsg("m1", "read_file", big, "call-1")],
        cacheStatus: "warm",
      }),
      { forceLayer1: true },
    );
    expect(result.history[0]?.content).toBe(CLEARED_PLACEHOLDER);
  });

  it("returns the Layer 1 result (no LLM call) when no llm is supplied and budget is exceeded", async () => {
    const tinyModel = { name: "tiny", maxTokens: 1, maxOutputTokens: 1 };
    const pipeline = new CompactPipeline(BASE_OPTS);
    const result = await pipeline.compact(
      makeInput({
        model: tinyModel,
        history: [userMsg("u1", "x".repeat(2_000))],
      }),
    );
    expect(result.layer).toBe(1);
    expect(result.summary).toBeUndefined();
  });

  it("accepts an injected prompt template via opts.layer3PromptTemplate", async () => {
    const custom = "CUSTOM {schema} | {history} END";
    const pipeline = new CompactPipeline({ ...BASE_OPTS, layer3PromptTemplate: custom });
    const llm = mockLlm();
    const spy = vi.spyOn(llm, "complete");
    const tinyModel = { ...TEST_MODEL, maxTokens: 100 };
    await pipeline.compact(
      makeInput({ model: tinyModel, history: [userMsg("u1", "x")] }),
      { llm },
    );
    const prompt = spy.mock.calls[0]?.[0] ?? "";
    expect(prompt).toContain("CUSTOM ");
    expect(prompt).toContain(" END");
  });
});
