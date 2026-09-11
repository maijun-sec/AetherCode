import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 5 (T-5-03): TokenUsage (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 5 / T-5-03: TokenUsage', () => {
  it('TokenUsage.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/TokenUsage.tsx'))).toBe(true);
  });

  it('exports TokenUsage component', () => {
    const src = read('src/components/TokenUsage.tsx');
    expect(src).toMatch(/export\s+function\s+TokenUsage\b/);
  });

  it('accepts an optional sessionId prop', () => {
    const src = read('src/components/TokenUsage.tsx');
    expect(src).toMatch(/sessionId\?:\s*string\s*\|\s*null/);
  });

  it('wires to useSessionTokens (TanStack Query)', () => {
    const src = read('src/components/TokenUsage.tsx');
    expect(src).toMatch(/useSessionTokens/);
  });

  it('subscribes to per-session usage events for live ticks', () => {
    const src = read('src/components/TokenUsage.tsx');
    expect(src).toMatch(/subscribeKind/);
    expect(src).toMatch(/'usage'/);
  });

  it('displays in / out / total / tools cells', () => {
    const src = read('src/components/TokenUsage.tsx');
    expect(src).toMatch(/token-cell/);
    expect(src).toMatch(/token-label/);
  });
});
