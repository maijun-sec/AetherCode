/**
 * Partial compact support (T-160 → T-161).
 *
 * Per `design.md §2.5` and `spec.md §2.4`:
 *
 * - `aethercode compact --from <id>` — compact everything *after* `<id>`,
 *   keeping the prefix. Preserves the prefix cache because the model's
 *   view of the prefix is unchanged.
 * - `aethercode compact --up-to <id>` — compact everything *before*
 *   `<id>`, keeping the suffix. Breaks the prefix cache because the
 *   earlier content has been replaced.
 *
 * Both options are implemented as special-cases over the existing
 * Layer 1+3 pipeline: a slice of the history is fed to Layer 3 and the
 * produced summary message replaces that slice. The kept slice is
 * concatenated back around the summary to form the new history.
 *
 * The helpers in this module are pure: they take a `Message[]`,
 * produce the new `Message[]`, and report the kept / compacted slice
 * boundaries. The pipeline driver is responsible for invoking Layer 1
 * and Layer 3 and constructing the `CompactResult` envelope.
 */

import { replaceHistoryKeepingRecent } from "./layer3.js";
import type { CompactSummary, Message } from "./types.js";

/** Locate the index of a message by id. Returns `-1` when not found. */
export function findMessageIndex(
  history: ReadonlyArray<Message>,
  id: string,
): number {
  for (let i = 0; i < history.length; i++) {
    if (history[i]?.id === id) {
      return i;
    }
  }
  return -1;
}

/** Result of a partial compact. */
export type PartialCompactPlan =
  | {
      /** "from" mode: keep prefix, compact suffix. */
      mode: "from";
      /** Index of the anchor message `<id>`. */
      anchorIndex: number;
      /** The kept prefix (history[0..anchorIndex]). */
      keptPrefix: ReadonlyArray<Message>;
      /** The slice to be fed to Layer 3 (history[anchorIndex+1..]). */
      compactedSlice: ReadonlyArray<Message>;
      /** True iff the anchor exists in the input. */
      found: true;
    }
  | {
      mode: "from";
      anchorIndex: -1;
      keptPrefix: [];
      compactedSlice: ReadonlyArray<Message>;
      found: false;
    }
  | {
      /** "upTo" mode: compact prefix, keep suffix. */
      mode: "upTo";
      anchorIndex: number;
      /** The slice to be fed to Layer 3 (history[0..anchorIndex]). */
      compactedSlice: ReadonlyArray<Message>;
      /** The kept suffix (history[anchorIndex..]). */
      keptSuffix: ReadonlyArray<Message>;
      found: true;
    }
  | {
      mode: "upTo";
      anchorIndex: -1;
      compactedSlice: ReadonlyArray<Message>;
      keptSuffix: [];
      found: false;
    };

/** Build the plan for `--from <id>`. */
export function planCompactFrom(
  history: ReadonlyArray<Message>,
  anchorId: string,
): PartialCompactPlan {
  const idx = findMessageIndex(history, anchorId);
  if (idx < 0) {
    return {
      mode: "from",
      anchorIndex: -1,
      keptPrefix: [],
      compactedSlice: history,
      found: false,
    };
  }
  const keptPrefix: Message[] = [];
  for (let i = 0; i <= idx; i++) {
    const m = history[i];
    if (m) {
      keptPrefix.push({ ...m });
    }
  }
  const compactedSlice: Message[] = [];
  for (let i = idx + 1; i < history.length; i++) {
    const m = history[i];
    if (m) {
      compactedSlice.push({ ...m });
    }
  }
  return {
    mode: "from",
    anchorIndex: idx,
    keptPrefix,
    compactedSlice,
    found: true,
  };
}

/** Build the plan for `--up-to <id>`. */
export function planCompactUpTo(
  history: ReadonlyArray<Message>,
  anchorId: string,
): PartialCompactPlan {
  const idx = findMessageIndex(history, anchorId);
  if (idx < 0) {
    return {
      mode: "upTo",
      anchorIndex: -1,
      compactedSlice: history,
      keptSuffix: [],
      found: false,
    };
  }
  // The anchor is the upper boundary: messages strictly *before* the
  // anchor go into the compacted slice; the anchor and everything
  // after are kept verbatim as the suffix.
  const compactedSlice: Message[] = [];
  for (let i = 0; i < idx; i++) {
    const m = history[i];
    if (m) {
      compactedSlice.push({ ...m });
    }
  }
  const keptSuffix: Message[] = [];
  for (let i = idx; i < history.length; i++) {
    const m = history[i];
    if (m) {
      keptSuffix.push({ ...m });
    }
  }
  return {
    mode: "upTo",
    anchorIndex: idx,
    compactedSlice,
    keptSuffix,
    found: true,
  };
}

/**
 * Slice off the *compacted* portion of a history for Layer 3 to summarize.
 * The compacted slice is exactly what gets passed to the LLM; the kept
 * slice is concatenated back later by `applyPartialCompact`.
 */
export function sliceForCompact(
  history: ReadonlyArray<Message>,
  mode: "from" | "upTo",
  anchorId: string,
): { plan: PartialCompactPlan; slice: ReadonlyArray<Message> } {
  if (mode === "from") {
    const plan = planCompactFrom(history, anchorId);
    if (!plan.found) {
      return { plan, slice: plan.compactedSlice };
    }
    return { plan, slice: plan.compactedSlice };
  }
  const plan = planCompactUpTo(history, anchorId);
  return { plan, slice: plan.compactedSlice };
}

/**
 * Build the new history from the kept slice(s) and the summary produced
 * by Layer 3 over the compacted slice. The summary is emitted as a
 * single `summary` role message in place of the compacted slice.
 *
 * The summary's `ts` is taken from the *first* message of the compacted
 * slice when available, so the new history's message ordering aligns
 * with the original (T-160/T-161) and the post-compact position lines
 * up with what the model expects.
 */
export function applyPartialCompact(
  keptPrefix: ReadonlyArray<Message>,
  summary: CompactSummary,
  keptSuffix: ReadonlyArray<Message>,
  options: { now?: () => number } = {},
): Message[] {
  const now = options.now ?? Date.now;
  const ts = now();
  const summaryMsg: Message = {
    id: `summary:${ts}`,
    role: "summary",
    content: `[summary] partial-compact (from/upTo)\n${JSON.stringify(summary, null, 2)}`,
    ts,
  };
  const out: Message[] = [];
  for (const m of keptPrefix) {
    out.push({ ...m });
  }
  out.push(summaryMsg);
  for (const m of keptSuffix) {
    out.push({ ...m });
  }
  return out;
}

/** Concatenate kept prefix + summary, dropping the kept suffix. */
export function applyPartialCompactFrom(
  plan: Extract<PartialCompactPlan, { mode: "from"; found: true }>,
  summary: CompactSummary,
  options: { now?: () => number } = {},
): Message[] {
  return applyPartialCompact(plan.keptPrefix, summary, [], options);
}

/** Concatenate summary + kept suffix, dropping the kept prefix. */
export function applyPartialCompactUpTo(
  plan: Extract<PartialCompactPlan, { mode: "upTo"; found: true }>,
  summary: CompactSummary,
  options: { now?: () => number } = {},
): Message[] {
  return applyPartialCompact([], summary, plan.keptSuffix, options);
}

/**
 * Convenience helper used by the pipeline driver: when a `--from` or
 * `--up_to` compact is requested, run Layer 3 over the compacted slice
 * and return a `replaceHistoryKeepingRecent`-style replacement scoped
 * to that slice.
 *
 * The returned value can be concatenated with the kept slice(s) by the
 * caller via `applyPartialCompact`. The number of "recent user
 * messages to keep" inside the slice is `recentKeep`; the kept
 * prefix/suffix is not touched.
 */
export function summarizeSliceForPartial(
  slice: ReadonlyArray<Message>,
  summary: CompactSummary,
  recentKeep: number,
  options: { now?: () => number } = {},
): Message[] {
  return replaceHistoryKeepingRecent(slice, summary, recentKeep, options);
}

/**
 * Describe the cache impact of a partial-compact plan. Used by tests
 * and by the TUI hint banner (T-162). For `--from <id>` the prefix is
 * preserved, so the cache should still be warm; for `--up_to <id>` the
 * earlier content is replaced, breaking the prefix cache.
 */
export function partialCompactCacheImpact(plan: PartialCompactPlan): {
  /** True when the plan replaces content that would invalidate the prefix cache. */
  breaksPrefixCache: boolean;
  /** Human-readable reason, e.g. "kept prefix" or "replaces prefix". */
  reason: string;
} {
  if (!plan.found) {
    return { breaksPrefixCache: true, reason: "anchor not found; fallback to full compact" };
  }
  if (plan.mode === "from") {
    return { breaksPrefixCache: false, reason: "kept prefix intact" };
  }
  return { breaksPrefixCache: true, reason: "replaces prefix" };
}

/**
 * Parse a `--from` / `--up-to` option set. Used by the CLI driver (the
 * actual CLI binary is wired in a later task; this parser is the
 * contract between the option and the pipeline). Returns `null` when
 * no partial compact is requested.
 */
export function parsePartialCompactOptions(args: {
  from?: string | undefined;
  upTo?: string | undefined;
}): { mode: "from" | "upTo"; anchorId: string } | null {
  if (args.from && args.upTo) {
    throw new Error("aethercode compact: --from and --up-to are mutually exclusive");
  }
  if (args.from) {
    return { mode: "from", anchorId: args.from };
  }
  if (args.upTo) {
    return { mode: "upTo", anchorId: args.upTo };
  }
  return null;
}
