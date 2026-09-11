/**
 * Tests for the 8-segment Zod schema (T-131).
 *
 * Covers:
 *  - 8 fields, in order
 *  - each field is a non-empty string
 *  - per-segment character cap (~1K tokens)
 *  - strict: unknown fields rejected
 *  - missing fields rejected
 */
import { describe, expect, it } from "vitest";

import { compactSummarySchema, MAX_SEGMENT_CHARS, SEGMENT_FIELD_NAMES } from "../schema.js";
import { VALID_SUMMARY } from "./fixtures.js";

describe("compactSummarySchema (8-segment)", () => {
  it("declares 8 fields in the documented order", () => {
    expect(SEGMENT_FIELD_NAMES).toEqual([
      "userTaskIntent",
      "projectContext",
      "approachTaken",
      "bugsAndFailures",
      "toolOutputsRetained",
      "decisionsAndTradeoffs",
      "openTodos",
      "nextStepPlan",
    ]);
    const shape = compactSummarySchema.shape;
    expect(Object.keys(shape)).toEqual([...SEGMENT_FIELD_NAMES]);
  });

  it("accepts a valid summary", () => {
    const parsed = compactSummarySchema.parse(VALID_SUMMARY);
    expect(parsed.userTaskIntent).toBe(VALID_SUMMARY.userTaskIntent);
    expect(parsed.nextStepPlan).toBe(VALID_SUMMARY.nextStepPlan);
  });

  it("rejects an empty string in any field", () => {
    const bad = { ...VALID_SUMMARY, userTaskIntent: "" };
    expect(() => compactSummarySchema.parse(bad)).toThrow();
  });

  it("rejects a missing field", () => {
    const { userTaskIntent: _omit, ...rest } = VALID_SUMMARY;
    void _omit;
    expect(() => compactSummarySchema.parse(rest)).toThrow();
  });

  it("rejects an unknown field (strict mode)", () => {
    const bad = { ...VALID_SUMMARY, extra: "smuggled" };
    expect(() => compactSummarySchema.parse(bad)).toThrow();
  });

  it("rejects a non-string field", () => {
    const bad = { ...VALID_SUMMARY, openTodos: 42 };
    expect(() => compactSummarySchema.parse(bad)).toThrow();
  });

  it("rejects a field exceeding the per-segment cap", () => {
    const tooLong = "x".repeat(MAX_SEGMENT_CHARS + 1);
    const bad = { ...VALID_SUMMARY, userTaskIntent: tooLong };
    expect(() => compactSummarySchema.parse(bad)).toThrow();
  });

  it("accepts a field exactly at the cap", () => {
    const maxLen = "x".repeat(MAX_SEGMENT_CHARS);
    const ok = { ...VALID_SUMMARY, userTaskIntent: maxLen };
    const parsed = compactSummarySchema.parse(ok);
    expect(parsed.userTaskIntent.length).toBe(MAX_SEGMENT_CHARS);
  });
});
