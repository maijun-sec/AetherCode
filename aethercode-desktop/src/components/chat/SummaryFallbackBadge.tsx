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
