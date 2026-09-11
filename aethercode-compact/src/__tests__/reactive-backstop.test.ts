/**
 * Tests for the reactive backstop (T-150 → T-152, T-153).
 *
 * Covers:
 *  - `isPromptTooLong` matches common provider error patterns
 *  - on a successful first call, no backstop is invoked
 *  - on a `prompt_too_long` first call, the LLM is retried with a
 *    shrunken prompt and the retry succeeds
 *  - on a `prompt_too_long` first call followed by another failure,
 *    a `ReactiveBackstopExhaustedError` is thrown with the attempt
 *    history (T-152)
 *  - the circuit breaker is notified on success / failure
 *  - non-`prompt_too_long` errors propagate untouched
 *  - `buildShrunkCompactInput` returns a smaller history on big bodies
 *  - `reactiveBackstopPass` returns a Layer 1 result when no llm is
 *    supplied
 */
import { describe, expect, it } from "vitest";

import { CircuitBreaker } from "../circuit-breaker.js";
import { CLEARED_PLACEHOLDER } from "../types.js";
import {
  buildShrunkCompactInput,
  isPromptTooLong,
  llmCallWithBackstop,
  ReactiveBackstopExhaustedError,
  reactiveBackstopPass,
} from "../reactive-backstop.js";
import { CompactPipeline } from "../pipeline.js";
import {
  assistantMsg,
  makeInput,
  mockLlm,
  toolMsg,
  userMsg,
  VALID_SUMMARY,
} from "./fixtures.js";
import type { LlmClient } from "../types.js";

const PIPELINE_OPTS = CompactPipeline.defaults();

function makePromptTooLongError(message: string): Error {
  const e = new Error(message);
  return e;
}

describe("isPromptTooLong (T-150)", () => {
  it("matches 'prompt is too long' provider messages", () => {
    expect(isPromptTooLong(new Error("prompt is too long"))).toBe(true);
  });

  it("matches 'prompt_too_long' tokenised messages", () => {
    expect(isPromptTooLong(new Error("API error: prompt_too_long"))).toBe(true);
  });

  it("matches 'maximum context length' / 'context length exceeded'", () => {
    expect(isPromptTooLong(new Error("maximum context length exceeded"))).toBe(true);
    expect(isPromptTooLong(new Error("context_length_exceeded: 200000"))).toBe(true);
  });

  it("matches 'string too long' / 'input length exceeded'", () => {
    expect(isPromptTooLong(new Error("string too long for buffer"))).toBe(true);
    expect(isPromptTooLong(new Error("input length exceeded"))).toBe(true);
  });

  it("matches JSON-stringified errors containing the marker", () => {
    const err = { code: "prompt_too_long", message: "context overflow" };
    expect(isPromptTooLong(err)).toBe(true);
  });

  it("returns false for unrelated errors", () => {
    expect(isPromptTooLong(new Error("rate limit exceeded"))).toBe(false);
    expect(isPromptTooLong(new Error("invalid api key"))).toBe(false);
    expect(isPromptTooLong("not an error object")).toBe(false);
    expect(isPromptTooLong({ code: "rate_limited" })).toBe(false);
  });
});

describe("llmCallWithBackstop (T-151)", () => {
  it("returns the LLM response on the first try without retrying", async () => {
    let calls = 0;
    const llm: LlmClient = {
      complete: async () => {
        calls += 1;
        return JSON.stringify(VALID_SUMMARY);
      },
    };
    const input = makeInput({ history: [userMsg("u1", "x")] });
    const outcome = await llmCallWithBackstop(
      { input, callOptions: { maxTokens: 8_000, model: "test" }, llm },
      { pipelineOptions: PIPELINE_OPTS },
    );
    expect(calls).toBe(1);
    expect(outcome.attempts).toHaveLength(0);
    expect(JSON.parse(outcome.text).userTaskIntent).toBe(VALID_SUMMARY.userTaskIntent);
  });

  it("retries once after a prompt_too_long first error and the retry succeeds (T-151)", async () => {
    let calls = 0;
    const llm: LlmClient = {
      complete: async () => {
        calls += 1;
        if (calls === 1) {
          throw makePromptTooLongError("prompt_too_long: history is 250K tokens");
        }
        return JSON.stringify(VALID_SUMMARY);
      },
    };
    const big = "x".repeat(5_000);
    const input = makeInput({
      history: [
        userMsg("u1", "big body"),
        toolMsg("t1", "read_file", big, "c1"),
      ],
    });
    const outcome = await llmCallWithBackstop(
      { input, callOptions: { maxTokens: 8_000, model: "test" }, llm },
      { pipelineOptions: PIPELINE_OPTS },
    );
    expect(calls).toBe(2);
    expect(outcome.attempts).toHaveLength(2);
    expect(outcome.attempts[0]?.succeeded).toBe(false);
    expect(outcome.attempts[0]?.trigger).toBe("prompt_too_long");
    expect(outcome.attempts[1]?.succeeded).toBe(true);
    expect(outcome.attempts[1]?.trigger).toBe("prompt_too_long");
    expect(JSON.parse(outcome.text).userTaskIntent).toBe(VALID_SUMMARY.userTaskIntent);
  });

  it("retries with the shrunken prompt (no big read_file body)", async () => {
    let secondPrompt = "";
    let calls = 0;
    const big = "x".repeat(5_000);
    const llm: LlmClient = {
      complete: async (prompt) => {
        calls += 1;
        if (calls === 1) {
          throw makePromptTooLongError("prompt_too_long");
        }
        secondPrompt = prompt;
        return JSON.stringify(VALID_SUMMARY);
      },
    };
    const input = makeInput({
      history: [
        userMsg("u1", "u-body"),
        toolMsg("t1", "read_file", big, "c1"),
      ],
    });
    await llmCallWithBackstop(
      { input, callOptions: { maxTokens: 8_000, model: "test" }, llm },
      { pipelineOptions: PIPELINE_OPTS },
    );
    // The retry prompt should not contain the big body.
    expect(secondPrompt).not.toContain(big);
    // But the user message is preserved.
    expect(secondPrompt).toContain("USER: u-body");
  });

  it("throws ReactiveBackstopExhaustedError when the retry also fails (T-152)", async () => {
    let calls = 0;
    const llm: LlmClient = {
      complete: async () => {
        calls += 1;
        throw makePromptTooLongError("prompt_too_long");
      },
    };
    const input = makeInput({ history: [userMsg("u1", "x")] });
    await expect(
      llmCallWithBackstop(
        { input, callOptions: { maxTokens: 8_000, model: "test" }, llm },
        { pipelineOptions: PIPELINE_OPTS },
      ),
    ).rejects.toBeInstanceOf(ReactiveBackstopExhaustedError);
    expect(calls).toBe(2);
  });

  it("the thrown error carries both attempts (T-152)", async () => {
    const llm: LlmClient = {
      complete: async () => {
        throw makePromptTooLongError("prompt_too_long");
      },
    };
    const input = makeInput({ history: [userMsg("u1", "x")] });
    try {
      await llmCallWithBackstop(
        { input, callOptions: { maxTokens: 8_000, model: "test" }, llm },
        { pipelineOptions: PIPELINE_OPTS },
      );
      throw new Error("expected throw");
    } catch (err) {
      expect(err).toBeInstanceOf(ReactiveBackstopExhaustedError);
      const e = err as ReactiveBackstopExhaustedError;
      expect(e.attempts).toHaveLength(2);
      expect(e.attempts[0]?.succeeded).toBe(false);
      expect(e.attempts[1]?.succeeded).toBe(false);
      expect(e.message).toMatch(/prompt_too_long/);
    }
  });

  it("non-prompt_too_long errors propagate untouched", async () => {
    const llm: LlmClient = {
      complete: async () => {
        throw new Error("rate limit exceeded");
      },
    };
    const input = makeInput({ history: [userMsg("u1", "x")] });
    await expect(
      llmCallWithBackstop(
        { input, callOptions: { maxTokens: 8_000, model: "test" }, llm },
        { pipelineOptions: PIPELINE_OPTS },
      ),
    ).rejects.toThrow(/rate limit/);
  });

  it("records breaker success when the retry succeeds", async () => {
    let calls = 0;
    const llm: LlmClient = {
      complete: async () => {
        calls += 1;
        if (calls === 1) {
          throw makePromptTooLongError("prompt_too_long");
        }
        return JSON.stringify(VALID_SUMMARY);
      },
    };
    const cb = new CircuitBreaker();
    const big = "x".repeat(5_000);
    const input = makeInput({
      history: [
        userMsg("u1", "u-body"),
        toolMsg("t1", "read_file", big, "c1"),
      ],
    });
    await llmCallWithBackstop(
      { input, callOptions: { maxTokens: 8_000, model: "test" }, llm },
      { pipelineOptions: PIPELINE_OPTS, circuitBreaker: cb, sessionId: "s1" },
    );
    // The breaker should have been notified of success.
    expect(cb.status("s1").consecutiveCompactFailures).toBe(0);
    expect(cb.isTripped("s1")).toBe(false);
  });

  it("records breaker failure when the retry fails (T-152)", async () => {
    const llm: LlmClient = {
      complete: async () => {
        throw makePromptTooLongError("prompt_too_long");
      },
    };
    const cb = new CircuitBreaker();
    const input = makeInput({ history: [userMsg("u1", "x")] });
    await expect(
      llmCallWithBackstop(
        { input, callOptions: { maxTokens: 8_000, model: "test" }, llm },
        { pipelineOptions: PIPELINE_OPTS, circuitBreaker: cb, sessionId: "s1" },
      ),
    ).rejects.toBeInstanceOf(ReactiveBackstopExhaustedError);
    expect(cb.status("s1").consecutiveCompactFailures).toBeGreaterThan(0);
  });
});

describe("buildShrunkCompactInput", () => {
  it("returns the input unchanged when no big read-class bodies exist", () => {
    const input = makeInput({ history: [userMsg("u1", "short")] });
    const out = buildShrunkCompactInput(input, PIPELINE_OPTS);
    expect(out.history).toEqual(input.history);
  });

  it("clears big read-class bodies via Layer 1", () => {
    const big = "x".repeat(5_000);
    const input = makeInput({
      history: [userMsg("u1", "u-body"), toolMsg("t1", "read_file", big, "c1")],
      cacheStatus: "cool",
    });
    const out = buildShrunkCompactInput(input, PIPELINE_OPTS);
    expect(out.history[1]?.content).toBe(CLEARED_PLACEHOLDER);
  });

  it("keeps the non-read-class body intact", () => {
    const big = "x".repeat(5_000);
    const input = makeInput({
      history: [userMsg("u1", "u-body"), toolMsg("t1", "TodoWrite", big, "c1")],
      cacheStatus: "cool",
    });
    const out = buildShrunkCompactInput(input, PIPELINE_OPTS);
    expect(out.history[1]?.content).toBe(big);
  });
});

describe("reactiveBackstopPass", () => {
  it("returns a Layer 1 result when no llm is supplied", async () => {
    const big = "x".repeat(5_000);
    const input = makeInput({
      history: [toolMsg("t1", "read_file", big, "c1")],
      cacheStatus: "cool",
    });
    const result = await reactiveBackstopPass(input, PIPELINE_OPTS);
    expect(result.layer).toBe(1);
    expect(result.history[0]?.content).toBe(CLEARED_PLACEHOLDER);
  });

  it("runs a one-shot Layer 1 + Layer 3 combo when llm is supplied", async () => {
    const big = "x".repeat(5_000);
    const input = makeInput({
      history: [
        userMsg("u1", "old turn 1"),
        assistantMsg("a1", "old reply"),
        toolMsg("t1", "read_file", big, "c1"),
        userMsg("u2", "recent turn 2"),
      ],
      cacheStatus: "cool",
    });
    const result = await reactiveBackstopPass(input, PIPELINE_OPTS, {
      llm: mockLlm(),
    });
    expect(result.layer).toBe(3);
    expect(result.summary).toBeDefined();
  });

  it("records breaker failure when the llm call fails", async () => {
    const failingLlm: LlmClient = {
      complete: async () => {
        throw new Error("upstream model is down");
      },
    };
    const cb = new CircuitBreaker();
    const input = makeInput({ history: [userMsg("u1", "x")] });
    await expect(
      reactiveBackstopPass(input, PIPELINE_OPTS, {
        llm: failingLlm,
        circuitBreaker: cb,
        sessionId: "s1",
      }),
    ).rejects.toThrow(/upstream/);
    expect(cb.status("s1").consecutiveCompactFailures).toBe(1);
  });

  it("records breaker success when the llm call succeeds", async () => {
    const cb = new CircuitBreaker();
    const input = makeInput({ history: [userMsg("u1", "x")] });
    await reactiveBackstopPass(input, PIPELINE_OPTS, {
      llm: mockLlm(),
      circuitBreaker: cb,
      sessionId: "s1",
    });
    expect(cb.status("s1").consecutiveCompactFailures).toBe(0);
  });
});
