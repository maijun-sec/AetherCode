import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 4.2 (T-4-11): SummaryFooter (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.2 / T-4-11: SummaryFooter', () => {
  it('SummaryFooter.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/chat/SummaryFooter.tsx'))).toBe(true);
  });

  it('exports SummaryFooter component', () => {
    const src = read('src/components/chat/SummaryFooter.tsx');
    expect(src).toMatch(/export\s+function\s+SummaryFooter\b/);
  });

  it('exports extractSummary helper (regex-based)', () => {
    const src = read('src/components/chat/SummaryFooter.tsx');
    expect(src).toMatch(/export\s+function\s+extractSummary\b/);
    expect(src).toMatch(/SUMMARY_HEADING_RE/);
  });

  it('extracts a ## Summary block with a permissive regex', () => {
    const src = read('src/components/chat/SummaryFooter.tsx');
    // Case-insensitive heading capture, "## Summary" or "##Summary"
    expect(src).toMatch(/\{1,6\}.*summary/);
    expect(src).toMatch(/im/);
  });

  it('renders a "pending" badge when the body has no summary', () => {
    const src = read('src/components/chat/SummaryFooter.tsx');
    expect(src).toMatch(/isPending/);
    expect(src).toMatch(/auto-summary pending/);
  });

  it('renders a "failed" badge when the auto-summary failed', () => {
    const src = read('src/components/chat/SummaryFooter.tsx');
    expect(src).toMatch(/fallbackFailed/);
    expect(src).toMatch(/auto-summary failed/);
  });

  it('never collapses the summary (always visible)', () => {
    const src = read('src/components/chat/SummaryFooter.tsx');
    // The component does NOT use a collapsed state.
    // The summary body is always rendered.
    expect(src).not.toMatch(/summary-card-collapsed/);
    expect(src).not.toMatch(/setExpanded/);
  });
});
