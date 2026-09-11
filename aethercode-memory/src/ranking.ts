/**
 * R-MEM-6.3 (F8+ type-specific ranking).
 *
 * The Tulving-style memory types added in R-MEM-5.3 are
 * classification-only so far: `memory/find` filters by
 * `memoryType` but the ranking inside a single type is plain
 * cosine similarity. That's a missed opportunity — the
 * three types want different weighting:
 *
 *  - **procedural** (skills): rows with a higher
 *    `success_count` should outrank ones with fewer successes,
 *    because "the agent has done this 10 times successfully" is
 *    a stronger signal than "the embedding matches". We add
 *    `log(1 + success_count) * 0.1` to the score.
 *
 *  - **episodic** (project changes, session facts): recent
 *    events outrank older ones, but the recency effect should
 *    decay smoothly over ~30 days. We add
 *    `max(0, 1 - age_days / 30) * 0.1`.
 *
 *  - **semantic** (global rules, image descriptions): no
 *    adjustment. Facts are facts; the user can ask "what do
 *    I know about X?" and get the most-relevant row regardless
 *    of when it was written.
 *
 * The boost values are tuned so a 10x more reliable procedural
 * row or a brand-new episodic row can outrank a slightly
 * better cosine match, but a much closer cosine match always
 * wins. The `0.1` coefficient is deliberately small.
 *
 * The function is pure: it takes the search results + an
 * enrichment provider (for `success_count` and `ts`) and
 * returns the same rows re-ordered. The MemoryStore.findSimilar
 * wrapper supplies the enrichment.
 */

import type { VectorRow, MemoryType } from './vec-store.js';

/** Parameters for `rankByType`. */
export interface RankByTypeParams {
  /** When the search happened. Used for the episodic recency
   *  decay window. Default `Date.now()`. */
  readonly now?: number;
  /** Coefficients (override for tuning). Default values are
   *  tuned for the 384-dim hash embedding. */
  readonly proceduralBoost?: number;
  readonly episodicBoost?: number;
  /** Recency window in ms for episodic rows. Default 30 days. */
  readonly recencyWindowMs?: number;
  /** Enrichment: given a row, return the row's `success_count`
   *  (procedural) or `0` (everything else). Default returns 0. */
  readonly getSuccessCount?: (row: VectorRow) => number;
  /** R-MEM-6.4 (F3 RL): enrichment for retrieval feedback.
   *  Given a row, return its (used, notUsed) tuple or null. */
  readonly getFeedback?: (row: VectorRow) => { used: number; notUsed: number } | null;
  /** Coefficient for the positive feedback boost. Default 0.1. */
  readonly feedbackUsedBoost?: number;
  /** Coefficient for the negative feedback penalty. Default 0.05. */
  readonly feedbackNotUsedPenalty?: number;
}

/** A re-ranked row. `score` is the final score after the boost;
 *  `cosine_score` is the pre-boost value (for transparency). */
export interface RankedVectorRow extends VectorRow {
  readonly cosine_score: number;
  readonly boost: number;
  /** Why the boost fired (or 'none'). Useful for debugging
   *  and for tests that pin the ranking. */
  readonly boostReason: 'procedural-success' | 'episodic-recency' | 'feedback-used' | 'feedback-not-used' | 'mixed' | 'none';
  /** R-MEM-6.4: the per-source boost breakdown (for debug
   *  surface). `total = sum(parts)`. */
  readonly boostBreakdown: {
    readonly procedural: number;
    readonly episodic: number;
    readonly feedbackUsed: number;
    readonly feedbackNotUsed: number;
  };
}

/** Apply type-specific ranking to a list of `VectorRow`s. The
 *  rows are returned in score-desc order. The original `score`
 *  is preserved as `cosine_score`; the new `score` includes the
 *  boost. */
export function rankByType(
  rows: ReadonlyArray<VectorRow>,
  params: RankByTypeParams = {},
): RankedVectorRow[] {
  const now = params.now ?? Date.now();
  const proceduralBoost = params.proceduralBoost ?? 0.1;
  const episodicBoost = params.episodicBoost ?? 0.1;
  const feedbackUsedBoost = params.feedbackUsedBoost ?? 0.1;
  const feedbackNotUsedPenalty = params.feedbackNotUsedPenalty ?? 0.05;
  const recencyWindowMs = params.recencyWindowMs ?? 30 * 24 * 60 * 60 * 1000;
  const getSuccessCount = params.getSuccessCount ?? ((): number => 0);
  const getFeedback = params.getFeedback ?? ((): { used: number; notUsed: number } | null => null);

  const ranked: RankedVectorRow[] = rows.map((r) => {
    const cosine = r.score ?? 0;
    let procedural = 0;
    let episodic = 0;
    let feedbackUsed = 0;
    let feedbackNotUsed = 0;
    if (r.memory_type === 'procedural') {
      const successes = getSuccessCount(r);
      if (successes > 0) procedural = Math.log(1 + successes) * proceduralBoost;
    } else if (r.memory_type === 'episodic') {
      const ageMs = Math.max(0, now - r.ts);
      if (ageMs < recencyWindowMs) {
        episodic = (1 - ageMs / recencyWindowMs) * episodicBoost;
      }
    }
    // R-MEM-6.4: feedback loop.
    const fb = getFeedback(r);
    if (fb !== null) {
      if (fb.used > 0) {
        feedbackUsed = Math.log(1 + fb.used) * feedbackUsedBoost;
      }
      if (fb.notUsed > 0) {
        feedbackNotUsed = -Math.log(1 + fb.notUsed) * feedbackNotUsedPenalty;
      }
    }
    const total = procedural + episodic + feedbackUsed + feedbackNotUsed;
    // Pick a primary reason for the boost (for the wire).
    // 'mixed' fires when more than one source contributes. The
    // priority order below is for the single-source case.
    const sources =
      Number(procedural > 0) +
      Number(episodic > 0) +
      Number(feedbackUsed > 0) +
      Number(feedbackNotUsed < 0);
    let reason: RankedVectorRow['boostReason'] = 'none';
    if (sources > 1) reason = 'mixed';
    else if (feedbackUsed > 0) reason = 'feedback-used';
    else if (feedbackNotUsed < 0) reason = 'feedback-not-used';
    else if (procedural > 0) reason = 'procedural-success';
    else if (episodic > 0) reason = 'episodic-recency';
    return {
      ...r,
      cosine_score: cosine,
      boost: total,
      boostReason: reason,
      boostBreakdown: { procedural, episodic, feedbackUsed, feedbackNotUsed },
      score: cosine + total,
    };
  });

  ranked.sort((a, b) => {
    const bs = b.score ?? 0;
    const as = a.score ?? 0;
    if (bs !== as) return bs - as;
    // Stable tiebreaker: prefer higher cosine so the boost
    // never *demotes* a row that started ahead.
    return (b.cosine_score ?? 0) - (a.cosine_score ?? 0);
  });
  return ranked;
}

/** Helper: only apply type-specific ranking when the caller
 *  asked for a Tulving-typed query. For untyped queries the
 *  original cosine order is preserved (no overhead, no
 *  semantic shift). */
export function shouldApplyTypeRanking(memoryType: MemoryType | undefined): boolean {
  return memoryType !== undefined;
}
