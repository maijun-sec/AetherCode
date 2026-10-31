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
