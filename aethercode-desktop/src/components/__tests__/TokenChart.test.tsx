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
 * Phase 5 (T-5-04): TokenChart (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 5 / T-5-04: TokenChart', () => {
  it('TokenChart.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/TokenChart.tsx'))).toBe(true);
  });

  it('exports TokenChart component', () => {
    const src = read('src/components/TokenChart.tsx');
    expect(src).toMatch(/export\s+function\s+TokenChart\b/);
  });

  it('exports TokenChartWindow type (24h / 7d / all)', () => {
    const src = read('src/components/TokenChart.tsx');
    expect(src).toMatch(/export\s+type\s+TokenChartWindow\b/);
    expect(src).toMatch(/'24h'/);
    expect(src).toMatch(/'7d'/);
    expect(src).toMatch(/'all'/);
  });

  it('subscribes to per-session usage events', () => {
    const src = read('src/components/TokenChart.tsx');
    expect(src).toMatch(/subscribeKind/);
    expect(src).toMatch(/'usage'/);
  });

  it('renders a plain SVG (no chart library)', () => {
    const src = read('src/components/TokenChart.tsx');
    expect(src).toMatch(/<svg/);
    expect(src).toMatch(/<rect/);
    // No `recharts` / `d3` imports.
    expect(src).not.toMatch(/from\s+['"]recharts['"]/);
    expect(src).not.toMatch(/from\s+['"]d3['"]/);
  });

  it('caps the event buffer (bounded memory for 10-hour stress test)', () => {
    const src = read('src/components/TokenChart.tsx');
    expect(src).toMatch(/next\.splice/);
    expect(src).toMatch(/2_?000/);
  });

  it('auto-scales the y-axis to the bucket max', () => {
    const src = read('src/components/TokenChart.tsx');
    expect(src).toMatch(/max/);
  });
});
