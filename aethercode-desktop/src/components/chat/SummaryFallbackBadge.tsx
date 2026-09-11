// Phase 7: SummaryFallbackBadge (T-7-04).
//
// When the post-turn auto-summary hook fails to produce a `##
// Summary` block, the renderer falls back to the last assistant
// message and surfaces a small inline badge so the user knows the
// summary was synthesised rather than extracted from the model's
// own response.

import type { SummaryBlock } from '../../rpc/types';

export interface SummaryFallbackBadgeProps {
  summary: SummaryBlock | null;
}

export function SummaryFallbackBadge({ summary }: SummaryFallbackBadgeProps) {
  if (!summary || !summary.fallback) return null;
  return (
    <span
      className="summary-fallback-badge"
      data-testid="summary-fallback-badge"
      role="status"
      title="The model didn't emit a `## Summary` block; the post-turn hook injected a fallback."
    >
      ⚠ auto-summary failed
    </span>
  );
}
