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
 * Zod schema for the 8-segment compact summary.
 *
 * Field order is fixed and matches `design.md §2.4` and `types.CompactSummary`.
 * Each string field is capped at the 1K-token equivalent (`MAX_SEGMENT_CHARS`).
 *
 * `MAX_SEGMENT_CHARS = 4_000` follows the common rule of thumb of ~4 characters
 * per token for English text. Tighter budgeting is enforced separately
 * (T-136 — per-segment 1K-token cap enforcement); this schema enforces the
 * byte-level ceiling so a runaway model cannot break the prompt budget.
 */
import { z } from "zod";

/** Per-segment character cap, ≈ 1K tokens at 4 chars/token. */
export const MAX_SEGMENT_CHARS = 4_000;

const segmentField = z
  .string()
  .min(1, "segment must not be empty")
  .max(MAX_SEGMENT_CHARS, `segment exceeds ${MAX_SEGMENT_CHARS} chars (~1K tokens)`);

/** Zod schema for the 8-segment compact summary. Order is fixed. */
export const compactSummarySchema = z
  .object({
    userTaskIntent: segmentField,
    projectContext: segmentField,
    approachTaken: segmentField,
    bugsAndFailures: segmentField,
    toolOutputsRetained: segmentField,
    decisionsAndTradeoffs: segmentField,
    openTodos: segmentField,
    nextStepPlan: segmentField,
  })
  // The schema is closed; reject unknown fields so a verbose model cannot
  // smuggle extra context into the injected history.
  .strict();

/** Inferred TypeScript type — mirrors `CompactSummary` in `types.ts`. */
export type CompactSummarySchema = z.infer<typeof compactSummarySchema>;

/** Field names in declaration order, used by the prompt template. */
export const SEGMENT_FIELD_NAMES = [
  "userTaskIntent",
  "projectContext",
  "approachTaken",
  "bugsAndFailures",
  "toolOutputsRetained",
  "decisionsAndTradeoffs",
  "openTodos",
  "nextStepPlan",
] as const;
