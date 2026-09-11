import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 4.1 (T-4-02): VariableSizeList (source-level).
 *
 * The component is a 200-line custom virtual list that
 * avoids the `react-window` dep. Tests assert the
 * structural properties the rest of the tree depends on:
 *   - Renders only the visible window (binary-search
 *     `findRowAt`)
 *   - Per-row variable height
 *   - rAF debounced scroll
 *   - Resize observer / scroll handler
 *   - Scroll-to-index imperative API
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.1 / T-4-02: VariableSizeList', () => {
  it('SessionListVirtual.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/session/SessionListVirtual.tsx'))).toBe(true);
  });

  it('exports VariableSizeList (generic)', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/export\s+function\s+VariableSizeList\b/);
    expect(src).toMatch(/VariableSizeList<T>/);
  });

  it('uses binary search for findRowAt (no linear scan)', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/findRowAt/);
    expect(src).toMatch(/lo\s*=\s*0/);
    expect(src).toMatch(/hi\s*=\s*offsets\.length/);
    expect(src).toMatch(/>>\s*1/);
  });

  it('supports per-row variable height', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/itemHeight\?:\s*\(index:\s*number,\s*item:\s*T\)/);
  });

  it('rAF-debounces the scroll handler', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/requestAnimationFrame/);
    expect(src).toMatch(/rafRef/);
  });

  it('supports overscan to smooth fast scrolls', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/overscan\s*\?:\s*number/);
    expect(src).toMatch(/overscan\s*=\s*4/);
  });

  it('exposes scrollToIndex for imperative scroll', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/scrollToIndex\s*=\s*useCallback/);
  });

  it('uses absolute positioning for the rendered window', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/position:\s*['"]absolute['"]/);
  });

  it('emits a visible-range change callback', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/onVisibleRangeChange/);
  });

  it('role=listbox for accessibility', () => {
    const src = read('src/components/session/SessionListVirtual.tsx');
    expect(src).toMatch(/role="listbox"/);
  });
});
