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
