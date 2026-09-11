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
