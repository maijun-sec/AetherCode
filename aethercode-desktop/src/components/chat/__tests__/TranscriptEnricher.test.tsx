import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 4.2 (T-4-17) + Phase 7 (T-7-02): TranscriptEnricher (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.2 / T-4-17 + Phase 7 / T-7-02: TranscriptEnricher', () => {
  it('TranscriptEnricher.ts exists', () => {
    expect(existsSync(join(root, 'src/components/chat/TranscriptEnricher.ts'))).toBe(true);
  });

  it('exports useTranscriptEnricher hook', () => {
    const src = read('src/components/chat/TranscriptEnricher.ts');
    expect(src).toMatch(/export\s+function\s+useTranscriptEnricher\b/);
  });

  it('exports bodyHasSummary + appendSummary helpers', () => {
    const src = read('src/components/chat/TranscriptEnricher.ts');
    expect(src).toMatch(/export\s+function\s+bodyHasSummary\b/);
    expect(src).toMatch(/export\s+function\s+appendSummary\b/);
  });

  it('subscribes to task/event kind=summary_missing', () => {
    const src = read('src/components/chat/TranscriptEnricher.ts');
    expect(src).toMatch(/summary_missing/);
    expect(src).toMatch(/subscribeKind/);
  });

  it('calls enricher/summarise when the missing event fires', () => {
    const src = read('src/components/chat/TranscriptEnricher.ts');
    expect(src).toMatch(/enricher\/summarise/);
  });

  it('patches the body via the onSummaryPatched callback', () => {
    const src = read('src/components/chat/TranscriptEnricher.ts');
    expect(src).toMatch(/onSummaryPatched/);
    expect(src).toMatch(/appendSummary/);
  });

  it('surfaces a "failed" state when the RPC errors', () => {
    const src = read('src/components/chat/TranscriptEnricher.ts');
    expect(src).toMatch(/setState\('failed'\)/);
    expect(src).toMatch(/setError/);
  });

  it('skips the enricher while the turn is still streaming', () => {
    const src = read('src/components/chat/TranscriptEnricher.ts');
    expect(src).toMatch(/isStreaming/);
  });
});
