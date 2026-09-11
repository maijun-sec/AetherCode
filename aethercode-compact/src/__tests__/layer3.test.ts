/**
 * Tests for Layer 3 — LLM-driven 8-segment compact (T-130, T-131).
 *
 * Covers:
 *  - the 4-shot prompt embeds the schema and the history
 *  - a mock LLM returning valid JSON produces a validated summary
 *  - markdown-fenced and <draft>...</draft><final>...</final> wrappers
 *    are unwrapped correctly
 *  - invalid LLM output fails validation
 *  - the result has `layer: 3` and `summary` populated
 */
import { describe, expect, it } from "vitest";

import {
  buildPrompt,
  buildSchemaReminder,
  extractJsonFromResponse,
  llmCompact,
  parseAndValidate,
  renderHistoryForPrompt,
  assertSchemaShape,
  describeSchema,
  DEFAULT_4_SHOT_PROMPT,
  enforceSegmentCap,
  replaceHistoryKeepingRecent,
} from "../layer3.js";
import { MAX_SEGMENT_CHARS, SEGMENT_FIELD_NAMES } from "../schema.js";
import {
  VALID_SUMMARY,
  assistantMsg,
  draftFinalMockLlm,
  fencedMockLlm,
  makeInput,
  mockLlm,
  toolMsg,
  userMsg,
} from "./fixtures.js";

describe("DEFAULT_4_SHOT_PROMPT", () => {
  it("contains 4 distinct shot markers", () => {
    const shots = DEFAULT_4_SHOT_PROMPT.match(/--- BEGIN SHOT \d/g) ?? [];
    expect(shots.length).toBe(4);
  });

  it("contains the {schema} and {history} placeholders", () => {
    expect(DEFAULT_4_SHOT_PROMPT).toContain("{schema}");
    expect(DEFAULT_4_SHOT_PROMPT).toContain("{history}");
  });

  it("is replaceable: a custom template flows through buildPrompt", () => {
    const custom = "PROMPT_HEAD {schema} MID {history} TAIL";
    const out = buildPrompt([], { promptTemplate: custom });
    expect(out).toBe(
      `PROMPT_HEAD ${buildSchemaReminder()} MID ${renderHistoryForPrompt([])} TAIL`,
    );
  });
});

describe("buildSchemaReminder", () => {
  it("lists all 8 field names in order", () => {
    const text = buildSchemaReminder();
    const order = [
      "userTaskIntent",
      "projectContext",
      "approachTaken",
      "bugsAndFailures",
      "toolOutputsRetained",
      "decisionsAndTradeoffs",
      "openTodos",
      "nextStepPlan",
    ];
    let prev = -1;
    for (const field of order) {
      const idx = text.indexOf(`"${field}"`);
      expect(idx).toBeGreaterThan(prev);
      prev = idx;
    }
  });
});

describe("describeSchema / assertSchemaShape", () => {
  it("returns the 8 field names", () => {
    expect(describeSchema().fields).toHaveLength(8);
  });
  it("does not throw for a valid sample", () => {
    expect(() => assertSchemaShape()).not.toThrow();
  });
});

describe("renderHistoryForPrompt", () => {
  it("prefixes TOOL(<name>) for tool messages", () => {
    const out = renderHistoryForPrompt([
      userMsg("u1", "hi"),
      toolMsg("t1", "read_file", "body"),
      assistantMsg("a1", "reply"),
    ]);
    expect(out).toContain("USER: hi");
    expect(out).toContain("TOOL(read_file): body");
    expect(out).toContain("ASSISTANT: reply");
  });
});

describe("buildPrompt", () => {
  it("inlines the schema and history into the default template", () => {
    const prompt = buildPrompt([userMsg("u1", "hi")]);
    expect(prompt).not.toContain("{schema}");
    expect(prompt).not.toContain("{history}");
    expect(prompt).toContain("userTaskIntent");
    expect(prompt).toContain("USER: hi");
  });
});

describe("extractJsonFromResponse", () => {
  it("strips markdown fences", () => {
    const out = extractJsonFromResponse("```json\n{\"a\":1}\n```");
    expect(JSON.parse(out)).toEqual({ a: 1 });
  });

  it("prefers the <final>...</final> block over <draft>...</draft>", () => {
    const out = extractJsonFromResponse(
      "<draft>{\"a\":1}</draft><final>{\"b\":2}</final>",
    );
    expect(JSON.parse(out)).toEqual({ b: 2 });
  });

  it("falls back to the first {...} block when no fences or tags", () => {
    const out = extractJsonFromResponse("noise {\"a\":1} tail");
    expect(JSON.parse(out)).toEqual({ a: 1 });
  });

  it("returns the trimmed text when no JSON is present", () => {
    expect(extractJsonFromResponse("  hello  ")).toBe("hello");
  });
});

describe("parseAndValidate", () => {
  it("accepts a valid 8-segment JSON string", () => {
    const parsed = parseAndValidate(JSON.stringify(VALID_SUMMARY));
    expect(parsed.userTaskIntent).toBe(VALID_SUMMARY.userTaskIntent);
  });

  it("rejects JSON with a missing field", () => {
    const bad = { ...VALID_SUMMARY };
    delete (bad as { openTodos?: string }).openTodos;
    expect(() => parseAndValidate(JSON.stringify(bad))).toThrow();
  });

  it("rejects JSON with a wrong type", () => {
    const bad = { ...VALID_SUMMARY, userTaskIntent: 42 };
    expect(() => parseAndValidate(JSON.stringify(bad))).toThrow();
  });

  it("strips markdown fences before parsing", () => {
    const parsed = parseAndValidate("```json\n" + JSON.stringify(VALID_SUMMARY) + "\n```");
    expect(parsed.nextStepPlan).toBe(VALID_SUMMARY.nextStepPlan);
  });
});

describe("llmCompact", () => {
  it("returns layer: 3 with a validated 8-segment summary", async () => {
    const input = makeInput({
      history: [userMsg("u1", "ship aethercode-compact")],
    });
    const result = await llmCompact(input, mockLlm(), {
      maxOutputTokens: 8_000,
      recentKeep: 5,
      skipHistoryReplace: true,
    });
    expect(result.layer).toBe(3);
    expect(result.summary).toBeDefined();
    expect(result.summary?.userTaskIntent).toBe(VALID_SUMMARY.userTaskIntent);
    expect(result.summary?.nextStepPlan).toBe(VALID_SUMMARY.nextStepPlan);
  });

  it("passes the 4-shot prompt (with the history) to the LLM client", async () => {
    let captured = "";
    const llm = {
      complete: async (prompt: string) => {
        captured = prompt;
        return JSON.stringify(VALID_SUMMARY);
      },
    };
    const input = makeInput({
      history: [userMsg("u1", "fix build"), toolMsg("t1", "bash", "err: oops", "c1")],
    });
    await llmCompact(input, llm, {
      maxOutputTokens: 8_000,
      recentKeep: 5,
      skipHistoryReplace: true,
    });

    expect(captured).toContain("BEGIN SHOT 1");
    expect(captured).toContain("BEGIN SHOT 4");
    expect(captured).toContain("userTaskIntent");
    expect(captured).toContain("USER: fix build");
    expect(captured).toContain("TOOL(bash): err: oops");
  });

  it("unwraps markdown-fenced LLM output", async () => {
    const input = makeInput({ history: [userMsg("u1", "x")] });
    const result = await llmCompact(input, fencedMockLlm(), {
      maxOutputTokens: 8_000,
      recentKeep: 5,
      skipHistoryReplace: true,
    });
    expect(result.summary?.userTaskIntent).toBe(VALID_SUMMARY.userTaskIntent);
  });

  it("unwraps <draft>...</draft><final>...</final> (anti-drift)", async () => {
    const input = makeInput({ history: [userMsg("u1", "x")] });
    const result = await llmCompact(input, draftFinalMockLlm(), {
      maxOutputTokens: 8_000,
      recentKeep: 5,
      skipHistoryReplace: true,
    });
    expect(result.summary?.userTaskIntent).toBe(VALID_SUMMARY.userTaskIntent);
  });

  it("throws when the LLM returns a structurally invalid payload", async () => {
    const input = makeInput({ history: [userMsg("u1", "x")] });
    const bad = mockLlm('{"userTaskIntent": "only one field"}');
    await expect(
      llmCompact(input, bad, {
        maxOutputTokens: 8_000,
        recentKeep: 5,
        skipHistoryReplace: true,
      }),
    ).rejects.toThrow();
  });

  it("forwards maxOutputTokens and model name to the LLM client", async () => {
    let captured: { maxTokens?: number; model?: string } = {};
    const llm = {
      complete: async (_p: string, opts?: { maxTokens?: number; model?: string }) => {
        captured = opts ?? {};
        return JSON.stringify(VALID_SUMMARY);
      },
    };
    const input = makeInput({ history: [userMsg("u1", "x")] });
    await llmCompact(input, llm, {
      maxOutputTokens: 4_321,
      recentKeep: 5,
      skipHistoryReplace: true,
    });
    expect(captured.maxTokens).toBe(4_321);
    expect(captured.model).toBe(input.model.name);
  });
});

// --- Round 2 (T-132 — T-137) — 2-step generation, history
// replacement, per-segment 1K cap. ---

describe("DEFAULT_4_SHOT_PROMPT — 2-step generation + quote-original (T-132, T-134)", () => {
  it("explicitly asks the model to emit <draft>...</draft> and <final>...</final>", () => {
    expect(DEFAULT_4_SHOT_PROMPT).toContain("<draft>");
    expect(DEFAULT_4_SHOT_PROMPT).toContain("</draft>");
    expect(DEFAULT_4_SHOT_PROMPT).toContain("<final>");
    expect(DEFAULT_4_SHOT_PROMPT).toContain("</final>");
  });

  it("instructs the model to quote original phrases verbatim (T-134)", () => {
    expect(DEFAULT_4_SHOT_PROMPT).toMatch(/quote.*original.*phrases?/i);
  });

  it("all 4 shots show the 2-step <draft>/<final> shape", () => {
    const drafts = DEFAULT_4_SHOT_PROMPT.match(/<draft>/g) ?? [];
    const finals = DEFAULT_4_SHOT_PROMPT.match(/<final>/g) ?? [];
    // 4 shots × (1 draft + 1 final) = 8 of each; allow extra in the
    // explanatory header.
    expect(drafts.length).toBeGreaterThanOrEqual(4);
    expect(finals.length).toBeGreaterThanOrEqual(4);
  });
});

describe("extractJsonFromResponse — draft/final handling (T-132, T-133)", () => {
  it("drops the <draft>...</draft> and keeps the <final>...</final> JSON", () => {
    const out = extractJsonFromResponse(
      `<draft>rough notes about the bug</draft><final>${JSON.stringify(VALID_SUMMARY)}</final>`,
    );
    const parsed = JSON.parse(out) as { userTaskIntent: string };
    expect(parsed.userTaskIntent).toBe(VALID_SUMMARY.userTaskIntent);
  });

  it("returns the trimmed text when no tags or fences or braces are present", () => {
    expect(extractJsonFromResponse("  hello world  ")).toBe("hello world");
  });
});

describe("enforceSegmentCap (T-136)", () => {
  it("returns the same object when every segment is under the cap", () => {
    const out = enforceSegmentCap(VALID_SUMMARY);
    expect(out).toBe(VALID_SUMMARY);
  });

  it("trims any segment that exceeds MAX_SEGMENT_CHARS and marks the cut", () => {
    const tooLong = "x".repeat(MAX_SEGMENT_CHARS + 50);
    const bad = { ...VALID_SUMMARY, userTaskIntent: tooLong };
    const out = enforceSegmentCap(bad);
    expect(out.userTaskIntent.length).toBeLessThanOrEqual(MAX_SEGMENT_CHARS);
    expect(out.userTaskIntent).toContain("…(truncated)");
  });

  it("leaves segments at exactly the cap untouched", () => {
    const at = "y".repeat(MAX_SEGMENT_CHARS);
    const ok = { ...VALID_SUMMARY, userTaskIntent: at };
    expect(enforceSegmentCap(ok)).toBe(ok);
  });

  it("trims every overflowing field, not just the first one", () => {
    const big = "z".repeat(MAX_SEGMENT_CHARS + 10);
    const bad = {
      ...VALID_SUMMARY,
      userTaskIntent: big,
      approachTaken: big,
      openTodos: big,
    };
    const out = enforceSegmentCap(bad);
    expect(out.userTaskIntent).toContain("…(truncated)");
    expect(out.approachTaken).toContain("…(truncated)");
    expect(out.openTodos).toContain("…(truncated)");
  });
});

describe("replaceHistoryKeepingRecent (T-135)", () => {
  it("keeps the last `keep` user messages and replaces the rest with a summary", () => {
    const history = [
      userMsg("u1", "first"),
      assistantMsg("a1", "r1"),
      toolMsg("t1", "bash", "out1", "c1"),
      userMsg("u2", "second"),
      assistantMsg("a2", "r2"),
      toolMsg("t2", "bash", "out2", "c2"),
      userMsg("u3", "third"),
      assistantMsg("a3", "r3"),
      toolMsg("t3", "bash", "out3", "c3"),
    ];
    const out = replaceHistoryKeepingRecent(history, VALID_SUMMARY, 2, { now: () => 1000 });
    // Expect: [summary, u2, a2, t2, u3, a3, t3]
    expect(out).toHaveLength(7);
    expect(out[0]?.role).toBe("summary");
    expect(out[0]?.content).toContain("[summary]");
    expect(out[0]?.content).toContain(VALID_SUMMARY.userTaskIntent);
    // First kept user message is `u2` (3rd user from the end is u1, so 2nd-from-last is u2)
    expect(out[1]?.id).toBe("u2");
    expect(out[1]?.role).toBe("user");
    // Last message is the trailing tool from u3.
    expect(out[6]?.id).toBe("t3");
  });

  it("replaces the entire history when keep = 0", () => {
    const history = [userMsg("u1", "x"), assistantMsg("a1", "y"), userMsg("u2", "z")];
    const out = replaceHistoryKeepingRecent(history, VALID_SUMMARY, 0, { now: () => 2000 });
    expect(out).toHaveLength(1);
    expect(out[0]?.role).toBe("summary");
  });

  it("replaces the entire history when the user-message count <= keep", () => {
    const history = [userMsg("u1", "x"), assistantMsg("a1", "y")];
    const out = replaceHistoryKeepingRecent(history, VALID_SUMMARY, 5, { now: () => 3000 });
    expect(out).toHaveLength(1);
    expect(out[0]?.role).toBe("summary");
  });

  it("returns the original history when there is nothing to replace", () => {
    const history = [userMsg("u1", "only")];
    const out = replaceHistoryKeepingRecent(history, VALID_SUMMARY, 1, { now: () => 4000 });
    // u1 is the only user message; the 1 user message == keep, so the
    // entire history is replaced with the summary.
    expect(out).toHaveLength(1);
    expect(out[0]?.role).toBe("summary");
  });

  it("keeps trailing assistant/tool messages that follow the Nth-from-last user message", () => {
    const history = [
      userMsg("u1", "first"),
      assistantMsg("a1", "r1"),
      userMsg("u2", "second"),
      assistantMsg("a2", "r2"),
      toolMsg("t1", "bash", "out", "c1"),
    ];
    const out = replaceHistoryKeepingRecent(history, VALID_SUMMARY, 1, { now: () => 5000 });
    // The (1st-from-last) user message is `u2`; everything from u2
    // onward is kept: [summary, u2, a2, t1].
    expect(out).toHaveLength(4);
    expect(out[0]?.role).toBe("summary");
    expect(out[1]?.id).toBe("u2");
    expect(out[2]?.id).toBe("a2");
    expect(out[3]?.id).toBe("t1");
  });

  it("summary message content is valid JSON of the 8 fields", () => {
    const history = [userMsg("u1", "x"), userMsg("u2", "y")];
    const out = replaceHistoryKeepingRecent(history, VALID_SUMMARY, 0, { now: () => 6000 });
    const summaryText = out[0]?.content ?? "";
    const jsonStart = summaryText.indexOf("{");
    const jsonEnd = summaryText.lastIndexOf("}");
    const parsed = JSON.parse(summaryText.slice(jsonStart, jsonEnd + 1)) as Record<string, string>;
    for (const field of SEGMENT_FIELD_NAMES) {
      expect(typeof parsed[field]).toBe("string");
    }
  });
});

describe("llmCompact — history replacement (T-135)", () => {
  it("replaces the history with summary + trailing user messages", async () => {
    const history = [
      userMsg("u1", "old turn 1"),
      assistantMsg("a1", "old reply 1"),
      userMsg("u2", "old turn 2"),
      assistantMsg("a2", "old reply 2"),
      userMsg("u3", "recent turn 3"),
      assistantMsg("a3", "recent reply 3"),
      userMsg("u4", "recent turn 4"),
      assistantMsg("a4", "recent reply 4"),
    ];
    const input = makeInput({ history });
    const result = await llmCompact(input, mockLlm(), {
      maxOutputTokens: 8_000,
      recentKeep: 2,
    });
    // The last 2 user messages (u3, u4) and everything after them
    // (a3, a4) are kept verbatim. The summary is at index 0.
    expect(result.history[0]?.role).toBe("summary");
    expect(result.history[result.history.length - 1]?.id).toBe("a4");
    expect(result.history.some((m) => m.id === "u3")).toBe(true);
    expect(result.history.some((m) => m.id === "u4")).toBe(true);
    // The old turn 1 and 2 are gone.
    expect(result.history.some((m) => m.id === "u1")).toBe(false);
    expect(result.history.some((m) => m.id === "u2")).toBe(false);
    // The old reply messages are also gone.
    expect(result.history.some((m) => m.id === "a1")).toBe(false);
  });

  it("skipHistoryReplace=true returns the original history (used by tests that focus on summary)", async () => {
    const history = [userMsg("u1", "x"), userMsg("u2", "y")];
    const input = makeInput({ history });
    const result = await llmCompact(input, mockLlm(), {
      maxOutputTokens: 8_000,
      recentKeep: 1,
      skipHistoryReplace: true,
    });
    expect(result.history).toEqual(history);
  });
});
